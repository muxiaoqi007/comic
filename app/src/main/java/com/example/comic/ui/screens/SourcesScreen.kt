package com.example.comic.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation as ComposePassword
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import com.example.comic.data.SourceTestRecord
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.SourceTypeBadge
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onOpenZlibrary: (SourceConfig) -> Unit = {}) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val repository = container.repository
    val sources by repository.sources.collectAsState()
    val downloads by container.downloadManager.downloads.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    val testResults by container.sourceTestStore.results.collectAsState()

    fun test(config: SourceConfig) {
        if (testingId != null) return
        testingId = config.id
        scope.launch {
            val result = try {
                runCatching { repository.testConnection(config) }.also {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                }
            } finally {
                testingId = null
            }
            if (repository.sources.value.none { it == config }) return@launch
            val message = result.fold(
                onSuccess = { "成功" },
                onFailure = { (it.message ?: "连接失败").take(80) },
            )
            container.sourceTestStore.record(config.id, result.isSuccess, message)
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "${config.name}：连接成功" },
                    onFailure = { "${config.name}：${it.message ?: "连接失败"}" },
                ),
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "源管理",
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.Bold,
                            fontSize = 28.sp,
                        )
                        Text(
                            text = if (sources.isEmpty()) {
                                "v${versionName ?: "?"}"
                            } else {
                                "${sources.count { it.enabled }}/${sources.size} 个源启用 · v${versionName ?: "?"}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            if (sources.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showAdd = true },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                    text = { Text("添加源") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                title = "还没有漫画源",
                description = "支持本地文件夹、NAS 上的 WebDAV 目录、Komga 服务器、Koobone 账号，以及 Z-Library 个人账号下载。",
                modifier = Modifier.padding(padding),
                actionLabel = "添加漫画源",
                onAction = { showAdd = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        testing = testingId == source.id,
                        testResult = testResults[source.id],
                        onTest = { test(source) },
                        onEdit = { editing = source },
                        onDelete = { pendingDelete = source },
                        onToggleEnabled = { enabled ->
                            repository.setSourceEnabled(source.id, enabled)
                        },
                        onOpenLibrary = { onOpenZlibrary(source) },
                    )
                }
            }
        }
    }

    if (showAdd || editing != null) {
        val wasEditing = editing != null
        AddSourceDialog(
            initial = editing,
            onDismiss = {
                showAdd = false
                editing = null
            },
            onSave = { config ->
                repository.saveSource(config)
                showAdd = false
                editing = null
                scope.launch {
                    snackbar.showSnackbar(
                        if (wasEditing) "已更新：${config.name}" else "已添加：${config.name}",
                    )
                }
            },
            onTest = { config -> repository.testConnectionTransient(config) },
        )
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除漫画源") },
            text = {
                val affected = downloads.count { it.sourceId == source.id }
                Text(
                    buildString {
                        append("确定删除「${source.name}」吗？阅读进度会保留在本地。")
                        if (affected > 0) {
                            append("\n该源有 $affected 本已下载漫画，删除后将无法从“下载”页打开。")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.deleteSource(source.id)
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
private fun SourceCard(
    source: SourceConfig,
    testing: Boolean,
    testResult: SourceTestRecord?,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    val contentAlpha = if (source.enabled) 1f else 0.48f
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.32f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 13.dp, top = 12.dp, bottom = 8.dp, end = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .alpha(contentAlpha)
                    .clip(RoundedCornerShape(11.dp))
                    .background(accent.copy(alpha = 0.11f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = sourceIcon(source.type),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source.name,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f).alpha(contentAlpha),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.alpha(contentAlpha)) { SourceTypeBadge(source.type) }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = source.locationLabel(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.alpha(contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!source.enabled) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.outline),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "已停用 · 不会在书架加载",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        } else if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(11.dp),
                                strokeWidth = 1.5.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "正在测试…",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            testResult == null -> MaterialTheme.colorScheme.outline
                                            testResult.ok -> Color(0xFF3F8F62)
                                            else -> MaterialTheme.colorScheme.error
                                        },
                                    ),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    testResult == null -> "尚未测试"
                                    testResult.ok -> "连接正常 · ${relativeTime(testResult.testedAt)}"
                                    else -> "连接失败 · ${testResult.message.take(36)}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (source.type == SourceType.ZLIBRARY) {
                        TextButton(
                            onClick = onOpenLibrary,
                            enabled = source.enabled,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                        ) {
                            Text("书库", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Switch(
                        checked = source.enabled,
                        onCheckedChange = onToggleEnabled,
                        modifier = Modifier.semantics {
                            contentDescription = "${source.name} 源开关"
                        },
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = "更多操作",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (testing) "正在测试…" else "测试连接") },
                        leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                        enabled = !testing && source.enabled,
                        onClick = {
                            menuOpen = false
                            onTest()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("编辑") },
                        leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.error,
                            leadingIconColor = MaterialTheme.colorScheme.error,
                        ),
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

private fun sourceIcon(type: SourceType) = when (type) {
    SourceType.LOCAL -> Icons.Outlined.FolderOpen
    SourceType.WEBDAV -> Icons.Outlined.Cloud
    SourceType.KOMGA -> Icons.Outlined.Dns
    SourceType.KOOBONE -> Icons.Outlined.MenuBook
    SourceType.ZLIBRARY -> Icons.Outlined.LibraryBooks
}

private data class SourceTestResult(val ok: Boolean, val message: String)

private fun relativeTime(timestamp: Long): String {
    val diff = (System.currentTimeMillis() - timestamp).coerceAtLeast(0)
    return when {
        diff < 60_000L -> "刚刚"
        diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
        diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
        diff < 7 * 86_400_000L -> "${diff / 86_400_000L} 天前"
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun SourceConfig.locationLabel(): String = when (type) {
    SourceType.LOCAL -> treeUri
        ?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
        ?: "未选择文件夹"

    SourceType.WEBDAV -> buildString {
        append(listOfNotNull(baseUrl, rootPath.takeIf { it.isNotBlank() }).joinToString(" / "))
        if (!publicBaseUrl.isNullOrBlank()) append(" · 公网备用")
    }

    SourceType.KOMGA -> buildString {
        append(baseUrl.orEmpty())
        if (!publicBaseUrl.isNullOrBlank()) append(" · 公网备用")
    }
    SourceType.KOOBONE -> listOfNotNull(
        baseUrl?.takeIf { it.isNotBlank() } ?: "https://koobone.com",
        username?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    SourceType.ZLIBRARY -> listOfNotNull(
        baseUrl?.takeIf { it.isNotBlank() },
        username?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

@Composable
private fun AddSourceDialog(
    initial: SourceConfig? = null,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
    onTest: suspend (SourceConfig) -> Unit,
) {
    val context = LocalContext.current
    val isEditing = initial != null
    var type by remember { mutableStateOf(initial?.type) }
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var treeUri by remember { mutableStateOf(initial?.treeUri) }
    var treeLabel by remember {
        mutableStateOf(
            initial?.treeUri?.let { uri ->
                runCatching {
                    DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
                }.getOrNull() ?: Uri.parse(uri).lastPathSegment
            },
        )
    }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var publicBaseUrl by remember { mutableStateOf(initial?.publicBaseUrl.orEmpty()) }
    var rootPath by remember { mutableStateOf(initial?.rootPath.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var inlineResult by remember { mutableStateOf<SourceTestResult?>(null) }

    fun currentConfig(): SourceConfig? {
        val currentType = type ?: return null
        return SourceConfig(
            id = initial?.id ?: UUID.randomUUID().toString(),
            type = currentType,
            name = name.trim(),
            treeUri = treeUri,
            baseUrl = if (currentType == SourceType.KOOBONE) {
                baseUrl.trim().ifBlank { "https://koobone.com" }
            } else {
                baseUrl.trim().ifBlank { null }
            },
            publicBaseUrl = publicBaseUrl.trim().ifBlank { null },
            rootPath = rootPath.trim(),
            username = username.trim().ifBlank { null },
            password = password.ifBlank { null },
            apiKey = apiKey.trim().ifBlank { null },
            enabled = initial?.enabled ?: true,
        )
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            treeUri = uri.toString()
            treeLabel = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment
            if (name.isBlank()) name = treeLabel.orEmpty()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .imePadding(),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
            ) {
                val currentType = type
                if (currentType == null) {
                    Text("添加漫画源", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(16.dp))
                    SourceTypeOption(
                        icon = { Icon(Icons.Outlined.FolderOpen, contentDescription = null) },
                        title = "本地文件夹",
                        subtitle = "授权一个目录，支持 CBZ/ZIP、EPUB 和图片文件夹",
                        onClick = {
                            type = SourceType.LOCAL
                            if (name.isBlank()) name = "本地漫画"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    SourceTypeOption(
                        icon = { Icon(Icons.Outlined.Cloud, contentDescription = null) },
                        title = "WebDAV",
                        subtitle = "连接 NAS 的 WebDAV 目录，支持远程读取 CBZ",
                        onClick = {
                            type = SourceType.WEBDAV
                            if (name.isBlank()) name = "WebDAV"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    SourceTypeOption(
                        icon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
                        title = "Komga",
                        subtitle = "连接 Komga 服务器，自动同步阅读进度",
                        onClick = {
                            type = SourceType.KOMGA
                            if (name.isBlank()) name = "Komga"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    SourceTypeOption(
                        icon = { Icon(Icons.Outlined.MenuBook, contentDescription = null) },
                        title = "Koobone",
                        subtitle = "使用 Koobone 账号在线书架，按需缓存 EPUB 阅读",
                        onClick = {
                            type = SourceType.KOOBONE
                            if (name.isBlank()) name = "Koobone"
                            if (baseUrl.isBlank()) baseUrl = "https://koobone.com"
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    SourceTypeOption(
                        icon = { Icon(Icons.Outlined.LibraryBooks, contentDescription = null) },
                        title = "Z-Library",
                        subtitle = "个人账号搜索并下载 EPUB/PDF，阅读交给外部应用",
                        onClick = {
                            type = SourceType.ZLIBRARY
                            if (name.isBlank()) name = "Z-Library"
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                        Text("取消")
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (isEditing) onDismiss() else type = null }) {
                            Icon(
                                imageVector = if (isEditing) {
                                    Icons.Outlined.Close
                                } else {
                                    Icons.AutoMirrored.Outlined.ArrowBack
                                },
                                contentDescription = if (isEditing) "关闭" else "返回",
                            )
                        }
                        Text(
                            text = buildString {
                                if (isEditing) append("编辑 ") else append("添加 ")
                                append(
                                    when (currentType) {
                                        SourceType.LOCAL -> "本地文件夹"
                                        SourceType.WEBDAV -> "WebDAV"
                                        SourceType.KOMGA -> "Komga"
                                        SourceType.KOOBONE -> "Koobone"
                                        SourceType.ZLIBRARY -> "Z-Library"
                                    },
                                )
                            },
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))

                    when (currentType) {
                        SourceType.LOCAL -> {
                            OutlinedTextField(
                                value = treeLabel.orEmpty(),
                                onValueChange = {},
                                label = { Text("漫画文件夹") },
                                readOnly = true,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            TextButton(onClick = { folderPicker.launch(null) }) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (treeUri == null) "选择文件夹" else "重新选择")
                            }
                            Text(
                                text = "授权后 App 会持久保存读取权限。目录第一层会被识别为系列，散放的 CBZ/ZIP、EPUB 各自作为一本。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        SourceType.WEBDAV -> {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("内网地址") },
                                placeholder = { Text("https://nas.local:5006") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = publicBaseUrl,
                                onValueChange = { publicBaseUrl = it },
                                label = { Text("公网地址（可选）") },
                                placeholder = { Text("https://nas.example.com:5006") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "在家优先走内网；离开 Wi-Fi 或内网不可达时自动切公网。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = rootPath,
                                onValueChange = { rootPath = it },
                                label = { Text("漫画目录（可选）") },
                                placeholder = { Text("/comics") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            CredentialFields(
                                username = username,
                                onUsernameChange = { username = it },
                                password = password,
                                onPasswordChange = { password = it },
                            )
                        }

                        SourceType.KOMGA -> {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("内网地址") },
                                placeholder = { Text("http://192.168.1.10:25600") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = publicBaseUrl,
                                onValueChange = { publicBaseUrl = it },
                                label = { Text("公网地址（可选）") },
                                placeholder = { Text("https://komga.example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "在家优先走内网；离开 Wi-Fi 或内网不可达时自动切公网。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("API Key（推荐）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "也可以填写账号密码使用 Basic Auth。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            CredentialFields(
                                username = username,
                                onUsernameChange = { username = it },
                                password = password,
                                onPasswordChange = { password = it },
                            )
                        }

                        SourceType.KOOBONE -> {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("服务器地址") },
                                placeholder = { Text("https://koobone.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("邮箱") },
                                placeholder = { Text("name@example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = ComposePassword(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "账号密码仅加密保存在本机；漫画按需下载 EPUB 并缓存。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        SourceType.ZLIBRARY -> {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("服务器地址") },
                                placeholder = { Text("https://zh.zlibrary.by") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = username,
                                onValueChange = { username = it },
                                label = { Text("邮箱") },
                                placeholder = { Text("name@example.com") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                label = { Text("密码") },
                                singleLine = true,
                                visualTransformation = ComposePassword(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "仅使用你自己的账号。下载的 EPUB/PDF 保存到 Download/Comic，App 内不提供文字书阅读；域名变化时请更新服务器地址。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            enabled = !testing,
                            onClick = {
                                val config = currentConfig() ?: return@TextButton
                                testing = true
                                inlineResult = null
                                scope.launch {
                                    val result = try {
                                        runCatching { onTest(config) }.also {
                                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                        }
                                    } finally {
                                        testing = false
                                    }
                                    inlineResult = SourceTestResult(
                                        ok = result.isSuccess,
                                        message = result.fold(
                                            onSuccess = { "连接成功 · 刚刚" },
                                            onFailure = { (it.message ?: "连接失败").take(100) },
                                        ),
                                    )
                                }
                            },
                        ) {
                            if (testing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("测试中…")
                            } else {
                                Text("测试连接")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        inlineResult?.let {
                            Text(
                                text = it.message,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (it.ok) Color(0xFF3F8F62) else MaterialTheme.colorScheme.error,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1.4f),
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = isValid(currentType, name, treeUri, baseUrl, username, password),
                            onClick = { currentConfig()?.let(onSave) },
                        ) { Text("保存") }
                    }
                }
            }
        }
    }
}

@Composable
private fun CredentialFields(
    username: String,
    onUsernameChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = username,
        onValueChange = onUsernameChange,
        label = { Text("用户名（可选）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = password,
        onValueChange = onPasswordChange,
        label = { Text("密码") },
        singleLine = true,
        visualTransformation = ComposePassword(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SourceTypeOption(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Row(Modifier.padding(10.dp)) { icon() }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun isValid(
    type: SourceType,
    name: String,
    treeUri: String?,
    baseUrl: String,
    username: String,
    password: String,
): Boolean = when (type) {
    SourceType.LOCAL -> name.isNotBlank() && !treeUri.isNullOrBlank()
    SourceType.WEBDAV, SourceType.KOMGA -> name.isNotBlank() && baseUrl.isNotBlank()
    SourceType.KOOBONE -> name.isNotBlank() && username.isNotBlank() && password.isNotBlank()
    SourceType.ZLIBRARY -> name.isNotBlank() && baseUrl.isNotBlank() &&
        username.isNotBlank() && password.isNotBlank()
}
