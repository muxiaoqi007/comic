package com.example.comic.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comic.data.DownloadManager
import com.example.comic.model.Book
import com.example.comic.model.ReadingProgress
import com.example.comic.model.Series
import com.example.comic.model.storageKey
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.ErrorState
import com.example.comic.ui.components.LoadingState
import com.example.comic.ui.components.SeriesCover
import com.example.comic.ui.components.formatSize
import com.example.comic.ui.viewmodel.SeriesDetailViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(
    series: Series,
    viewModel: SeriesDetailViewModel,
    onBack: () -> Unit,
    onOpenBook: (Book, Int?) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val container = LocalContainer.current
    val progressMap by container.repository.progress.collectAsState()
    val downloadManager = container.downloadManager
    val activeDownloads by downloadManager.active.collectAsState()
    val downloadRecords by downloadManager.downloads.collectAsState()
    val downloadable = remember(series.sourceId) {
        container.repository.supportsDownload(series.sourceId)
    }
    val readCount = remember(state.books, progressMap) {
        state.books.count { progressMap[it.storageKey]?.completed == true }
    }
    val currentBook = remember(state.books, progressMap) {
        val inProgress = state.books
            .mapNotNull { book -> progressMap[book.storageKey]?.let { book to it } }
            .filterNot { it.second.completed }
            .maxByOrNull { it.second.updatedAt }
            ?.first
        inProgress
            ?: state.books.firstOrNull { progressMap[it.storageKey] == null }
            ?: state.books.firstOrNull()
    }
    val allFinished = state.books.isNotEmpty() && readCount == state.books.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = series.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> LoadingState(modifier = Modifier.padding(padding))
            state.error != null -> ErrorState(
                message = state.error ?: "加载失败",
                modifier = Modifier.padding(padding),
                onRetry = { viewModel.load() },
            )
            state.books.isEmpty() -> EmptyState(
                title = "没有找到漫画",
                description = "这个系列里没有可读取的压缩包或图片文件夹。",
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                item {
                    SeriesHeader(
                        series = series,
                        bookCount = state.books.size,
                        readCount = readCount,
                    )
                }
                currentBook?.let { book ->
                    item {
                        SeriesContinueCard(
                            book = book,
                            progress = progressMap[book.storageKey],
                            allFinished = allFinished,
                            onOpen = {
                                onOpenBook(book, if (allFinished) 0 else null)
                            },
                        )
                    }
                }
                itemsIndexed(
                    items = state.books,
                    key = { _, book -> book.id },
                ) { index, book ->
                    val activeDownload = activeDownloads[book.storageKey]
                    val downloaded = downloadRecords.any {
                        it.sourceId == book.sourceId && it.bookId == book.id
                    }
                    BookRow(
                        index = index,
                        book = book,
                        progress = progressMap[book.storageKey],
                        downloadable = downloadable,
                        downloaded = downloaded,
                        activeDownload = activeDownload,
                        onDownload = {
                            downloadManager.download(book, series.title, series.description)
                        },
                        onRestart = { onOpenBook(book, 0) },
                        onClick = { onOpenBook(book, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesContinueCard(
    book: Book,
    progress: ReadingProgress?,
    allFinished: Boolean,
    onOpen: () -> Unit,
) {
    val label = when {
        allFinished -> "已全部读完 · 从头重读"
        progress == null -> "开始阅读 · ${book.title}"
        else -> "继续阅读 · ${book.title}"
    }
    val meta = when {
        allFinished -> "共 ${progress?.totalPages ?: 0} 页"
        progress == null -> "还没有阅读记录"
        else -> "读到 ${progress.page + 1} / ${progress.totalPages} 页"
    }
    Surface(
        color = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.68f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
            )
        }
    }
}

@Composable
private fun SeriesHeader(series: Series, bookCount: Int, readCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        SeriesCover(
            series = series,
            modifier = Modifier
                .width(96.dp)
                .height(144.dp)
                .clip(RoundedCornerShape(10.dp)),
            cornerRadius = 10,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(series.title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (readCount > 0) "$bookCount 卷 · 已读 $readCount 卷" else "$bookCount 卷",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!series.description.isNullOrBlank()) {
                val description = series.description
                Spacer(Modifier.height(8.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    index: Int,
    book: Book,
    progress: ReadingProgress?,
    downloadable: Boolean,
    downloaded: Boolean,
    activeDownload: DownloadManager.ActiveDownload?,
    onDownload: () -> Unit,
    onRestart: () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(44.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = book.number?.takeIf { it.isNotBlank() } ?: (index + 1).toString(),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            val details = buildList {
                book.pageCount?.takeIf { it > 0 }?.let { add("$it 页") }
                formatSize(book.size)?.let { add(it) }
                activeDownload?.let { download ->
                    if (download.error != null) {
                        add("下载失败")
                    } else if (download.totalBytes > 0) {
                        val percent = download.downloadedBytes * 100 / download.totalBytes
                        add("下载中 $percent%")
                    } else {
                        add("下载中…")
                    }
                }
            }.joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null && progress.totalPages > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = {
                        ((progress.page + 1).toFloat() / progress.totalPages).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(0.7f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = if (progress.completed) {
                        "已读完"
                    } else {
                        "读到 ${progress.page + 1}/${progress.totalPages} 页"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (progress?.completed == true) {
            TextButton(onClick = onRestart) {
                Text("重读", style = MaterialTheme.typography.labelSmall)
            }
        }
        when {
            activeDownload != null && activeDownload.error == null -> Box(
                modifier = Modifier.size(28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (activeDownload.totalBytes > 0) {
                    CircularProgressIndicator(
                        progress = {
                            (activeDownload.downloadedBytes.toFloat() / activeDownload.totalBytes)
                                .coerceIn(0f, 1f)
                        },
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            activeDownload != null -> IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "重新下载",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(22.dp),
                )
            }

            downloaded -> Icon(
                imageVector = Icons.Outlined.DownloadDone,
                contentDescription = "已下载",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )

            downloadable -> IconButton(onClick = onDownload) {
                Icon(
                    imageVector = Icons.Outlined.Download,
                    contentDescription = "下载",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
            }

            else -> Icon(
                imageVector = Icons.Outlined.AutoStories,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
