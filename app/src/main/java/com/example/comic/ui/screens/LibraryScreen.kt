package com.example.comic.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.input.ImeAction
import com.example.comic.ui.viewmodel.ShelfSort
import com.example.comic.ui.viewmodel.selectShelfEntries
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
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.example.comic.ui.components.LoadingState
import com.example.comic.ui.components.SeriesCover
import com.example.comic.ui.viewmodel.LibraryViewModel

private enum class ShelfFilter(val label: String) {
    ALL("全部"),
    READING("在读"),
    UNREAD("未读"),
    FINISHED("系列读完"),
}

private val ShelfLight = Color(0xFFC79A6A)
private val ShelfMid = Color(0xFF9C6B40)
private val ShelfDark = Color(0xFF66401F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenSeries: (Series) -> Unit,
    onOpenBook: (Book, Int?) -> Unit,
    onOpenSources: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val sources by LocalContainer.current.repository.sources.collectAsState()
    val progressMap by LocalContainer.current.repository.progress.collectAsState()
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ShelfFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ShelfSort.TITLE) }
    val focusManager = LocalFocusManager.current
    val comicSources = remember(sources) { sources.filter { it.type != SourceType.ZLIBRARY } }
    val enabledSources = remember(comicSources) { comicSources.filter { it.enabled } }

    LaunchedEffect(enabledSources) {
        if (enabledSources.none { it.id == selectedSourceId }) selectedSourceId = null
    }

    val resolvedProgress = remember(progressMap, state.entries) {
        val byId = state.entries.associateBy { it.series.sourceId to it.series.id }
        val byTitle = state.entries.groupBy { it.series.sourceId to it.series.title }
        progressMap.values.mapNotNull { progress ->
            val matched = progress.seriesId?.let { wanted ->
                byId[progress.sourceId to wanted]
                    ?: byTitle[progress.sourceId to wanted]?.singleOrNull()
            } ?: byId[progress.sourceId to progress.bookId]
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
        val allVolumesRead = expected != null && started >= expected
        return if (allCompleted && allVolumesRead) {
            ShelfFilter.FINISHED
        } else {
            ShelfFilter.READING
        }
    }

    val sourceEntries = remember(state.entries, selectedSourceId, query, sort, seriesProgress) {
        selectShelfEntries(state.entries, query, selectedSourceId, sort, seriesProgress.mapValues { it.value.updatedAt })
    }
    val counts = ShelfFilter.entries.associateWith { target ->
        if (target == ShelfFilter.ALL) sourceEntries.size
        else sourceEntries.count { statusOf(it) == target }
    }
    val visibleEntries = sourceEntries.filter { filter == ShelfFilter.ALL || statusOf(it) == filter }

    val hero = remember(resolvedProgress, state.entries, selectedSourceId) {
        val entriesById = state.entries.associateBy { it.series.sourceId to it.series.id }
        resolvedProgress
            .filter { it.updatedAt > 0 && (selectedSourceId == null || it.sourceId == selectedSourceId) }
            .mapNotNull { progress ->
                entriesById[progress.sourceId to progress.seriesId]?.let { it to progress }
            }
            .maxByOrNull { it.second.updatedAt }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ),
            ),
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
                                text = "把故事留在手边 · ${state.entries.size} 部收藏",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    actions = {
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
                comicSources.isEmpty() -> EmptyState(
                    title = "还没有漫画源",
                    description = "Z-Library 是下载源。添加本地文件夹、WebDAV、Komga 或 Koobone 后，漫画会出现在这里。",
                    modifier = Modifier.padding(padding),
                    actionLabel = "管理漫画源",
                    onAction = onOpenSources,
                )
                enabledSources.isEmpty() -> EmptyState(
                    title = "所有漫画源都已停用",
                    description = "在源管理里打开需要的源，书架会重新加载；停用的源不会发起网络请求。",
                    modifier = Modifier.padding(padding),
                    actionLabel = "管理漫画源",
                    onAction = onOpenSources,
                )
                state.entries.isEmpty() && state.failures.isNotEmpty() -> LibraryFailureState(
                    failures = state.failures,
                    modifier = Modifier.padding(padding),
                    onRetry = { viewModel.refresh() },
                    onOpenSources = onOpenSources,
                )
                state.entries.isEmpty() -> EmptyState(
                    title = "书架是空的",
                    description = "没有找到漫画，检查一下源目录结构，或点击刷新重试。",
                    modifier = Modifier.padding(padding),
                    actionLabel = "刷新",
                    onAction = { viewModel.refresh() },
                )
                else -> Column(Modifier.fillMaxSize().padding(padding)) {
                    ShelfSearchField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = { focusManager.clearFocus() },
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SourceMenu(enabledSources, selectedSourceId) { selectedSourceId = it }
                        Spacer(Modifier.weight(1f))
                        ShelfSortMenu(sort) { sort = it }
                    }
                    ShelfFilters(filter, counts) { filter = it }
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    ShelfList(
                        entries = visibleEntries,
                        progressFor = { seriesProgress["${it.series.sourceId}|${it.series.id}"] },
                        readCountFor = { entry ->
                            progressBySeries["${entry.series.sourceId}|${entry.series.id}"]?.count { it.completed } ?: 0
                        },
                        onOpen = { focusManager.clearFocus(); onOpenSeries(it) },
                        resetKey = "$query|$filter|$selectedSourceId|$sort",
                        modifier = Modifier.weight(1f),
                        header = {
                            if (state.failures.isNotEmpty()) {
                                FailureNotice(state.failures, onRetry = { viewModel.refresh() })
                            }
                            if (query.isBlank() && filter == ShelfFilter.ALL) hero?.let { (entry, progress) ->
                                ContinueReadingCard(
                                    entry = entry,
                                    progress = progress,
                                    onOpen = { onOpenBook(heroBook(entry, progress), null) },
                                    onRestart = { onOpenBook(heroBook(entry, progress), 0) },
                                    onOpenSeries = { onOpenSeries(entry.series) },
                                )
                            }
                            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (query.isBlank()) "藏书一览" else "搜索结果", fontFamily = FontFamily.Serif, style = MaterialTheme.typography.titleSmall)
                                Spacer(Modifier.weight(1f))
                                Text("${visibleEntries.size} 部作品", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                        emptyContent = {
                            EmptyState(
                                title = if (query.isBlank()) "这个书架暂时没有作品" else "没有找到「${query.trim()}」",
                                description = "试试其他关键词，或清除筛选条件。",
                                actionLabel = "重置搜索与筛选",
                                onAction = {
                                    query = ""
                                    filter = ShelfFilter.ALL
                                    selectedSourceId = null
                                    focusManager.clearFocus()
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun heroBook(entry: LibraryViewModel.Entry, progress: ReadingProgress): Book =
    Book(
        id = progress.bookId,
        sourceId = progress.sourceId,
        seriesId = progress.seriesId ?: entry.series.id,
        title = progress.bookTitle ?: entry.series.title,
    )

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ContinueReadingCard(
    entry: LibraryViewModel.Entry,
    progress: ReadingProgress,
    onOpen: () -> Unit,
    onRestart: () -> Unit,
    onOpenSeries: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF33261A), Color(0xFF6B4529), Color(0xFF8A5A34)),
                ),
            )
            .clickable(onClick = if (progress.completed) onOpenSeries else onOpen)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeriesCover(
                series = entry.series,
                modifier = Modifier
                    .width(52.dp)
                    .height(74.dp)
                    .clip(RoundedCornerShape(5.dp)),
                cornerRadius = 5,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (progress.completed) {
                        "本卷已读完 · ${entry.series.title}"
                    } else {
                        "继续阅读 · ${entry.series.title}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.66f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = progress.bookTitle ?: entry.series.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Serif,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(7.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                        modifier = Modifier.weight(1f).height(3.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (progress.completed) {
                            "已读完"
                        } else {
                            "${progress.page + 1}/${progress.totalPages}"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.74f),
                    )
                }
                Spacer(Modifier.height(7.dp))
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Button(
                        onClick = if (progress.completed) onOpenSeries else onOpen,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFF5DFC0),
                            contentColor = Color(0xFF33261A),
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 38.dp),
                    ) {
                        Text(if (progress.completed) "查看系列" else "接着读", style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, Modifier.size(16.dp))
                    }
                    TextButton(
                        onClick = if (progress.completed) onRestart else onOpenSeries,
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White.copy(alpha = 0.9f)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        modifier = Modifier.heightIn(min = 38.dp),
                    ) { Text(if (progress.completed) "从头重读" else "系列", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun ShelfSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .height(42.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                "搜索作品或来源",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                },
            )
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .clickable { onQueryChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "清空搜索",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp),
    ) {
        items(ShelfFilter.entries) { item ->
            FilterChip(
                selected = filter == item,
                onClick = { onSelect(item) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                ),
                label = {
                    Text(
                        "${item.label} ${counts[item] ?: 0}",
                        style = MaterialTheme.typography.labelMedium,
                    )
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
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(
                Icons.Outlined.Dns,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = selectedName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
            Icon(
                Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
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
private fun ShelfSortMenu(sort: ShelfSort, onSelect: (ShelfSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
        ) {
            Icon(
                Icons.Outlined.Sort,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(sort.label, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ShelfSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = { if (sort == option) Icon(Icons.Outlined.Check, "已选中") },
                    onClick = { onSelect(option); expanded = false },
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
    resetKey: String,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    BoxWithConstraints(modifier) {
        val columns = ((maxWidth - 28.dp + 10.dp) / 114.dp).toInt().coerceIn(2, 6)
        val rows = entries.chunked(columns)
        val listState = rememberLazyListState()
        var previousResetKey by rememberSaveable { mutableStateOf(resetKey) }
        LaunchedEffect(resetKey) {
            if (previousResetKey != resetKey) {
                listState.scrollToItem(0)
                previousResetKey = resetKey
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
        ) {
            item(key = "header") { header() }
            if (entries.isEmpty()) item(key = "empty") { emptyContent() }
            itemsIndexed(rows, key = { _, row -> row.joinToString { "${it.series.sourceId}|${it.series.id}" } }) { index, row ->
                Column(Modifier.padding(horizontal = 14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top,
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
    val reserveTitleLines = LocalDensity.current.fontScale > 1.15f
    Column(modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.68f)
                .shadow(3.dp, RoundedCornerShape(3.dp, 8.dp, 8.dp, 3.dp))
                .clip(RoundedCornerShape(3.dp, 8.dp, 8.dp, 3.dp)),
        ) {
            SeriesCover(entry.series, Modifier.fillMaxSize(), cornerRadius = 0)
            Box(
                Modifier.fillMaxHeight().width(9.dp).align(Alignment.CenterStart)
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent))),
            )
            if (progress != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(bottomStart = 6.dp, bottomEnd = 6.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 10.dp),
                ) {
                    Icon(
                        if (entry.series.bookCount != null && readCount >= entry.series.bookCount) {
                            Icons.Outlined.Check
                        } else {
                            Icons.Outlined.Bookmark
                        },
                        contentDescription = if (
                            entry.series.bookCount != null &&
                            readCount >= entry.series.bookCount
                        ) {
                            "系列读完"
                        } else {
                            "有阅读记录"
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp).size(16.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(
            entry.series.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = if (reserveTitleLines) 2 else 1,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(1.dp))
        Text(
            "${sourceLabel(entry.sourceType)} · ${entry.series.bookCount?.let { "$it 卷" } ?: "卷数待确认"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                readCount > 0 -> "已读 $readCount / ${entry.series.bookCount ?: "?"} 卷"
                progress != null -> "读到 ${progress.page + 1} / ${progress.totalPages} 页"
                else -> "等待翻开"
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (progress != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ShelfPlank(showShadow: Boolean) {
    Column(Modifier.padding(top = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x33000000)),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
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
                .height(2.dp)
                .background(Color(0xFF4C2E16)),
        )
        Spacer(Modifier.height(if (showShadow) 14.dp else 10.dp))
    }
}

private fun sourceLabel(type: SourceType): String = when (type) {
    SourceType.LOCAL -> "本地"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.KOMGA -> "Komga"
    SourceType.KOOBONE -> "Koobone"
    SourceType.ZLIBRARY -> "Z-Library"
}

@Composable
private fun FailureNotice(failures: List<String>, onRetry: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.24f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(start = 12.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${failures.size} 个源未能加载",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (expanded) "收起" else "详情",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                    failures.forEach { failure ->
                        Text(
                            text = failure,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                    ) { Text("重新加载", style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun LibraryFailureState(
    failures: List<String>,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
    onOpenSources: () -> Unit,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
            modifier = Modifier.size(44.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text("暂时连不上漫画源", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "网络、地址或凭据可能发生了变化。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(14.dp))
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                failures.forEach { failure ->
                    Text(
                        text = failure,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(onClick = onRetry) { Text("重试") }
        TextButton(onClick = onOpenSources) { Text("检查源设置") }
    }
}
