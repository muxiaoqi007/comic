package com.example.comic.source.local

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.source.ComicSource
import com.example.comic.source.SourceException
import com.example.comic.source.epub.EpubArchive
import com.example.comic.source.pdf.PdfArchive
import com.example.comic.util.isArchiveName
import com.example.comic.util.isEpubName
import com.example.comic.util.isImageName
import com.example.comic.util.isPdfName
import com.example.comic.util.mimeForName
import com.example.comic.util.naturalCompare
import com.example.comic.util.titleWithoutExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.util.Collections

class LocalSource(
    private val context: Context,
    override val config: SourceConfig,
) : ComicSource {
    private val resolver = context.contentResolver
    private val root: DocumentFile? = config.treeUri
        ?.let { Uri.parse(it) }
        ?.let { runCatching { DocumentFile.fromTreeUri(context, it) }.getOrNull() }
    private val resolved = mutableMapOf<String, DocumentFile>()

    override suspend fun testConnection() {
        withContext(Dispatchers.IO) {
            val root = requireRoot()
            if (!root.isDirectory || !root.canRead()) {
                throw SourceException("无法读取该文件夹")
            }
            root.listFiles()
        }
    }

    override suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        listOf(Library(id = ROOT_LIBRARY_ID, sourceId = id, name = config.name))
    }

    override suspend fun series(libraryId: String): List<Series> = withContext(Dispatchers.IO) {
        val root = requireRoot()
        val children = root.listFiles()
            .filterNot { isHidden(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name.orEmpty(), b.name.orEmpty()) }

        val result = children
            .filter { it.isDirectory }
            .map { directory ->
                Series(
                    id = directory.uri.toString(),
                    sourceId = id,
                    libraryId = libraryId,
                    title = directory.name ?: "未命名",
                    bookCount = countBooks(directory),
                )
            }
            .toMutableList()

        children
            .filter { it.isFile && isBookName(it.name) }
            .forEach { file ->
                result += Series(
                    id = file.uri.toString(),
                    sourceId = id,
                    libraryId = libraryId,
                    title = file.name
                        ?.let(::titleWithoutExtension)
                        ?.ifBlank { "未命名" }
                        ?: "未命名",
                    bookCount = 1,
                )
            }
        result
    }

    override suspend fun books(seriesId: String): List<Book> = withContext(Dispatchers.IO) {
        if (seriesId == SINGLES_ID) {
            return@withContext requireRoot().listFiles()
                .filter { it.isFile && isBookName(it.name) }
                .sortedWith { a, b -> naturalCompare(a.name.orEmpty(), b.name.orEmpty()) }
                .map { archive -> archive.toArchiveBook(seriesId) }
        }

        val directory = resolve(seriesId)
        if (directory.isFile && isBookName(directory.name)) {
            return@withContext listOf(directory.toArchiveBook(seriesId))
        }
        if (!directory.isDirectory) {
            throw SourceException("系列目录不存在")
        }

        val children = directory.listFiles()
        val books = mutableListOf<Book>()

        val directImages = children.filter { it.isFile && isImageName(it.name) }
        if (directImages.isNotEmpty()) {
            books += Book(
                id = directory.uri.toString(),
                sourceId = id,
                seriesId = seriesId,
                title = directory.name ?: "未命名",
                pageCount = directImages.size,
            )
        }

        children
            .filter { it.isFile && isBookName(it.name) }
            .forEach { archive -> books += archive.toArchiveBook(seriesId) }

        children
            .filter { it.isDirectory && it.listFiles().any { file -> file.isFile && isImageName(file.name) } }
            .forEach { folder ->
                books += Book(
                    id = folder.uri.toString(),
                    sourceId = id,
                    seriesId = seriesId,
                    title = folder.name ?: "未命名",
                    pageCount = folder.listFiles().count { it.isFile && isImageName(it.name) },
                )
            }

        books.sortedWith { a, b -> naturalCompare(a.title, b.title) }
    }

    override suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        val document = resolve(book.id)
        when {
            document.isDirectory -> {
                val images = document.listFiles()
                    .filter { it.isFile && isImageName(it.name) }
                    .sortedWith { a, b -> naturalCompare(a.name.orEmpty(), b.name.orEmpty()) }
                images.mapIndexed { index, image ->
                    Page(
                        sourceId = id,
                        bookId = book.id,
                        index = index,
                        key = image.uri.toString(),
                        mimeType = mimeForName(image.name.orEmpty()),
                    )
                }
            }

            document.isFile && isPdfName(document.name) -> {
                val count = PdfArchive.pageCount(resolver, document.uri)
                (0 until count).map { index ->
                    Page(
                        sourceId = id,
                        bookId = book.id,
                        index = index,
                        key = "$PDF_PAGE_PREFIX$index",
                        mimeType = "image/jpeg",
                    )
                }
            }

            document.isFile && isBookName(document.name) -> {
                val entries = if (isEpubName(document.name)) {
                    withZip(document.uri) { zip -> EpubArchive.pageEntries(zip) }
                } else {
                    listZipImages(document.uri).map { it.name }
                }
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

            else -> throw SourceException("不支持的漫画格式")
        }
    }

    override suspend fun openPage(page: Page): InputStream = withContext(Dispatchers.IO) {
        if (page.key.startsWith(PDF_PAGE_PREFIX)) {
            val index = page.key.removePrefix(PDF_PAGE_PREFIX).toIntOrNull()
                ?: throw SourceException("无效的 PDF 页码")
            return@withContext ByteArrayInputStream(
                PdfArchive.renderPage(resolver, Uri.parse(page.bookId), index),
            )
        }
        if (page.key.startsWith("content://")) {
            return@withContext resolver.openInputStream(Uri.parse(page.key))
                ?: throw SourceException("无法打开页面")
        }
        val bytes = readZipEntry(Uri.parse(page.bookId), page.key)
        ByteArrayInputStream(bytes)
    }

    override suspend fun openCover(series: Series): InputStream? = withContext(Dispatchers.IO) {
        val firstBook = books(series.id).firstOrNull() ?: return@withContext null
        val document = runCatching { resolve(firstBook.id) }.getOrNull()
        if (document != null && document.isFile && isPdfName(document.name)) {
            return@withContext ByteArrayInputStream(PdfArchive.renderCover(resolver, document.uri))
        }
        val firstPage = pages(firstBook).firstOrNull() ?: return@withContext null
        openPage(firstPage)
    }

    private fun requireRoot(): DocumentFile =
        root ?: throw SourceException("请选择本地漫画文件夹")

    private fun resolve(uriString: String): DocumentFile {
        resolved[uriString]?.let { return it }
        val target = Uri.parse(uriString)
        val root = requireRoot()
        if (root.uri == target) {
            resolved[uriString] = root
            return root
        }
        val found = findDocument(root, target, 0)
            ?: throw SourceException("找不到文件：${target.lastPathSegment}")
        resolved[uriString] = found
        return found
    }

    private fun findDocument(directory: DocumentFile, target: Uri, depth: Int): DocumentFile? {
        if (depth > 8) return null
        val children = runCatching { directory.listFiles() }.getOrElse { return null }
        children.forEach { child ->
            if (child.uri == target) return child
            if (child.isDirectory) {
                findDocument(child, target, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun countBooks(directory: DocumentFile): Int {
        val children = directory.listFiles()
        val directImages = children.any { it.isFile && isImageName(it.name) }
        val files = children.count { it.isFile && isBookName(it.name) }
        val imageFolders = children.count { child ->
            child.isDirectory && child.listFiles().any { it.isFile && isImageName(it.name) }
        }
        return files + imageFolders + if (directImages) 1 else 0
    }

    private fun isBookName(name: String?): Boolean =
        isArchiveName(name) || isEpubName(name) || isPdfName(name)

    private fun DocumentFile.toArchiveBook(seriesId: String): Book = Book(
        id = uri.toString(),
        sourceId = id,
        seriesId = seriesId,
        title = titleWithoutExtension(name.orEmpty()),
        pageCount = if (isPdfName(name)) {
            runCatching { PdfArchive.pageCount(resolver, uri) }.getOrNull()
        } else {
            null
        },
        size = length(),
        updatedAt = lastModified().takeIf { it > 0 },
    )

    private fun <T> withZip(uri: Uri, block: (ZipFile) -> T): T {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw SourceException("无法打开漫画文件")
        return descriptor.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { input ->
                ZipFile(input.channel).use(block)
            }
        }
    }

    private fun listZipImages(uri: Uri): List<ZipArchiveEntry> = withZip(uri) { zip ->
        Collections.list(zip.entries)
            .filter { !it.isDirectory && isImageName(it.name) }
            .sortedWith { a, b -> naturalCompare(a.name, b.name) }
    }

    private fun readZipEntry(uri: Uri, entryName: String): ByteArray = withZip(uri) { zip ->
        val entry = zip.getEntry(entryName)
            ?: throw SourceException("压缩包中找不到页面：$entryName")
        zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun isHidden(name: String?): Boolean = name.isNullOrBlank() || name.startsWith(".")

    private companion object {
        const val ROOT_LIBRARY_ID = "root"
        const val SINGLES_ID = "local:singles"
        const val PDF_PAGE_PREFIX = "pdf:"
    }
}
