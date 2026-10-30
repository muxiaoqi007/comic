package com.example.comic.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.comic.data.AppContainer
import com.example.comic.model.Book
import com.example.comic.model.Series
import com.example.comic.model.SourceType
import com.example.comic.ui.screens.DownloadsScreen
import com.example.comic.ui.screens.LibraryScreen
import com.example.comic.ui.screens.ReaderScreen
import com.example.comic.ui.screens.SeriesDetailScreen
import com.example.comic.ui.screens.SourcesScreen
import com.example.comic.ui.screens.ZlibraryScreen
import com.example.comic.ui.viewmodel.LibraryViewModel
import com.example.comic.ui.viewmodel.ReaderViewModel
import com.example.comic.ui.viewmodel.SeriesDetailViewModel
import com.example.comic.ui.viewmodel.ZlibraryViewModel
import android.widget.Toast

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
fun ComicApp(container: AppContainer) {
    CompositionLocalProvider(LocalContainer provides container) {
        val navController = rememberNavController()
        val context = LocalContext.current
        val destinations = remember {
            listOf(
                TopLevelDestination(Routes.LIBRARY, "书架", Icons.Outlined.CollectionsBookmark),
                TopLevelDestination(Routes.DOWNLOADS, "下载", Icons.Outlined.Download),
                TopLevelDestination(Routes.SOURCES, "源管理", Icons.Outlined.Dns),
            )
        }
        val backStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = backStackEntry?.destination?.route
        val view = LocalView.current
        val isReading = currentRoute == Routes.READER_PATTERN
        DisposableEffect(view, isReading) {
            val previousKeepScreenOn = view.keepScreenOn
            view.keepScreenOn = isReading
            onDispose { view.keepScreenOn = previousKeepScreenOn }
        }
        val showBottomBar = currentRoute == Routes.LIBRARY ||
            currentRoute == Routes.DOWNLOADS ||
            currentRoute == Routes.SOURCES

        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        destinations.forEach { destination ->
                            NavigationBarItem(
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                selected = currentRoute == destination.route,
                                onClick = {
                                    navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = destination.icon,
                                        contentDescription = destination.label,
                                    )
                                },
                                label = { Text(destination.label) },
                            )
                        }
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Routes.LIBRARY,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Routes.LIBRARY) {
                    val viewModel: LibraryViewModel = viewModel {
                        LibraryViewModel(container.repository, container.shelfCacheStore)
                    }
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenSeries = { navController.navigate(Routes.series(it)) },
                        onOpenBook = { book, page ->
                            navController.navigate(Routes.reader(book, page))
                        },
                        onOpenSources = { navController.navigate(Routes.SOURCES) },
                    )
                }

                composable(Routes.DOWNLOADS) {
                    DownloadsScreen(
                        onOpen = { record ->
                            if (record.sourceType == SourceType.ZLIBRARY) {
                                if (!openDownloadedFile(context, record)) {
                                    Toast.makeText(
                                        context,
                                        "没有可打开此文件的应用",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                navController.navigate(
                                    Routes.reader(
                                        Book(
                                            id = record.bookId,
                                            sourceId = record.sourceId,
                                            seriesId = record.seriesId,
                                            title = record.title,
                                        ),
                                    ),
                                )
                            }
                        },
                    )
                }

                composable(Routes.SOURCES) {
                    SourcesScreen(
                        onOpenZlibrary = { source ->
                            navController.navigate(Routes.zlibrary(source.id))
                        },
                    )
                }

                composable(
                    route = Routes.ZLIBRARY_PATTERN,
                    arguments = listOf(navArgument("sourceId") { type = NavType.StringType }),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val viewModel: ZlibraryViewModel = viewModel {
                        ZlibraryViewModel(
                            repository = container.repository,
                            store = container.downloadStore,
                            downloads = container.zlibraryDownloads,
                            sourceId = sourceId,
                        )
                    }
                    ZlibraryScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                    )
                }

                composable(
                    route = Routes.SERIES_PATTERN,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("seriesId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("libraryId") { type = NavType.StringType },
                        navArgument("bookCount") {
                            type = NavType.IntType
                            defaultValue = -1
                        },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val seriesId = entry.arguments?.getString("seriesId").orEmpty()
                    val title = entry.arguments?.getString("title").orEmpty()
                    val libraryId = entry.arguments?.getString("libraryId").orEmpty()
                    val bookCount = entry.arguments?.getInt("bookCount") ?: -1
                    val series = Series(
                        id = seriesId,
                        sourceId = sourceId,
                        libraryId = libraryId,
                        title = title,
                        bookCount = bookCount.takeIf { it >= 0 },
                    )
                    val viewModel: SeriesDetailViewModel = viewModel {
                        SeriesDetailViewModel(container.repository, series)
                    }
                    SeriesDetailScreen(
                        series = series,
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenBook = { book, page ->
                            navController.navigate(Routes.reader(book, page))
                        },
                    )
                }

                composable(
                    route = Routes.READER_PATTERN,
                    arguments = listOf(
                        navArgument("sourceId") { type = NavType.StringType },
                        navArgument("bookId") { type = NavType.StringType },
                        navArgument("seriesId") { type = NavType.StringType },
                        navArgument("title") { type = NavType.StringType },
                        navArgument("page") {
                            type = NavType.IntType
                            defaultValue = -1
                        },
                    ),
                ) { entry ->
                    val sourceId = entry.arguments?.getString("sourceId").orEmpty()
                    val bookId = entry.arguments?.getString("bookId").orEmpty()
                    val seriesId = entry.arguments?.getString("seriesId").orEmpty()
                    val title = entry.arguments?.getString("title").orEmpty()
                    val page = entry.arguments?.getInt("page") ?: -1
                    val viewModel: ReaderViewModel = viewModel {
                        ReaderViewModel(
                            repository = container.repository,
                            imageLoader = container.imageLoader,
                            sourceId = sourceId,
                            bookId = bookId,
                            seriesId = seriesId,
                            bookTitle = title,
                            initialPageOverride = page.takeIf { it >= 0 },
                        )
                    }
                    ReaderScreen(
                        viewModel = viewModel,
                        onBack = { navController.popBackStack() },
                        onOpenNext = { next ->
                            navController.navigate(Routes.reader(next)) {
                                popUpTo(Routes.READER_PATTERN) { inclusive = true }
                            }
                        },
                    )
                }
            }
        }
    }
}
