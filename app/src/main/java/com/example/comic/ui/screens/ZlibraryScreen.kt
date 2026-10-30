package com.example.comic.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.comic.source.zlibrary.ZlibraryBook
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.LoadingState
import com.example.comic.ui.components.formatSize
import com.example.comic.ui.openDownloadedFile
import com.example.comic.ui.viewmodel.ZlibraryFormat
import com.example.comic.ui.viewmodel.ZlibraryViewModel
import kotlinx.coroutines.launch

private const val ZLIBRARY_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0 Safari/537.36"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZlibraryScreen(
    viewModel: ZlibraryViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val downloaded by viewModel.downloaded.collectAsState()
    val active by viewModel.active.collectAsState()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }

    fun submitSearch() {
        focusManager.clearFocus()
        viewModel.search(query)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = viewModel.sourceName,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "个人账号 · EPUB / PDF 下载",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.connecting -> LoadingState(
                label = "正在登录 Z-Library…",
                modifier = Modifier.padding(padding),
            )

            !state.connected -> EmptyState(
                title = "无法连接 Z-Library",
                description = state.error ?: "请检查域名、网络或账号密码后重试。",
                modifier = Modifier.padding(padding),
                actionLabel = "重新登录",
                onAction = { viewModel.connect() },
            )

            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                state.profile?.let { profile ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Outlined.LibraryBooks,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    text = profile.downloadsLimit?.let {
                                        "今日已下载 ${profile.downloadsToday} / $it"
                                    } ?: "今日已下载 ${profile.downloadsToday} 本",
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                Text(
                                    text = "仅使用你配置的个人账号",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { viewModel.refreshProfile() }) {
                                Icon(
                                    Icons.Outlined.Refresh,
                                    contentDescription = "刷新下载额度",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    placeholder = { Text("搜索书名或作者") },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { submitSearch() }) {
                                Icon(Icons.Outlined.Search, contentDescription = "搜索")
                            }
                            if (query.isNotEmpty()) {
                                IconButton(onClick = {
                                    query = ""
                                    viewModel.search("")
                                }) {
                                    Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                                }
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                )

                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp),
                ) {
                    items(ZlibraryFormat.entries) { format ->
                        FilterChip(
                            selected = state.format == format,
                            onClick = { viewModel.selectFormat(format) },
                            label = { Text(format.label) },
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.searching && state.books.isEmpty() -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                        state.searchError != null && state.books.isEmpty() -> EmptyState(
                            title = "搜索失败",
                            description = state.searchError ?: "请稍后再试",
                            actionLabel = "重试",
                            onAction = { viewModel.search(query) },
                        )

                        state.books.isEmpty() -> EmptyState(
                            title = if (query.isBlank()) "搜索你的 Z-Library" else "没有找到「${query.trim()}」",
                            description = if (query.isBlank()) {
                                "输入书名或作者，结果可以下载为 EPUB/PDF。"
                            } else {
                                "换个关键词或格式再试试。"
                            },
                        )

                        else -> LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.books, key = { it.id }) { book ->
                                ZlibraryBookRow(
                                    book = book,
                                    sourceId = viewModel.sourceId,
                                    record = downloaded[book.id],
                                    active = active["${viewModel.sourceId}|${book.id}"],
                                    onDownload = { viewModel.download(book) },
                                    onRetry = { item -> viewModel.retry(item) },
                                    onCancel = { key -> viewModel.cancel(key) },
                                    onOpen = { record ->
                                        if (!openDownloadedFile(context, record)) {
                                            scope.launch {
                                                snackbar.showSnackbar("没有可打开此文件的应用")
                                            }
                                        }
                                    },
                                )
                            }
                            if (state.searchError != null) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = state.searchError ?: "",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.weight(1f),
                                        )
                                        TextButton(onClick = { viewModel.search(query) }) {
                                            Text("重试")
                                        }
                                    }
                                }
                            }
                            if (state.page < state.totalPages) {
                                item {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                    ) {
                                        if (state.searching) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(22.dp),
                                                strokeWidth = 2.dp,
                                            )
                                        } else {
                                            TextButton(onClick = { viewModel.loadMore() }) {
                                                Text("加载更多")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZlibraryBookRow(
    book: ZlibraryBook,
    sourceId: String,
    record: com.example.comic.model.DownloadRecord?,
    active: com.example.comic.data.ZlibraryDownloadManager.Active?,
    onDownload: () -> Unit,
    onRetry: (com.example.comic.data.ZlibraryDownloadManager.Active) -> Unit,
    onCancel: (String) -> Unit,
    onOpen: (com.example.comic.model.DownloadRecord) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            ZlibraryCover(
                book = book,
                sourceId = sourceId,
                modifier = Modifier
                    .width(58.dp)
                    .height(84.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = listOfNotNull(
                        book.year,
                        book.language,
                        book.extension?.uppercase(),
                        formatSize(book.sizeBytes),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                active?.error?.let { error ->
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = error,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.width(58.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                when {
                    record != null -> TextButton(onClick = { onOpen(record) }) {
                        Text("打开")
                    }

                    active?.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextButton(onClick = { onRetry(active) }) { Text("重试") }
                        IconButton(
                            onClick = { onCancel(active.key) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "移除下载",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    active != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val fraction = if (active.totalBytes > 0) {
                            active.downloadedBytes.toFloat() / active.totalBytes
                        } else {
                            null
                        }
                        if (fraction != null) {
                            CircularProgressIndicator(
                                progress = { fraction.coerceIn(0f, 1f) },
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                strokeWidth = 2.5.dp,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = if (fraction != null) {
                                "${(fraction * 100).toInt()}%"
                            } else {
                                "下载中"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        IconButton(
                            onClick = { onCancel(active.key) },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "取消下载",
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }

                    else -> IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Outlined.Download,
                            contentDescription = "下载 ${book.title}",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ZlibraryCover(
    book: ZlibraryBook,
    sourceId: String,
    modifier: Modifier = Modifier,
) {
    val loader = LocalContainer.current.imageLoader
    var bitmap by remember(book.id, book.coverUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(book.id, book.coverUrl) {
        bitmap = book.coverUrl?.let { url ->
            loader.loadRemoteCover(
                cacheKey = "zlibrary|$sourceId|${book.id}",
                url = url,
                headers = mapOf("User-Agent" to ZLIBRARY_UA),
            )
        }
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = book.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Outlined.LibraryBooks,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
