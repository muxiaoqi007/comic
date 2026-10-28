package com.example.comic.source.komga

import com.example.comic.model.Book
import com.example.comic.model.Library
import com.example.comic.model.Page
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.source.ComicSource
import com.example.comic.source.SourceException
import com.example.comic.source.remote.appendPath
import com.example.comic.source.remote.authorize
import com.example.comic.source.remote.execute
import com.example.comic.source.remote.normalizeBaseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.InputStream

class KomgaSource(
    private val client: OkHttpClient,
    override val config: SourceConfig,
) : ComicSource {
    private val base = normalizeBaseUrl(config.baseUrl).appendPath("/api/v1")
    private val json = Json {
        ignoreUnknownKeys = true
    }

    override suspend fun testConnection() {
        withContext(Dispatchers.IO) {
            val libraries = decode<List<KomgaLibrary>>(request("/libraries"))
            libraries.firstOrNull()?.let { series(it.id) }
        }
    }

    override suspend fun libraries(): List<Library> = withContext(Dispatchers.IO) {
        val dto = decode<List<KomgaLibrary>>(request("/libraries"))
        dto.map { Library(id = it.id, sourceId = id, name = it.name.ifBlank { "Komga" }) }
    }

    override suspend fun series(libraryId: String): List<Series> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Series>()
        var page = 0
        while (true) {
            val dto = decode<KomgaPageResponse<KomgaSeries>>(listSeries(libraryId, page))
            result += dto.content.map { series ->
                Series(
                    id = series.id,
                    sourceId = id,
                    libraryId = libraryId,
                    title = series.metadata?.title?.takeIf { it.isNotBlank() } ?: series.name,
                    bookCount = series.booksCount,
                    description = series.metadata?.summary?.takeIf { it.isNotBlank() },
                )
            }
            if (dto.content.size < PAGE_SIZE) break
            page++
        }
        result
    }

    override suspend fun books(seriesId: String): List<Book> = withContext(Dispatchers.IO) {
        val result = mutableListOf<Book>()
        var page = 0
        while (true) {
            val dto = decode<KomgaPageResponse<KomgaBook>>(listBooks(seriesId, page))
            result += dto.content.map { book ->
                Book(
                    id = book.id,
                    sourceId = id,
                    seriesId = seriesId,
                    title = book.metadata?.title?.takeIf { it.isNotBlank() } ?: book.name,
                    number = book.number?.contentOrNull,
                    pageCount = book.media?.pagesCount,
                    size = book.sizeBytes,
                )
            }
            if (dto.content.size < PAGE_SIZE) break
            page++
        }
        result
    }

    override suspend fun pages(book: Book): List<Page> = withContext(Dispatchers.IO) {
        val dto = decode<List<KomgaPageEntry>>(request("/books/${book.id}/pages"))
        dto.sortedBy { it.number }.mapIndexed { index, page ->
            Page(
                sourceId = id,
                bookId = book.id,
                index = index,
                key = page.number.toString(),
                mimeType = page.mediaType,
            )
        }
    }

    override suspend fun openPage(page: Page): InputStream = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url("/books/${page.bookId}/pages/${page.key}")).get()
        builder.auth()
        val response = client.execute(builder.build())
        if (!response.isSuccessful) {
            response.close()
            throw SourceException("加载页面失败：HTTP ${response.code}")
        }
        response.body?.byteStream() ?: run {
            response.close()
            throw SourceException("页面内容为空")
        }
    }

    override suspend fun openCover(series: Series): InputStream? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url("/series/${series.id}/thumbnail")).get()
        builder.auth()
        val response = client.execute(builder.build())
        if (!response.isSuccessful) {
            response.close()
            return@withContext null
        }
        response.body?.byteStream() ?: run {
            response.close()
            null
        }
    }

    override suspend fun updateProgress(book: Book, page: Int, totalPages: Int, completed: Boolean) =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(
                ReadProgressUpdate(
                    page = (page + 1).coerceIn(1, totalPages.coerceAtLeast(1)),
                    completed = completed,
                ),
            )
            val builder = Request.Builder()
                .url(url("/books/${book.id}/read-progress"))
                .patch(payload.toRequestBody(JSON_MEDIA_TYPE))
            builder.auth()
            val response = client.execute(builder.build())
            response.use {
                if (!it.isSuccessful) {
                    throw SourceException("同步阅读进度失败：HTTP ${it.code}")
                }
            }
        }

    private fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = base.newBuilder().addPathSegments(path.trim('/'))
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    private fun Request.Builder.auth(): Request.Builder {
        val key = config.apiKey?.takeIf { it.isNotBlank() }
        return if (key != null) header("X-API-Key", key) else authorize(config)
    }

    /**
     * Komga 1.19 起系列/书籍列表改用 POST /series/list、/books/list，
     * 旧的 GET 接口仍可能被移除；这里新接口优先，旧接口兜底。
     */
    private fun listSeries(libraryId: String, page: Int): String {
        val payload = json.encodeToString(
            SeriesListRequest(SeriesListCondition(SearchOperatorIs(operator = "is", value = libraryId))),
        )
        return try {
            request("/series/list", pageQuery(page), "POST", payload)
        } catch (e: KomgaHttpException) {
            if (!e.isMissingEndpoint()) throw e
            request("/series", pageQuery(page) + ("library_id" to libraryId))
        }
    }

    private fun listBooks(seriesId: String, page: Int): String {
        val payload = json.encodeToString(
            BookListRequest(BookListCondition(SearchOperatorIs(operator = "is", value = seriesId))),
        )
        return try {
            request("/books/list", pageQuery(page), "POST", payload)
        } catch (e: KomgaHttpException) {
            if (!e.isMissingEndpoint()) throw e
            request("/series/$seriesId/books", pageQuery(page))
        }
    }

    private fun pageQuery(page: Int): Map<String, String> =
        mapOf("page" to page.toString(), "size" to PAGE_SIZE.toString())

    private fun request(
        path: String,
        query: Map<String, String> = emptyMap(),
        method: String = "GET",
        body: String? = null,
    ): String {
        val builder = Request.Builder()
            .url(url(path, query))
            .header("Accept", "application/json")
        if (body != null) {
            builder.method(method, body.toRequestBody(JSON_MEDIA_TYPE))
        } else {
            builder.method(method, null)
        }
        builder.auth()
        val response = client.execute(builder.build())
        response.use {
            if (!it.isSuccessful) {
                throw KomgaHttpException(it.code, "Komga 返回 HTTP ${it.code}")
            }
            return it.body?.string() ?: throw SourceException("Komga 返回内容为空")
        }
    }

    private inline fun <reified T> decode(body: String): T = try {
        json.decodeFromString<T>(body)
    } catch (e: Exception) {
        val detail = e.message?.take(160)
        throw SourceException(
            if (detail.isNullOrBlank()) "无法解析 Komga 响应" else "无法解析 Komga 响应：$detail",
            e,
        )
    }

    private companion object {
        const val PAGE_SIZE = 200
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class KomgaHttpException(
    val code: Int,
    message: String,
) : Exception(message) {
    fun isMissingEndpoint(): Boolean = code == 400 || code == 404 || code == 405
}

@Serializable
private data class SeriesListRequest(
    val condition: SeriesListCondition,
)

@Serializable
private data class SeriesListCondition(
    val libraryId: SearchOperatorIs,
)

@Serializable
private data class BookListRequest(
    val condition: BookListCondition,
)

@Serializable
private data class BookListCondition(
    val seriesId: SearchOperatorIs,
)

@Serializable
private data class SearchOperatorIs(
    val operator: String,
    val value: String,
)

@Serializable
private data class ReadProgressUpdate(
    val page: Int,
    val completed: Boolean,
)

@Serializable
private data class KomgaPageResponse<T>(
    val content: List<T> = emptyList(),
    val totalElements: Long = 0,
)

@Serializable
private data class KomgaLibrary(
    val id: String,
    val name: String = "",
    val root: String = "",
)

@Serializable
private data class KomgaSeries(
    val id: String,
    val libraryId: String = "",
    val name: String = "",
    val booksCount: Int? = null,
    val metadata: KomgaSeriesMetadata? = null,
)

@Serializable
private data class KomgaSeriesMetadata(
    val title: String? = null,
    val summary: String? = null,
)

@Serializable
private data class KomgaBook(
    val id: String,
    val seriesId: String = "",
    val name: String = "",
    val number: JsonPrimitive? = null,
    val sizeBytes: Long? = null,
    val media: KomgaMedia? = null,
    val metadata: KomgaBookMetadata? = null,
    val readProgress: KomgaReadProgress? = null,
)

@Serializable
private data class KomgaMedia(
    val pagesCount: Int = 0,
    val mediaType: String? = null,
)

@Serializable
private data class KomgaBookMetadata(
    val title: String? = null,
)

@Serializable
private data class KomgaReadProgress(
    val page: Int = 0,
    val completed: Boolean = false,
)

@Serializable
private data class KomgaPageEntry(
    val number: Int,
    val fileName: String = "",
    val mediaType: String? = null,
)
