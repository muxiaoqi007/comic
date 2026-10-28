package com.example.comic.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation as ComposePassword
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.platform.LocalContext
import com.example.comic.model.SourceConfig
import com.example.comic.model.SourceType
import com.example.comic.ui.LocalContainer
import com.example.comic.ui.components.EmptyState
import com.example.comic.ui.components.SourceTypeBadge
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen() {
    val container = LocalContainer.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }
    val repository = container.repository
    val sources by repository.sources.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SourceConfig?>(null) }
    var pendingDelete by remember { mutableStateOf<SourceConfig?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }

    fun test(config: SourceConfig) {
        if (testingId != null) return
        testingId = config.id
        scope.launch {
            val result = runCatching { repository.testConnection(config) }
            testingId = null
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
                        Text("源管理", fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "v${versionName ?: "?"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text("添加源") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (sources.isEmpty()) {
            EmptyState(
                title = "还没有漫画源",
                description = "支持本地文件夹、NAS 上的 WebDAV 目录、Komga 服务器，以及 Koobone 账号。",
                modifier = Modifier.padding(padding),
                actionLabel = "添加漫画源",
                onAction = { showAdd = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sources, key = { it.id }) { source ->
                    SourceCard(
                        source = source,
                        testing = testingId == source.id,
                        onTest = { test(source) },
                        onEdit = { editing = source },
                        onDelete = { pendingDelete = source },
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
        )
    }

    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除漫画源") },
            text = { Text("确定删除「${source.name}」吗？阅读进度会保留在本地。") },
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
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = source.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                SourceTypeBadge(source.type)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = source.locationLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onTest, enabled = !testing) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("测试中…")
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("测试连接")
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(
                        Icons.Outlined.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun SourceConfig.locationLabel(): String = when (type) {
    SourceType.LOCAL -> treeUri
        ?.let { runCatching { Uri.parse(it).lastPathSegment }.getOrNull() }
        ?: "未选择文件夹"

    SourceType.WEBDAV -> listOfNotNull(baseUrl, rootPath.takeIf { it.isNotBlank() }).joinToString(" / ")
    SourceType.KOMGA -> baseUrl.orEmpty()
    SourceType.KOOBONE -> listOfNotNull(
        baseUrl?.takeIf { it.isNotBlank() } ?: "https://koobone.com",
        username?.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

@Composable
private fun AddSourceDialog(
    initial: SourceConfig? = null,
    onDismiss: () -> Unit,
    onSave: (SourceConfig) -> Unit,
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
    var rootPath by remember { mutableStateOf(initial?.rootPath.orEmpty()) }
    var username by remember { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember { mutableStateOf(initial?.password.orEmpty()) }
    var apiKey by remember { mutableStateOf(initial?.apiKey.orEmpty()) }

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
                        subtitle = "授权一个目录，支持 CBZ/ZIP 压缩包和图片文件夹",
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
                                text = "授权后 App 会持久保存读取权限。目录第一层会被识别为系列。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        SourceType.WEBDAV -> {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("服务器地址") },
                                placeholder = { Text("https://nas.local:5006") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
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
                                label = { Text("服务器地址") },
                                placeholder = { Text("http://192.168.1.10:25600") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                modifier = Modifier.fillMaxWidth(),
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
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                        TextButton(
                            enabled = isValid(currentType, name, treeUri, baseUrl, username, password),
                            onClick = {
                                onSave(
                                    SourceConfig(
                                        id = initial?.id ?: UUID.randomUUID().toString(),
                                        type = currentType,
                                        name = name.trim(),
                                        treeUri = treeUri,
                                        baseUrl = if (currentType == SourceType.KOOBONE) {
                                            baseUrl.trim().ifBlank { "https://koobone.com" }
                                        } else {
                                            baseUrl.trim().ifBlank { null }
                                        },
                                        rootPath = rootPath.trim(),
                                        username = username.trim().ifBlank { null },
                                        password = password.ifBlank { null },
                                        apiKey = apiKey.trim().ifBlank { null },
                                    ),
                                )
                            },
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
}
