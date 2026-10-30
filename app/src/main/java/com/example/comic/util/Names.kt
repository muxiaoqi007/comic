package com.example.comic.util

import java.text.Collator
import java.util.Locale

private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

fun isImageName(name: String?): Boolean {
    val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return extension in IMAGE_EXTENSIONS
}

fun isArchiveName(name: String?): Boolean {
    val extension = name?.substringAfterLast('.', "")?.lowercase() ?: return false
    return extension == "cbz" || extension == "zip"
}

fun isEpubName(name: String?): Boolean =
    name?.substringAfterLast('.', "")?.lowercase() == "epub"

fun isPdfName(name: String?): Boolean =
    name?.substringAfterLast('.', "")?.lowercase() == "pdf"

fun mimeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "bmp" -> "image/bmp"
    "avif" -> "image/avif"
    "pdf" -> "application/pdf"
    else -> null
}

fun titleWithoutExtension(name: String): String =
    name.substringBeforeLast('.', name).ifBlank { name }

fun naturalCompare(left: String, right: String): Int {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a.isDigit() && b.isDigit()) {
            var endA = i
            while (endA < left.length && left[endA].isDigit()) endA++
            var endB = j
            while (endB < right.length && right[endB].isDigit()) endB++
            val numberA = left.substring(i, endA).trimStart('0')
            val numberB = right.substring(j, endB).trimStart('0')
            val comparison = numberA.length.compareTo(numberB.length)
                .takeIf { it != 0 }
                ?: numberA.compareTo(numberB)
            if (comparison != 0) return comparison
            i = endA
            j = endB
        } else {
            val comparison = a.lowercaseChar().compareTo(b.lowercaseChar())
            if (comparison != 0) return comparison
            i++
            j++
        }
    }
    return (left.length - i).compareTo(right.length - j)
}

private val titleCollator: Collator = Collator.getInstance(Locale.SIMPLIFIED_CHINESE).apply {
    strength = Collator.PRIMARY
}

/**
 * 按标题排序：中文按拼音，数字段按数值，避免 Vol 10 排在 Vol 2 前面。
 */
fun compareTitles(left: String, right: String): Int = synchronized(titleCollator) {
    var i = 0
    var j = 0
    while (i < left.length && j < right.length) {
        val a = left[i]
        val b = right[j]
        if (a.isDigit() && b.isDigit()) {
            var endA = i
            while (endA < left.length && left[endA].isDigit()) endA++
            var endB = j
            while (endB < right.length && right[endB].isDigit()) endB++
            val numberA = left.substring(i, endA).trimStart('0')
            val numberB = right.substring(j, endB).trimStart('0')
            val comparison = numberA.length.compareTo(numberB.length)
                .takeIf { it != 0 }
                ?: numberA.compareTo(numberB)
            if (comparison != 0) return@synchronized comparison
            i = endA
            j = endB
        } else if (a.isDigit() != b.isDigit()) {
            val comparison = titleCollator.compare(a.toString(), b.toString())
            if (comparison != 0) return@synchronized comparison
            i++
            j++
        } else {
            var endA = i
            while (endA < left.length && !left[endA].isDigit()) endA++
            var endB = j
            while (endB < right.length && !right[endB].isDigit()) endB++
            val comparison = titleCollator.compare(
                left.substring(i, endA),
                right.substring(j, endB),
            )
            if (comparison != 0) return@synchronized comparison
            i = endA
            j = endB
        }
    }
    (left.length - i).compareTo(right.length - j)
}
