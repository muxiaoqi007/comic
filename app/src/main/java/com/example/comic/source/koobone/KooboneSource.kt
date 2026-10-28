package com.example.comic.source.koobone

import android.content.Context
import android.net.Uri
import com.example.comic.data.DownloadStore
import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.source.DownloadResult
import com.example.comic.source.DownloadableSource
import com.example.comic.source.SourceException
import com.example.comic.source.remote.execute
import com.example.comic.source.remote.normalizeBaseUrl
import com.example.comic.util.compareTitles
import com.example.comic.util.isImageName
import com.example.comic.util.mimeForName
import com.example.comic.util.naturalCompare
import com.example.comic.util.titleWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.TimeUnit

class KooboneSource(
    private val context: Context,
    client: OkHttpClient,
    override val config: SourceConfig,
    private val downloadStore: DownloadStore,
) : DownloadableSource {
    private val base: HttpUrl = normalizeBaseUrl(
        config.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL,
    )
    private val origin: String = buildString {
        append(base.scheme).append("://").append(base.host)
        if (base.port != HttpUrl.defaultPort(base.scheme)) append(':').append(base.port)
    }
    private val json = Json { ignoreUnknownKeys = true }
    private val cookieJar = MemoryCookieJar()
    private val client = client.newBuilder()
        .cookieJar(cookieJar)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val authMutex = Mutex()
    private var session: Session? = null
    private val volumes = mutableMapOf<String, KooboneVolume>()
    private val seriesVolumes = mutableMapOf<String, List<KooboneVolume>>()
    private val volumeSeriesKeys = mutableMapOf<String, String>()

    override suspend fun testConnection() {
        withContext(Dispatchers.IO) {
            session = null
            val uin = login().uin
            val page = fetchVolumePage(uin, 1)
            if (page.uin <= 0) throw SourceException("Koobone 会话无效，请检查账号")
        }
    }

    override suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        listOf(Library(id = LIBRARY_ID, sourceId = id, name = config.name))
    }

    override suspend fun series(libraryId: String): List<Series> = withContext(Dispatchers.IO) {
        val items = fetchAllVolumes()
        cacheVolumes(items)
        groupVolumes(items).map { (seriesId, volumes) ->
            Series(
                id = seriesId,
                sourceId = id,
                libraryId = LIBRARY_ID,
                title = volumes.seriesTitle(),
                bookCount = volumes.size,
                description = volumes.firstNotNullOfOrNull {
                    it.volAuthor.takeIf(String::isNotBlank)
                },
            )
        }
    }

    override suspend fun books(seriesId: String): List<Book> = withContext(Dispatchers.IO) {
        seriesVolumesFor(seriesId)
            .sortedWith { a, b -> compareTitles(a.sortKey(), b.sortKey()) }
            .map { volume ->
                Book(
                    id = volume.fileMd5,
                    sourceId = id,
                    seriesId = seriesId,
                    title = volume.displayTitle(),
                    number = volume.volSNumber.takeIf { it.isNotBlank() },
                )
            }
    }

    override suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        val volume = volumeFor(book.id)
        val entries = withEpubZip(volume) { zip -> pagesFromZip(zip) }
        entries.mapIndexed { index, entry ->
            Page(
                sourceId = id,
                bookId = book.id,
                index = index,
                key = entry,
                mimeType = mimeForName(entry),
            )
        }
    }

    override suspend fun openPage(page: Page): InputStream = withContext(Dispatchers.IO) {
        val volume = volumeFor(page.bookId)
        val bytes = withEpubZip(volume) { zip ->
            val entry = zip.getEntry(page.key)
                ?: throw SourceException("EPUB 中找不到页面：${page.key}")
            zip.getInputStream(entry).use { it.readBytes() }
        }
        ByteArrayInputStream(bytes)
    }

    private fun <T> withEpubZip(volume: KooboneVolume, block: (ZipFile) -> T): T {
        val record = downloadStore.find(id, volume.fileMd5)
        val contentUri = record?.contentUri
        if (contentUri != null) {
            val descriptor = context.contentResolver.openFileDescriptor(
                Uri.parse(contentUri),
                "r",
            ) ?: throw SourceException("无法打开已下载的 EPUB")
            descriptor.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { input ->
                    ZipFile(input.channel).use { zip -> return block(zip) }
                }
            }
        }
        return ZipFile(epubFile(volume)).use { zip -> block(zip) }
    }

    override suspend fun openCover(series: Series): InputStream? = withContext(Dispatchers.IO) {
        val localCover = downloadStore.items.value.firstOrNull {
            it.sourceId == id &&
                it.seriesId == series.id &&
                !it.coverPath.isNullOrBlank()
        }?.coverPath
            ?: downloadStore.find(id, series.id)?.coverPath
        localCover?.let { path ->
            val file = File(path)
            if (file.length() > 0) return@withContext file.inputStream()
        }
        val volume = runCatching { coverVolumeFor(series.id) }.getOrNull()
            ?: return@withContext null
        val raw = volume.coverUrl.takeIf { it.isNotBlank() } ?: volume.coverSmall
        if (raw.isBlank()) return@withContext null
        val url = resolveUrl(raw)
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", base.toString())
            .get()
            .build()
        val response = client.execute(request)
        if (!response.isSuccessful) {
            response.close()
            return@withContext null
        }
        response.body?.byteStream() ?: run {
            response.close()
            null
        }
    }

    override suspend fun downloadBook(
        book: Book,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val volume = volumeFor(book.id)
        if (volume.fileUrl.isBlank()) {
            throw SourceException("下载地址缺失，请刷新书架后重试")
        }
        targetFile.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(resolveUrl(volume.fileUrl))
            .header("User-Agent", USER_AGENT)
            .header("Referer", base.toString())
            .get()
            .build()
        client.execute(request).use { response ->
            if (!response.isSuccessful) {
                throw SourceException("下载 EPUB 失败：HTTP ${response.code}")
            }
            val body = response.body ?: throw SourceException("EPUB 内容为空")
            val total = body.contentLength().takeIf { it > 0 } ?: 0
            var downloaded = 0L
            var lastUpdate = 0L
            targetFile.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 300) {
                            onProgress(downloaded, total)
                            lastUpdate = now
                        }
                    }
                }
            }
            onProgress(downloaded, total)
        }
        val size = targetFile.length()
        if (size <= 0) throw SourceException("EPUB 内容为空")
        val pageCount = runCatching { epubPages(targetFile).size }.getOrDefault(0)
        DownloadResult(sizeBytes = size, pageCount = pageCount)
    }

    override suspend fun downloadCover(book: Book, targetFile: File): Boolean =
        withContext(Dispatchers.IO) {
            val volume = volumeFor(book.id)
            val raw = volume.coverUrl.takeIf { it.isNotBlank() } ?: volume.coverSmall
            if (raw.isBlank()) return@withContext false
            val request = Request.Builder()
                .url(resolveUrl(raw))
                .header("User-Agent", USER_AGENT)
                .header("Referer", base.toString())
                .get()
                .build()
            client.execute(request).use { response ->
                if (!response.isSuccessful) return@withContext false
                val body = response.body ?: return@withContext false
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            targetFile.length() > 0
        }

    override fun resolveSeriesId(bookId: String): String? =
        synchronized(volumes) { volumeSeriesKeys[bookId] }

    override fun close() {
        session = null
    }

    private suspend fun login(): Session = withContext(Dispatchers.IO) {
        session?.let { return@withContext it }
        authMutex.withLock {
            session?.let { return@withLock it }
            val email = config.username?.trim().orEmpty()
            val password = config.password.orEmpty()
            if (email.isBlank() || password.isBlank()) {
                throw SourceException("请填写 Koobone 邮箱和密码")
            }
            val form = FormBody.Builder()
                .add("email", email)
                .add("passwd", password)
                .add("keepalive", "1")
                .build()
            val request = Request.Builder()
                .url(url("/login_do.php"))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", url("/login.php").toString())
                .header("Origin", origin)
                .post(form)
                .build()
            val body = client.execute(request).use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw SourceException("Koobone 登录失败：HTTP ${response.code}")
                }
                text
            }
            val dto = decode<LoginResponse>(body, "登录")
            if (dto.uin <= 0) {
                throw SourceException("Koobone 登录失败：${dto.msg.ifBlank { "账号或密码错误" }}")
            }
            Session(uin = dto.uin).also { session = it }
        }
    }

    private suspend fun fetchVolumePage(uin: Int, page: Int): VolListResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(
                    url(
                        "/vol_list.php",
                        mapOf("u" to uin.toString(), "p" to page.toString()),
                    ),
                )
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .header("Referer", base.toString())
                .get()
                .build()
            val body = client.execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw SourceException("Koobone 返回 HTTP ${response.code}")
                }
                response.body?.string() ?: throw SourceException("Koobone 返回内容为空")
            }
            decode<VolListResponse>(body, "书架")
        }

    private suspend fun fetchAllVolumes(): List<KooboneVolume> = withContext(Dispatchers.IO) {
        val uin = login().uin
        val result = mutableListOf<KooboneVolume>()
        var page = 1
        while (true) {
            val dto = fetchVolumePage(uin, page)
            if (dto.uin <= 0) {
                session = null
                throw SourceException("Koobone 会话已失效，请重新测试连接")
            }
            result += dto.data.filter { it.fileMd5.isNotBlank() && it.fileUrl.isNotBlank() }
            if (page >= dto.totalpage || dto.data.isEmpty()) break
            page++
        }
        result
    }

    private fun cacheVolumes(items: List<KooboneVolume>) {
        synchronized(volumes) {
            items.forEach { volumes[it.fileMd5] = it }
            seriesVolumes.clear()
            volumeSeriesKeys.clear()
            groupVolumes(items).forEach { (key, volumes) ->
                seriesVolumes[key] = volumes
                volumes.forEach { volumeSeriesKeys[it.fileMd5] = key }
            }
        }
    }

    private suspend fun seriesVolumesFor(seriesId: String): List<KooboneVolume> {
        synchronized(volumes) { seriesVolumes[seriesId] }?.let { return it }
        synchronized(volumes) { volumes[seriesId] }?.let { return listOf(it) }
        val items = fetchAllVolumes()
        cacheVolumes(items)
        synchronized(volumes) { seriesVolumes[seriesId] }?.let { return it }
        synchronized(volumes) { volumes[seriesId] }?.let { return listOf(it) }
        throw SourceException("找不到这个系列，可能已被移除")
    }

    private suspend fun coverVolumeFor(seriesId: String): KooboneVolume {
        val volumes = seriesVolumesFor(seriesId)
        return volumes.firstOrNull {
            it.coverUrl.isNotBlank() || it.coverSmall.isNotBlank()
        } ?: volumes.first()
    }

    /**
     * 真实站点里同一作品的部分卷可能缺少 vol_seriesid。
     * 先按 seriesid 分组，再把缺 id 但标题唯一的卷并入对应组，避免同一系列被拆开。
     */
    private fun groupVolumes(items: List<KooboneVolume>): Map<String, List<KooboneVolume>> {
        val groups = LinkedHashMap<String, MutableList<KooboneVolume>>()
        val idsByTitle = mutableMapOf<String, MutableSet<String>>()

        items.filter { it.volSeriesId.isNotBlank() }.forEach { volume ->
            val key = volume.volSeriesId.trim()
            groups.getOrPut(key) { mutableListOf() }.add(volume)
            val title = volume.volSeries.trim().lowercase()
            if (title.isNotEmpty()) {
                idsByTitle.getOrPut(title) { mutableSetOf() }.add(key)
            }
        }

        items.filter { it.volSeriesId.isBlank() }.forEach { volume ->
            val title = volume.volSeries.trim()
            val normalized = title.lowercase()
            val candidates = idsByTitle[normalized].orEmpty()
            val key = when {
                candidates.size == 1 -> candidates.first()
                title.isNotEmpty() -> title
                else -> volume.fileMd5
            }
            groups.getOrPut(key) { mutableListOf() }.add(volume)
        }

        return groups.mapValues { (_, volumes) ->
            volumes.sortedWith { a, b -> compareTitles(a.sortKey(), b.sortKey()) }
        }
    }

    private fun List<KooboneVolume>.seriesTitle(): String {
        val first = first()
        return first.volSeries.ifBlank { first.displayTitle() }
    }

    private fun KooboneVolume.sortKey(): String =
        volSNumber.ifBlank { displayTitle() }

    private suspend fun volumeFor(volumeId: String): KooboneVolume {
        synchronized(volumes) { volumes[volumeId] }?.let { return it }
        downloadStore.find(id, volumeId)?.let { record ->
            return KooboneVolume(
                volName = record.title,
                volAuthor = record.author.orEmpty(),
                fileMd5 = record.bookId,
            )
        }
        val items = fetchAllVolumes()
        cacheVolumes(items)
        return synchronized(volumes) { volumes[volumeId] }
            ?: throw SourceException("找不到这本漫画，可能已被移除")
    }

    private fun epubFile(volume: KooboneVolume): File {
        downloadStore.find(id, volume.fileMd5)?.let { record ->
            val saved = File(record.filePath)
            if (saved.length() > 0) return saved
        }
        val dir = File(context.cacheDir, "koobone").apply { mkdirs() }
        val target = File(dir, "${volume.fileMd5}.epub")
        if (target.length() > 0) return target

        val temp = File(dir, "${volume.fileMd5}.part")
        val request = Request.Builder()
            .url(resolveUrl(volume.fileUrl))
            .header("User-Agent", USER_AGENT)
            .header("Referer", base.toString())
            .get()
            .build()
        try {
            client.execute(request).use { response ->
                if (!response.isSuccessful) {
                    throw SourceException("下载 EPUB 失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw SourceException("EPUB 内容为空")
                temp.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            if (temp.length() <= 0) throw SourceException("EPUB 内容为空")
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return target
        } catch (e: Exception) {
            temp.delete()
            throw if (e is SourceException) e else SourceException("下载 EPUB 失败：${e.message}", e)
        }
    }

    private fun epubPages(file: File): List<String> = try {
        ZipFile(file).use { zip -> pagesFromZip(zip) }
    } catch (e: SourceException) {
        throw e
    } catch (e: Exception) {
        throw SourceException("无法解析 EPUB：${e.message}", e)
    }

    private fun pagesFromZip(zip: ZipFile): List<String> {
        val pages = try {
            readSpinePages(zip)
        } catch (e: Exception) {
            throw SourceException("无法解析 EPUB：${e.message}", e)
        }
        if (pages.isEmpty()) throw SourceException("EPUB 中没有可显示的图片")
        return pages
    }

    private fun readSpinePages(zip: ZipFile): List<String> {
        val container = zip.readText("META-INF/container.xml") ?: return fallbackPages(zip)
        val rootfile = Regex("<(?:[\\w-]+:)?rootfile\\b[^>]*>", RegexOption.IGNORE_CASE)
            .find(container)?.value ?: return fallbackPages(zip)
        val opfPath = attr(rootfile, "full-path") ?: return fallbackPages(zip)
        val opf = zip.readText(resolveEntry(zip, opfPath)) ?: return fallbackPages(zip)

        val manifest = mutableMapOf<String, ManifestItem>()
        Regex("<(?:[\\w-]+:)?item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val tag = match.value
            val itemId = attr(tag, "id") ?: return@forEach
            val href = attr(tag, "href") ?: return@forEach
            manifest[itemId] = ManifestItem(
                href = href,
                mediaType = attr(tag, "media-type").orEmpty(),
            )
        }

        val result = mutableListOf<String>()
        Regex("<(?:[\\w-]+:)?itemref\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).forEach { match ->
            val idref = attr(match.value, "idref") ?: return@forEach
            val item = manifest[idref] ?: return@forEach
            val pagePath = resolveZipPath(opfPath, item.href)
            if (item.mediaType.startsWith("image/", ignoreCase = true)) {
                resolveEntry(zip, pagePath)?.let(result::add)
                return@forEach
            }
            val html = zip.readText(resolveEntry(zip, pagePath)) ?: return@forEach
            imageRefs(html).forEach { ref ->
                val imagePath = resolveZipPath(pagePath, ref)
                resolveEntry(zip, imagePath)?.let(result::add)
            }
        }

        val ordered = result.distinct()
        return if (ordered.isEmpty()) fallbackPages(zip) else ordered
    }

    private fun fallbackPages(zip: ZipFile): List<String> =
        Collections.list(zip.entries)
            .filter { !it.isDirectory && isImageName(it.name) }
            .map { it.name }
            .sortedWith { a, b -> naturalCompare(a, b) }

    private fun imageRefs(html: String): List<String> {
        val refs = mutableListOf<String>()
        Regex("<(?:[\\w-]+:)?img\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            attr(match.value, "src")?.let { refs += unescape(it) }
        }
        Regex("<(?:[\\w-]+:)?image\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val href = attr(match.value, "xlink:href") ?: attr(match.value, "href")
            href?.let { refs += unescape(it) }
        }
        return refs
    }

    private fun resolveEntry(zip: ZipFile, path: String): String? {
        val decoded = runCatching { URLDecoder.decode(path, "UTF-8") }.getOrDefault(path)
        val candidates = listOf(path, decoded, path.replace('/', '\\'), "./$path")
        return candidates.firstOrNull { zip.getEntry(it) != null }
    }

    private fun ZipFile.readText(path: String?): String? {
        val actual = path ?: return null
        return try {
            getEntry(actual)?.let { entry ->
                getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun resolveZipPath(basePath: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?').trim()
        val baseDir = basePath.substringBeforeLast('/', "")
        val joined = when {
            clean.startsWith("/") -> clean.trimStart('/')
            baseDir.isEmpty() -> clean
            else -> "$baseDir/$clean"
        }
        val parts = ArrayDeque<String>()
        joined.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun resolveUrl(raw: String): HttpUrl {
        raw.toHttpUrlOrNull()?.let { return it }
        return base.resolve(raw) ?: throw SourceException("无效的下载地址：$raw")
    }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = base.newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private inline fun <reified T> decode(body: String, what: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: Exception) {
        throw SourceException("无法解析 Koobone $what 响应：${e.message?.take(160).orEmpty()}", e)
    }

    private fun KooboneVolume.displayTitle(): String =
        volName.takeIf { it.isNotBlank() }
            ?: titleWithoutExtension(fileUrl.substringAfterLast('/'))

    private data class Session(val uin: Int)

    private data class ManifestItem(val href: String, val mediaType: String)

    private companion object {
        const val DEFAULT_BASE_URL = "https://koobone.com"
        const val LIBRARY_ID = "koobone"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        fun attr(tag: String, name: String): String? =
            Regex(
                """(?:^|\s)${Regex.escape(name)}\s*=\s*["']([^"']*)["']""",
                RegexOption.IGNORE_CASE,
            ).find(tag)?.groupValues?.get(1)

        fun unescape(value: String): String = value
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }
}

private class MemoryCookieJar : CookieJar {
    private val store = mutableMapOf<String, Cookie>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { cookie ->
                store[cookie.name + "|" + cookie.domain + "|" + cookie.path] = cookie
            }
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
        store.values.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
}

@Serializable
private data class LoginResponse(
    val ret: Int = 0,
    val msg: String = "",
    val uin: Int = 0,
    val skey: String = "",
    val device: String = "",
)

@Serializable
private data class VolListResponse(
    val uin: Int = 0,
    val total: Int = 0,
    val totalpage: Int = 0,
    val nowpage: Int = 0,
    val data: List<KooboneVolume> = emptyList(),
)

@Serializable
private data class KooboneVolume(
    @SerialName("vol_name") val volName: String = "",
    @SerialName("vol_author") val volAuthor: String = "",
    @SerialName("vol_series") val volSeries: String = "",
    @SerialName("vol_seriesid") val volSeriesId: String = "",
    @SerialName("vol_snumber") val volSNumber: String = "",
    @SerialName("file_url") val fileUrl: String = "",
    @SerialName("file_md5") val fileMd5: String = "",
    @SerialName("cover_url") val coverUrl: String = "",
    @SerialName("cover_small") val coverSmall: String = "",
    @SerialName("cover_type") val coverType: String = "",
)
