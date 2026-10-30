package com.example.comic.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.comic.model.DownloadRecord
import com.example.comic.util.mimeForName
import java.io.File

/** Opens a downloaded EPUB/PDF with an app outside Comic. */
fun openDownloadedFile(context: Context, record: DownloadRecord): Boolean {
    val uri = record.contentUri?.let(Uri::parse) ?: run {
        val file = File(record.filePath)
        if (!file.exists()) return false
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val mime = record.mimeType
        ?: mimeForName(record.filePath)
        ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
