package com.example.comic.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.example.comic.model.Page
import com.example.comic.model.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ImageLoader(
    private val repository: SourceRepository,
) {
    private val pageCache = BitmapCache(memoryBudget(1f / 3f))
    private val coverCache = BitmapCache(memoryBudget(1f / 12f))

    suspend fun loadPage(page: Page): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${page.sourceId}|${page.bookId}|${page.key}"
        pageCache.get(key)?.let { return@withContext it }
        val bytes = runCatching { repository.openPageBytes(page) }.getOrNull()
            ?: return@withContext null
        val bitmap = decode(bytes, maxDimension = PAGE_MAX_DIMENSION) ?: return@withContext null
        pageCache.put(key, bitmap)
        bitmap
    }

    suspend fun loadCover(series: Series): Bitmap? = withContext(Dispatchers.IO) {
        val key = "${series.sourceId}|${series.id}"
        coverCache.get(key)?.let { return@withContext it }
        val bytes = runCatching { repository.openCoverBytes(series) }.getOrNull()
            ?: return@withContext null
        val bitmap = decode(bytes, maxDimension = COVER_MAX_DIMENSION) ?: return@withContext null
        coverCache.put(key, bitmap)
        bitmap
    }

    suspend fun loadLocalCover(path: String): Bitmap? = withContext(Dispatchers.IO) {
        val key = "file|$path"
        coverCache.get(key)?.let { return@withContext it }
        val file = File(path)
        if (!file.exists()) return@withContext null
        val bitmap = decode(file.readBytes(), maxDimension = COVER_MAX_DIMENSION)
            ?: return@withContext null
        coverCache.put(key, bitmap)
        bitmap
    }

    private fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()
    }

    private fun memoryBudget(fraction: Float): Int =
        (Runtime.getRuntime().maxMemory() * fraction).toInt().coerceAtMost(64 * 1024 * 1024)

    private class BitmapCache(maxBytes: Int) : LruCache<String, Bitmap>(maxBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private companion object {
        const val PAGE_MAX_DIMENSION = 2560
        const val COVER_MAX_DIMENSION = 640
    }
}
