package com.example.comic.source.epub

import com.example.comic.source.SourceException
import com.example.comic.util.isImageName
import com.example.comic.util.naturalCompare
import org.apache.commons.compress.archivers.zip.ZipFile
import java.net.URLDecoder
import java.util.Collections

/** Reads the image entries that make up an EPUB, in spine order. */
object EpubArchive {

    fun pageEntries(zip: ZipFile): List<String> {
        val pages = try {
            readSpinePages(zip)
        } catch (e: SourceException) {
            throw e
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

    private fun attr(tag: String, name: String): String? =
        Regex(
            """(?:^|\s)${Regex.escape(name)}\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE,
        ).find(tag)?.groupValues?.get(1)

    private fun unescape(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")

    private data class ManifestItem(val href: String, val mediaType: String)
}
