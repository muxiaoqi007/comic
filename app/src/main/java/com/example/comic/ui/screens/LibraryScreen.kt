package com.example.comic.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.comic.model.Book
import com.example.comic.model.ReadingProgress
import com.example.comic.model.Series
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.ErrorState
import com.example.comic.ui.components.LoadingState
import com.example.comic.ui.components.SeriesCover
import com.example.comic.ui.viewmodel.LibraryViewModel

private enum class ShelfFilter(val label: String) {
    ALL("全部"),
    READING("在读"),
    UNREAD("未读"),
    FINISHED("已读完"),
}

private val PaperTop = Color(0xFFF7F1E5)
private val PaperBottom = Color(0xFFEDE1CD)
private val ShelfLight = Color(0xFFC79A6A)
private val ShelfMid = Color(0xFF9C6B40)
private val ShelfDark = Color(0xFF66401F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book) -> Unit,
    onOpenSources: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sources by LocalContainer.current.repository.sources.collectAsState()
    val progressMap by LocalContainer.current.repository.progress.collectAsState()
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }

    LaunchedEffect(sources) {
        viewModel.refresh()
    }

    val resolvedProgress = remember(progressMap, state.entries) {
        progressMap.values.mapNotNull { progress ->
            val candidates = state.entries.filter { it.series.sourceId == progress.sourceId }
            val matched = progress.seriesId?.let { wanted ->
                candidates.firstOrNull { it.series.id == wanted }
                    ?: candidates.firstOrNull { it.series.title == wanted }
            } ?: candidates.firstOrNull { it.series.id == progress.bookId }
            val seriesId = matched?.series?.id ?: progress.seriesId
                ?: return@mapNotNull null
            progress.copy(seriesId = seriesId)
        }
    }
    val progressBySeries = remember(resolvedProgress) {
        resolvedProgress.groupBy { "${it.sourceId}|${it.seriesId}" }
    }
    val seriesProgress = remember(progressBySeries) {
        progressBySeries.mapValues { (_, values) -> values.maxBy { it.updatedAt } }
    }

    fun statusOf(entry: LibraryViewModel.Entry): ShelfFilter {
        val progresses = progressBySeries["${entry.series.sourceId}|${entry.series.id}"]
            ?: return ShelfFilter.UNREAD
        val started = progresses.map { it.bookId }.distinct().size
        val expected = entry.series.bookCount
        val allCompleted = progresses.all { it.completed }
        val allVolumesRead = expected == null || started >= expected
        return if (allCompleted && allVolumesRead) {
            ShelfFilter.FINISHED
        } else {
            ShelfFilter.READING
        }
    }

    val sourceEntries = state.entries.filter { entry ->
        selectedSourceId == null || entry.series.sourceId == selectedSourceId
    }
    val counts = ShelfFilter.entries.associateWith { target ->
        if (target == ShelfFilter.ALL) sourceEntries.size
        else sourceEntries.count { statusOf(it) == target }
    }
    val visibleEntries = sourceEntries.filter { filter == ShelfFilter.ALL || statusOf(it) == filter }

    val hero = remember(resolvedProgress, state.entries) {
        resolvedProgress
            .filter { it.updatedAt > 0 }
            .maxByOrNull { it.updatedAt }
            ?.let { progress ->
                state.entries.firstOrNull {
                    it.series.sourceId == progress.sourceId && it.series.id == progress.seriesId
                }?.let { entry -> entry to progress }
            }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(PaperTop, PaperBottom))),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Column {
                            Text(
                                text = "书架",
                                fontFamily = FontFamily.Serif,
                                fontWeight = FontWeight.Bold,
                                fontSize = 30.sp,
                            )
                            Text(
                                text = "${state.entries.size} 部作品 · ${counts[ShelfFilter.READING] ?: 0} 部在读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
                        SourceMenu(
                            sources = sources,
                            selectedSourceId = selectedSourceId,
                            onSelect = { selectedSourceId = it },
                        )
                        IconButton(
                            onClick = { viewModel.refresh() },
                            enabled = !state.loading,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    },
                )
            },
        ) { padding ->
            when {
                state.loading && state.entries.isEmpty() -> LoadingState()
                sources.isEmpty() -> EmptyState(
                    title = "还没有漫画源",
                    description = "添加本地文件夹、WebDAV、Komga 或 Koobone 账号后，漫画会出现在这里。",
                    modifier = Modifier.padding(padding),
                    actionLabel = "管理漫画源",
                    onAction = onOpenSources,
                )
                state.entries.isEmpty() && state.failures.isNotEmpty() -> ErrorState(
                    message = "加载失败：${state.failures.joinToString("；")}",
                    modifier = Modifier.padding(padding),
                    onRetry = { viewModel.refresh() },
                )
                state.entries.isEmpty() -> EmptyState(
                    title = "书架是空的",
                    description = "没有找到漫画，检查一下源目录结构，或点击刷新重试。",
                    modifier = Modifier.padding(padding),
                    actionLabel = "刷新",
                    onAction = { viewModel.refresh() },
                )
                else -> Column(Modifier.fillMaxSize().padding(padding)) {
                    hero?.let { (entry, progress) ->
                        ContinueReadingCard(
                            entry = entry,
                            progress = progress,
                            onOpen = {
                                onOpenBook(
                                    Book(
                                        id = progress.bookId,
                                        sourceId = progress.sourceId,
                                        seriesId = progress.seriesId ?: entry.series.id,
                                        title = progress.bookTitle ?: entry.series.title,
                                    ),
                                )
                            },
                        )
                    }
                    ShelfFilters(
                        filter = filter,
                        counts = counts,
                        onSelect = { filter = it },
                    )
                    if (state.failures.isNotEmpty()) {
                        FailureBanner(state.failures)
                    }
                    if (visibleEntries.isEmpty()) {
                        EmptyState(
                            title = "没有符合条件的漫画",
                            description = "换一个筛选条件，或切换漫画源看看。",
                            actionLabel = "查看全部",
                            onAction = {
                                filter = ShelfFilter.ALL
                                selectedSourceId = null
                            },
                        )
                    } else {
                        ShelfList(
                            entries = visibleEntries,
                            progressFor = { seriesProgress["${it.series.sourceId}|${it.series.id}"] },
                            readCountFor = { entry ->
                                progressBySeries["${entry.series.sourceId}|${entry.series.id}"]
                                    ?.count { it.completed } ?: 0
                            },
                            onOpen = onOpenSeries,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(
    entry: LibraryViewModel.Entry,
    progress: ReadingProgress,
    onOpen: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF33261A), Color(0xFF6B4529), Color(0xFF8A5A34)),
                ),
            )
            .clickable(onClick = onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeriesCover(
                series = entry.series,
                modifier = Modifier
                    .width(64.dp)
                    .height(96.dp)
                    .clip(RoundedCornerShape(6.dp)),
                cornerRadius = 6,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (progress.completed) {
                        "已读完 · ${entry.series.title}"
                    } else {
                        "继续阅读 · ${entry.series.title}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.68f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = progress.bookTitle ?: entry.series.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Serif,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = {
                        if (progress.completed) {
                            1f
                        } else {
                            ((progress.page + 1).toFloat() / progress.totalPages.coerceAtLeast(1))
                                .coerceIn(0f, 1f)
                        }
                    },
                    color = Color(0xFFF0C98B),
                    trackColor = Color.White.copy(alpha = 0.22f),
                    modifier = Modifier.fillMaxWidth(0.86f),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (progress.completed) {
                        "已读完 · 共 ${progress.totalPages} 页"
                    } else {
                        "读到 ${progress.page + 1} / ${progress.totalPages} 页"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForward,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun ShelfFilters(
    filter: ShelfFilter,
    counts: Map<ShelfFilter, Int>,
    onSelect: (ShelfFilter) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(ShelfFilter.entries) { item ->
            FilterChip(
                selected = filter == item,
                onClick = { onSelect(item) },
                label = {
                    Text("${item.label} ${counts[item] ?: 0}")
                },
            )
        }
    }
}

@Composable
private fun SourceMenu(
    sources: List<SourceConfig>,
    selectedSourceId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = sources.firstOrNull { it.id == selectedSourceId }?.name ?: "全部来源"
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(
                text = selectedName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("全部来源") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            sources.forEach { source ->
                DropdownMenuItem(
                    text = { Text(source.name) },
                    onClick = {
                        expanded = false
                        onSelect(source.id)
                    },
                )
            }
        }
    }
}

@Composable
private fun ShelfList(
    entries: List<LibraryViewModel.Entry>,
    progressFor: (LibraryViewModel.Entry) -> ReadingProgress?,
    readCountFor: (LibraryViewModel.Entry) -> Int,
    onOpen: (Series) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val columns = ((maxWidth - 32.dp) / 108.dp).toInt().coerceIn(2, 4)
        val rows = entries.chunked(columns)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp),
        ) {
            itemsIndexed(rows) { index, row ->
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        row.forEach { entry ->
                            ShelfBook(
                                entry = entry,
                                progress = progressFor(entry),
                                readCount = readCountFor(entry),
                                modifier = Modifier.weight(1f),
                                onClick = { onOpen(entry.series) },
                            )
                        }
                        repeat(columns - row.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                    ShelfPlank(showShadow = index > 0)
                }
            }
        }
    }
}

@Composable
private fun ShelfBook(
    entry: LibraryViewModel.Entry,
    progress: ReadingProgress?,
    readCount: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .aspectRatio(0.66f)
            .shadow(10.dp, RoundedCornerShape(topStart = 3.dp, topEnd = 7.dp, bottomEnd = 6.dp, bottomStart = 3.dp))
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 7.dp, bottomEnd = 6.dp, bottomStart = 3.dp))
            .clickable(onClick = onClick),
    ) {
        SeriesCover(
            series = entry.series,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 0,
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(8.dp)
                .align(Alignment.CenterStart)
                .background(
                    Brush.horizontalGradient(
                        listOf(Color.Black.copy(alpha = 0.38f), Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .align(Alignment.CenterEnd)
                .background(Color.White.copy(alpha = 0.55f)),
        )
        Surface(
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.48f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(6.dp),
        ) {
            Text(
                text = sourceLabel(entry.sourceType),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val count = entry.series.bookCount
        if (count != null && count > 1) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.55f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp),
            ) {
                Text(
                    text = "$count 册",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f)),
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = entry.series.title,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val count = entry.series.bookCount
            val readLabel = when {
                count != null && count > 1 && readCount > 0 -> "已读 $readCount/$count 册"
                progress?.completed == true -> "已读完"
                else -> null
            }
            readLabel?.let {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.78f),
                )
            }
            if (progress != null && progress.totalPages > 0) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = {
                        ((progress.page + 1).toFloat() / progress.totalPages).coerceIn(0f, 1f)
                    },
                    color = Color(0xFFF0C98B),
                    trackColor = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                )
            }
        }
    }
}

@Composable
private fun ShelfPlank(showShadow: Boolean) {
    Column(Modifier.padding(top = 6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(12.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x33000000)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(11.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(ShelfLight, ShelfMid, ShelfDark),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(Color(0xFF4C2E16)),
        )
        Spacer(Modifier.height(if (showShadow) 24.dp else 18.dp))
    }
}

private fun sourceLabel(type: SourceType): String = when (type) {
    SourceType.LOCAL -> "本地"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.KOMGA -> "Komga"
    SourceType.KOOBONE -> "Koobone"
}

@Composable
private fun FailureBanner(failures: List<String>) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = "加载失败：${failures.joinToString("、")}",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}
