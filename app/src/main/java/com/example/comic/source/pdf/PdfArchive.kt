package com.example.comic.source.pdf

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.example.comic.source.SourceException
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Renders PDF pages to JPEG bytes so the image-based reader can display them. */
object PdfArchive {
    const val PAGE_MAX_LONG_EDGE = 1800
    private const val COVER_MAX_LONG_EDGE = 900
    private const val MAX_SCALE = 3f
    private const val JPEG_QUALITY = 90

    fun pageCount(resolver: ContentResolver, uri: Uri): Int = withRenderer(resolver, uri) { it.pageCount }

    fun renderPage(
        resolver: ContentResolver,
        uri: Uri,
        index: Int,
        longEdge: Int = PAGE_MAX_LONG_EDGE,
    ): ByteArray = withRenderer(resolver, uri) { renderer ->
        if (index !in 0 until renderer.pageCount) {
            throw SourceException("PDF 页码超出范围")
        }
        renderer.openPage(index).use { page ->
            val scale = (longEdge.toFloat() / max(page.width, page.height)).coerceIn(0.1f, MAX_SCALE)
            val width = (page.width * scale).roundToInt().coerceAtLeast(1)
            val height = (page.height * scale).roundToInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).drawColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun renderCover(resolver: ContentResolver, uri: Uri): ByteArray =
        renderPage(resolver, uri, 0, COVER_MAX_LONG_EDGE)

    private fun <T> withRenderer(
        resolver: ContentResolver,
        uri: Uri,
        block: (PdfRenderer) -> T,
    ): T {
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw SourceException("无法打开 PDF 文件")
        return descriptor.use { pfd ->
            val renderer = try {
                PdfRenderer(pfd)
            } catch (e: Exception) {
                throw SourceException("该 PDF 无法在应用内打开：${e.message?.take(80) ?: "文件不可随机读取"}")
            }
            try {
                block(renderer)
            } finally {
                renderer.close()
            }
        }
    }
}
