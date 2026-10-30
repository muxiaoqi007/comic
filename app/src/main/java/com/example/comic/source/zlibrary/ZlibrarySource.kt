package com.example.comic.source.zlibrary

import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.source.ComicSource
import com.example.comic.source.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class ZlibraryBook(
    val id: String,
    val hash: String,
    val title: String,
    val author: String?,
    val coverUrl: String?,
    val extension: String?,
    val sizeBytes: Long?,
    val year: String?,
    val language: String?,
)

data class ZlibrarySearchPage(
    val books: List<ZlibraryBook>,
    val page: Int,
    val totalPages: Int,
)

data class ZlibraryProfile(
    val downloadsToday: Int,
    val downloadsLimit: Int?,
)

data class ZlibraryDownload(
    val fileName: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/**
 * Personal-account Z-Library client used as a download source. It only logs in
 * with the account configured by the user; it never uses bundled accounts.
 */
class ZlibrarySource(
    private val client: OkHttpClient,
    override val config: SourceConfig,
) : ComicSource {

    private data class Session(val userId: String, val userKey: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    @Volatile
    private var session: Session? = null
    private val base: HttpUrl = normalizeBase(config.baseUrl)
    private val downloadClient = client.newBuilder()
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun testConnection() {
        connect()
    }

    suspend fun connect(): ZlibraryProfile? {
        sessionMutex.withLock { session = null }
        requireSession()
        return profile()
    }

    override suspend fun libraries(): List<Library> = emptyList()

    override suspend fun series(libraryId: String): List<Series> = emptyList()

    override suspend fun books(seriesId: String): List<Book> = emptyList()

    override suspend fun pages(book: Book): List<Page> = emptyList()

    override suspend fun openPage(page: Page): java.io.InputStream =
        throw SourceException("Z-Library 下载源不支持应用内阅读")

    suspend fun search(
        query: String,
        page: Int = 1,
        extensions: List<String> = emptyList(),
    ): ZlibrarySearchPage = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext ZlibrarySearchPage(emptyList(), page, page)
        val active = requireSession()
        val form = FormBody.Builder()
            .add("message", term)
            .add("page", page.coerceAtLeast(1).toString())
            .add("limit", PAGE_SIZE.toString())
        extensions.filter { it.isNotBlank() }.forEach { form.add("extensions[]", it) }
        val response = request(
            url = apiUrl("/eapi/book/search"),
            method = "POST",
            body = form.build(),
            cookie = active.cookie(),
        )
        if (!response.truthy("success")) {
            throw SourceException(errorMessage(response))
        }
        val books = (response["books"] as? JsonArray)
            .orEmpty()
            .mapNotNull { item -> (item as? JsonObject)?.toBook() }
        val pagination = response["pagination"] as? JsonObject
        val current = pagination?.int("current") ?: page
        val totalPages = pagination?.int("total_pages") ?: current
        ZlibrarySearchPage(
            books = books,
            page = current,
            totalPages = totalPages.coerceAtLeast(current),
        )
    }

    suspend fun profile(): ZlibraryProfile? = withContext(Dispatchers.IO) {
        val active = requireSession()
        val response = request(
            url = apiUrl("/eapi/user/profile"),
            method = "GET",
            body = null,
            cookie = active.cookie(),
        )
        if (!response.truthy("success")) return@withContext null
        val user = response["user"] as? JsonObject ?: return@withContext null
        ZlibraryProfile(
            downloadsToday = user.int("downloads_today"),
            downloadsLimit = (user["downloads_limit"] as? JsonPrimitive)?.intOrNull,
        )
    }

    suspend fun coverBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", base.toString())
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        }.getOrNull()
    }

    suspend fun download(
        book: ZlibraryBook,
        targetFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): ZlibraryDownload = withContext(Dispatchers.IO) {
        val active = requireSession()
        val linkUrl = apiUrl("/eapi/book")
            .newBuilder()
            .addPathSegment(book.id)
            .addPathSegment(book.hash)
            .addPathSegment("file")
            .build()
        val response = request(
            url = linkUrl,
            method = "GET",
            body = null,
            cookie = active.cookie(),
        )
        if (!response.truthy("success")) {
            throw SourceException(errorMessage(response))
        }
        val file = response["file"] as? JsonObject
            ?: throw SourceException("Z-Library 没有返回下载信息")
        if (!file.truthy("allowDownload")) {
            val reason = file.string("disallowDownloadMessage")
            if (reason.contains("limit", ignoreCase = true)) {
                throw SourceException("Z-Library 今日下载额度已用完")
            }
            throw SourceException(reason.ifBlank { "Z-Library 暂不允许下载这本书" })
        }
        val downloadUrl = file.string("downloadLink")
            .ifBlank { throw SourceException("Z-Library 没有返回下载地址") }
        val extension = file.string("extension")
            .ifBlank { book.extension.orEmpty() }
            .ifBlank { "epub" }
            .lowercase()
        val description = file.string("description").ifBlank { book.title }
        val author = file.string("author").ifBlank { book.author.orEmpty() }
        val fileName = buildFileName(description, author, extension)

        targetFile.parentFile?.mkdirs()
        targetFile.delete()
        try {
            val request = Request.Builder()
                .url(downloadUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", base.toString())
                .get()
                .build()
            downloadClient.newCall(request).execute().use { downloadResponse ->
                if (!downloadResponse.isSuccessful) {
                    throw SourceException("Z-Library 下载失败：HTTP ${downloadResponse.code}")
                }
                val body = downloadResponse.body ?: throw SourceException("Z-Library 下载内容为空")
                val total = body.contentLength().takeIf { it > 0 } ?: 0L
                var downloaded = 0L
                var lastUpdate = 0L
                body.byteStream().use { input ->
                    targetFile.outputStream().use { output ->
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
        } catch (e: SourceException) {
            targetFile.delete()
            throw e
        } catch (e: Exception) {
            targetFile.delete()
            throw SourceException("Z-Library 下载失败：${e.message?.take(100)}", e)
        }
        val size = targetFile.length()
        if (size <= 0) throw SourceException("Z-Library 下载内容为空")
        ZlibraryDownload(
            fileName = fileName,
            extension = extension,
            mimeType = mimeTypeFor(extension),
            sizeBytes = size,
        )
    }

    override fun close() {
        session = null
    }

    private suspend fun requireSession(): Session = sessionMutex.withLock {
        session ?: login().also { session = it }
    }

    private suspend fun login(): Session {
        val email = config.username?.trim().orEmpty()
        val password = config.password.orEmpty()
        if (email.isBlank() || password.isBlank()) {
            throw SourceException("请填写 Z-Library 邮箱和密码")
        }
        val form = FormBody.Builder()
            .add("email", email)
            .add("password", password)
            .build()
        val response = request(
            url = apiUrl("/eapi/user/login"),
            method = "POST",
            body = form,
            cookie = null,
        )
        if (!response.truthy("success")) {
            throw SourceException(
                response.string("error").ifBlank { "Z-Library 登录失败，请检查邮箱和密码" },
            )
        }
        val user = response["user"] as? JsonObject
            ?: throw SourceException("Z-Library 登录响应缺少账号信息")
        val userId = user.string("id")
        val userKey = user.string("remix_userkey")
        if (userId.isBlank() || userKey.isBlank()) {
            throw SourceException("Z-Library 登录响应缺少凭据")
        }
        return Session(userId, userKey)
    }

    private suspend fun request(
        url: HttpUrl,
        method: String,
        body: okhttp3.RequestBody?,
        cookie: String?,
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (cookie != null) builder.header("Cookie", cookie)
        if (method == "POST") {
            builder.post(body ?: FormBody.Builder().build())
        } else {
            builder.get()
        }
        try {
            client.newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    session = null
                }
                if (!response.isSuccessful) {
                    throw SourceException(
                        when (response.code) {
                            401, 403 -> "Z-Library 登录已失效或访问被拒绝"
                            429 -> "Z-Library 请求过于频繁，请稍后再试"
                            else -> "Z-Library 请求失败：HTTP ${response.code}"
                        },
                    )
                }
                val parsed = runCatching { json.parseToJsonElement(text) }.getOrNull()
                parsed as? JsonObject
                    ?: throw SourceException("Z-Library 返回格式异常，域名可能已变更")
            }
        } catch (e: SourceException) {
            throw e
        } catch (e: Exception) {
            throw SourceException("无法连接 Z-Library：${e.message?.take(100)}", e)
        }
    }

    private fun apiUrl(path: String): HttpUrl {
        val clean = path.trim().trimStart('/')
        return base.newBuilder().addPathSegments(clean).build()
    }

    private fun JsonObject.toBook(): ZlibraryBook? {
        val id = string("id")
        val hash = string("hash")
        val title = string("title")
        if (id.isBlank() || title.isBlank()) return null
        return ZlibraryBook(
            id = id,
            hash = hash,
            title = title,
            author = string("author").ifBlank { null },
            coverUrl = string("cover").ifBlank { null }?.let(::absoluteUrl),
            extension = string("extension").ifBlank { null },
            sizeBytes = (this["filesize"] as? JsonPrimitive)?.longOrNull,
            year = string("year").ifBlank { null },
            language = string("language").ifBlank { null },
        )
    }

    private fun absoluteUrl(value: String): String = when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://") || value.startsWith("https://") -> value
        else -> base.resolve(value)?.toString() ?: value
    }

    private fun JsonObject.truthy(key: String): Boolean {
        val primitive = this[key] as? JsonPrimitive ?: return false
        return primitive.booleanOrNull == true ||
            primitive.intOrNull == 1 ||
            primitive.contentOrNull == "1"
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int {
        val primitive = this[key] as? JsonPrimitive ?: return 0
        return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull() ?: 0
    }

    private fun errorMessage(response: JsonObject): String =
        response.string("error")
            .ifBlank { response.string("message") }
            .ifBlank { "Z-Library 请求失败，请稍后再试" }

    private fun Session.cookie(): String =
        "siteLanguageV2=en; remix_userid=$userId; remix_userkey=$userKey"

    private fun normalizeBase(value: String?): HttpUrl {
        val raw = value?.trim().orEmpty()
        val withScheme = when {
            raw.isEmpty() -> "https://zh.zlibrary.by"
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "https://$raw"
        }
        return withScheme.trimEnd('/').toHttpUrlOrNull()
            ?: throw SourceException("Z-Library 地址格式不正确")
    }

    private fun buildFileName(description: String, author: String, extension: String): String {
        val baseName = listOf(description.trim(), author.trim())
            .filter { it.isNotBlank() }
            .joinToString(" - ")
            .ifBlank { "zlibrary-book" }
        val safe = baseName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .trim()
            .take(120)
            .ifBlank { "zlibrary-book" }
        return "$safe.$extension"
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        "epub" -> "application/epub+zip"
        "pdf" -> "application/pdf"
        "mobi" -> "application/x-mobipocket-ebook"
        "azw3" -> "application/vnd.amazon.ebook"
        "fb2" -> "application/fb2"
        "djvu" -> "image/vnd.djvu"
        "cbz", "zip" -> "application/zip"
        else -> "application/octet-stream"
    }

    private companion object {
        const val PAGE_SIZE = 30
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
