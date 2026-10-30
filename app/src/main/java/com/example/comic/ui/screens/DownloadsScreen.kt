package com.example.comic.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comic.data.DownloadManager
import com.example.comic.model.DownloadRecord
import com.example.comic.model.SourceType
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onOpen: (DownloadRecord) -> Unit) {
    val container = LocalContainer.current
    val manager = container.downloadManager
    val downloads by manager.downloads.collectAsState()
    val active by manager.active.collectAsState()
    var pendingDelete by remember { mutableStateOf<DownloadRecord?>(null) }
    var pendingExport by remember { mutableStateOf<DownloadRecord?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri ->
        val record = pendingExport
        pendingExport = null
        if (uri == null || record == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        File(record.filePath).inputStream().use { input -> input.copyTo(output) }
                    } ?: error("无法写入所选位置")
                }
            }
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "已导出：${record.title}.epub" },
                    onFailure = { "导出失败：${it.message ?: "未知错误"}" },
                ),
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("下载", fontWeight = FontWeight.SemiBold) },
            )
        },
    ) { padding ->
        val activeItems = active.values.sortedBy { it.title }
        if (downloads.isEmpty() && activeItems.isEmpty()) {
            EmptyState(
                title = "还没有下载的内容",
                description = "漫画可在系列页下载；Z-Library 的 EPUB/PDF 可在在线书库里下载后用外部应用打开。",
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (activeItems.isNotEmpty()) {
                    item { SectionTitle("正在下载") }
                    items(activeItems, key = { "active-${it.key}" }) { item ->
                        ActiveDownloadRow(
                            item = item,
                            onRetry = { manager.retry(item) },
                            onCancel = { manager.cancel(item.key) },
                        )
                    }
                }
                if (downloads.isNotEmpty()) {
                    item { SectionTitle("已下载 ${downloads.size} 本") }
                    items(downloads, key = { "${it.sourceId}|${it.bookId}" }) { record ->
                        DownloadRow(
                            record = record,
                            onClick = { onOpen(record) },
                            onExport = {
                                pendingExport = record
                                exportLauncher.launch(exportFileName(record.title))
                            },
                            onDelete = { pendingDelete = record },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = {
                Text(
                    if (record.contentUri != null) {
                        "确定删除「${record.title}」吗？会同时删除 Download/Comic 中的文件，且不可恢复。"
                    } else {
                        "确定删除「${record.title}」的本地文件吗？不可恢复。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        manager.delete(record)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun ActiveDownloadRow(
    item: DownloadManager.ActiveDownload,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (item.error != null) Icons.Outlined.ErrorOutline else Icons.Outlined.Download,
                    contentDescription = null,
                    tint = if (item.error != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    item.seriesTitle?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Outlined.Delete, contentDescription = "取消下载")
                }
            }
            if (item.error != null) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.book != null) {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    TextButton(onClick = onCancel) { Text("移除") }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                if (item.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${formatSize(item.downloadedBytes) ?: "0 KB"} / " +
                            (formatSize(item.totalBytes) ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(
    record: DownloadRecord,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val loader = LocalContainer.current.imageLoader
    var cover by remember(record.coverPath) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(record.coverPath) {
        cover = record.coverPath?.let { runCatching { loader.loadLocalCover(it) }.getOrNull() }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(width = 56.dp, height = 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = cover
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AutoStories,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                record.seriesTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val details = buildList {
                    if (record.sourceType == SourceType.ZLIBRARY) {
                        add(
                            when (record.mimeType) {
                                "application/pdf" -> "PDF"
                                "application/epub+zip" -> "EPUB"
                                else -> "文件"
                            },
                        )
                    }
                    record.pageCount?.takeIf { it > 0 }?.let { add("$it 页") }
                    formatSize(record.sizeBytes)?.let { add(it) }
                    if (record.contentUri != null) add("Download/Comic")
                    add(DATE_FORMAT.format(Date(record.downloadedAt)))
                }.joinToString(" · ")
                Text(
                    text = details,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (record.contentUri == null) {
                IconButton(onClick = onExport) {
                    Icon(
                        imageVector = Icons.Outlined.SaveAlt,
                        contentDescription = "导出到手机",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = "删除下载",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun exportFileName(title: String): String =
    title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "comic" }.take(80) + ".epub"

private val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
