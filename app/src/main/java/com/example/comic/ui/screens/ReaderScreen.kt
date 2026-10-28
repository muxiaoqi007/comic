package com.example.comic.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.comic.data.ImageLoader
import com.example.comic.model.Page
import com.example.comic.model.ReadingMode
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.ErrorState
import com.example.comic.ui.components.LoadingState
import com.example.comic.ui.viewmodel.ReaderViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val readingSettings = LocalContainer.current.readingSettings
    val mode by readingSettings.mode.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when {
            state.loading -> LoadingState(
                label = "加载页面…",
                modifier = Modifier.background(Color.Black),
            )

            state.error != null -> Column(Modifier.fillMaxSize()) {
                ReaderTopBar(
                    title = viewModel.book.title,
                    pageLabel = "",
                    onBack = onBack,
                )
                ErrorState(
                    message = state.error ?: "加载失败",
                    onRetry = { viewModel.load() },
                )
            }

            state.pages.isEmpty() -> Column(Modifier.fillMaxSize()) {
                ReaderTopBar(
                    title = viewModel.book.title,
                    pageLabel = "",
                    onBack = onBack,
                )
                ErrorState(message = "这本漫画没有可显示的页面")
            }

            else -> ReaderContent(
                viewModel = viewModel,
                pages = state.pages,
                mode = mode,
                onModeChange = readingSettings::setMode,
                onBack = onBack,
            )
        }
    }
}

@Composable
private fun ReaderContent(
    viewModel: ReaderViewModel,
    pages: List<Page>,
    mode: ReadingMode,
    onModeChange: (ReadingMode) -> Unit,
    onBack: () -> Unit,
) {
    val imageLoader = viewModel.imageLoader
    val initialPage = viewModel.initialPage.coerceIn(0, pages.lastIndex)
    val pagerState = rememberPagerState(initialPage = initialPage) { pages.size }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    val scope = rememberCoroutineScope()
    var chromeVisible by remember { mutableStateOf(true) }
    var previousMode by remember { mutableStateOf(mode) }
    val currentMode by rememberUpdatedState(mode)

    val layoutInfo = listState.layoutInfo
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()
    val lastPageMostlyVisible = lastVisible != null &&
        lastVisible.index == pages.lastIndex &&
        run {
            val visible = minOf(lastVisible.offset + lastVisible.size, layoutInfo.viewportEndOffset) -
                maxOf(lastVisible.offset, layoutInfo.viewportStartOffset)
            visible >= lastVisible.size * 0.6f
        }
    val atListEnd = layoutInfo.visibleItemsInfo.isNotEmpty() && !listState.canScrollForward
    val verticalPage = if (lastPageMostlyVisible || atListEnd) {
        pages.lastIndex
    } else {
        listState.firstVisibleItemIndex.coerceIn(0, pages.lastIndex)
    }
    val currentPage = if (mode == ReadingMode.VERTICAL) {
        verticalPage
    } else {
        pagerState.currentPage.coerceIn(0, pages.lastIndex)
    }
    var exiting by remember { mutableStateOf(false) }

    fun finish() {
        if (exiting) return
        exiting = true
        viewModel.saveNowBlocking(currentPage, pages.size)
        onBack()
    }

    BackHandler(onBack = ::finish)

    LaunchedEffect(mode) {
        if (previousMode != mode) {
            val target = if (previousMode == ReadingMode.VERTICAL) {
                listState.firstVisibleItemIndex
            } else {
                pagerState.currentPage
            }.coerceIn(0, pages.lastIndex)
            if (mode == ReadingMode.VERTICAL) {
                listState.scrollToItem(target)
            } else {
                pagerState.scrollToPage(target)
            }
            previousMode = mode
        }
    }

    LaunchedEffect(pagerState.settledPage) {
        if (!exiting && currentMode != ReadingMode.VERTICAL) {
            viewModel.scheduleSave(pagerState.settledPage, pages.size)
        }
    }

    LaunchedEffect(currentPage, mode) {
        if (!exiting && currentMode == ReadingMode.VERTICAL) {
            viewModel.scheduleSave(currentPage, pages.size)
        }
    }

    fun goTo(page: Int) {
        val target = page.coerceIn(0, pages.lastIndex)
        scope.launch {
            if (mode == ReadingMode.VERTICAL) {
                listState.scrollToItem(target)
            } else {
                pagerState.animateScrollToPage(target)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mode) {
                    detectTapGestures { offset ->
                        if (mode == ReadingMode.VERTICAL) {
                            chromeVisible = !chromeVisible
                        } else {
                            val width = size.width.toFloat()
                            when {
                                offset.x < width * 0.28f -> {
                                    val target = if (mode == ReadingMode.RTL) {
                                        pagerState.currentPage + 1
                                    } else {
                                        pagerState.currentPage - 1
                                    }
                                    if (target in pages.indices) goTo(target)
                                }

                                offset.x > width * 0.72f -> {
                                    val target = if (mode == ReadingMode.RTL) {
                                        pagerState.currentPage - 1
                                    } else {
                                        pagerState.currentPage + 1
                                    }
                                    if (target in pages.indices) goTo(target)
                                }

                                else -> chromeVisible = !chromeVisible
                            }
                        }
                    }
                },
        ) {
            if (mode == ReadingMode.VERTICAL) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                ) {
                    items(count = pages.size, key = { pages[it].key }) { index ->
                        VerticalReaderPage(page = pages[index], loader = imageLoader)
                    }
                }
            } else {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (mode == ReadingMode.RTL) {
                        LayoutDirection.Rtl
                    } else {
                        LayoutDirection.Ltr
                    },
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(0.dp),
                        pageSpacing = 0.dp,
                        beyondViewportPageCount = 1,
                    ) { pageIndex ->
                        ReaderPage(page = pages[pageIndex], loader = imageLoader)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopBar(
                title = viewModel.book.title,
                pageLabel = "${currentPage + 1} / ${pages.size}",
                onBack = { finish() },
                mode = mode,
                onModeChange = onModeChange,
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderBottomBar(
                page = currentPage,
                total = pages.size,
                onSeek = { goTo(it) },
            )
        }
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    pageLabel: String,
    onBack: () -> Unit,
    mode: ReadingMode? = null,
    onModeChange: ((ReadingMode) -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 6.dp)
            .pointerInput(Unit) { detectTapGestures { } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                contentDescription = "返回",
                tint = Color.White,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (pageLabel.isNotBlank()) {
                Text(
                    text = pageLabel,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (mode != null && onModeChange != null) {
            ReadingModeMenu(mode = mode, onModeChange = onModeChange)
        }
    }
}

@Composable
private fun ReadingModeMenu(
    mode: ReadingMode,
    onModeChange: (ReadingMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) {
            Icon(modeIcon(mode), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text(modeLabel(mode))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            ReadingMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(modeLabel(option)) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (option == mode) {
                                Icons.Outlined.Check
                            } else {
                                modeIcon(option)
                            },
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    onClick = {
                        expanded = false
                        onModeChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    total: Int,
    onSeek: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.62f))
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Text(
            text = "${page + 1} / $total",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        Slider(
            value = page.toFloat(),
            onValueChange = { value -> onSeek(value.roundToInt()) },
            valueRange = 0f..(total - 1).toFloat().coerceAtLeast(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
    }
}

@Composable
private fun ReaderPage(page: Page, loader: ImageLoader) {
    var attempt by remember(page.key) { mutableIntStateOf(0) }
    val result by produceState<Result<Bitmap?>?>(initialValue = null, page.key, attempt) {
        value = runCatching { loader.loadPage(page) }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val current = result
        when {
            current == null -> CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))

            current.getOrNull() != null -> Image(
                bitmap = current.getOrNull()!!.asImageBitmap(),
                contentDescription = "第 ${page.index + 1} 页",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            else -> PageError(
                page = page,
                message = current.exceptionOrNull()?.message ?: "页面加载失败",
                onRetry = { attempt++ },
            )
        }
    }
}

@Composable
private fun VerticalReaderPage(page: Page, loader: ImageLoader) {
    var attempt by remember(page.key) { mutableIntStateOf(0) }
    val result by produceState<Result<Bitmap?>?>(initialValue = null, page.key, attempt) {
        value = runCatching { loader.loadPage(page) }
    }
    val bitmap = result?.getOrNull()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            result == null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f))
            }

            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "第 ${page.index + 1} 页",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )

            else -> PageError(
                page = page,
                message = result?.exceptionOrNull()?.message ?: "页面加载失败",
                onRetry = { attempt++ },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f),
            )
        }
    }
}

@Composable
private fun PageError(
    page: Page,
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.BrokenImage,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
        ) { Text("重试 · 第 ${page.index + 1} 页") }
    }
}

private fun modeLabel(mode: ReadingMode): String = when (mode) {
    ReadingMode.LTR -> "从左向右"
    ReadingMode.RTL -> "从右向左"
    ReadingMode.VERTICAL -> "从上到下"
}

private fun modeIcon(mode: ReadingMode): ImageVector = when (mode) {
    ReadingMode.VERTICAL -> Icons.Outlined.SwapVert
    else -> Icons.Outlined.SwapHoriz
}
