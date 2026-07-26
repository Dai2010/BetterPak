package com.dai2010.betterpak.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import com.dai2010.betterpak.data.ArchiveDefaults
import com.dai2010.betterpak.data.ArchiveEngineProvider
import com.dai2010.betterpak.domain.ArchiveExtractOptions
import com.dai2010.betterpak.domain.ArchiveErrorClassifier
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveItem
import com.dai2010.betterpak.domain.ArchivePreview
import com.dai2010.betterpak.domain.ArchiveProgress
import com.dai2010.betterpak.domain.PreviewPolicy
import com.dai2010.betterpak.domain.OverwritePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val MAX_PREVIEW_IMAGE_DIMENSION = 8192
private const val MAX_PREVIEW_IMAGE_PIXELS = 20_000_000L

data class PreviewRequest(
    val archiveUri: Uri,
    val format: ArchiveFormat,
    val password: String,
    val extractOptions: ArchiveExtractOptions,
    val maxPreviewBytes: Long,
    val destinationUri: Uri?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSetupScreen(
    initialArchiveUri: Uri? = null,
    archiveDefaults: ArchiveDefaults = ArchiveDefaults(),
    onOpenPreview: (PreviewRequest) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var archiveUri by remember(initialArchiveUri) { mutableStateOf(initialArchiveUri) }
    var archiveFormat by remember(initialArchiveUri) {
        mutableStateOf(initialArchiveUri?.let { ArchiveEngineProvider.engine.detectFormat(context, it) } ?: ArchiveFormat.UNKNOWN)
    }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var overwritePolicy by rememberSaveable(archiveDefaults.overwritePolicy) {
        mutableStateOf(archiveDefaults.overwritePolicy.name)
    }
    var maxEntriesText by rememberSaveable(archiveDefaults.maxEntries) {
        mutableStateOf(archiveDefaults.maxEntries.toString())
    }
    var maxSizeGbText by rememberSaveable(archiveDefaults.maxExpandedBytes) {
        mutableStateOf((archiveDefaults.maxExpandedBytes / 1024.0 / 1024.0 / 1024.0).toString())
    }
    var extractionThreads by rememberSaveable { mutableIntStateOf(2) }
    var advancedExpanded by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var overwriteMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(initialArchiveUri) {
        initialArchiveUri?.let { ArchiveEngineProvider.engine.persistUriPermission(context, it) }
    }

    fun selectArchive(uri: Uri?) {
        val format = uri?.let { ArchiveEngineProvider.engine.detectFormat(context, it) } ?: ArchiveFormat.UNKNOWN
        archiveUri = uri
        archiveFormat = format
        if (!format.supportsPassword) password = ""
        status = when {
            uri == null -> null
            format == ArchiveFormat.UNKNOWN -> "无法识别格式，请选择 ZIP、RAR、7z、TAR 或 Zstandard 文件"
            else -> "已选择 ${format.label}，完成设置后开始预览"
        }
    }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ArchiveEngineProvider.engine.persistUriPermission(context, it) }
        selectArchive(uri)
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveEngineProvider.engine.persistUriPermission(context, it) }
        destinationUri = uri
    }

    Scaffold(topBar = { PreviewBackTopBar("预览设置", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("先完成设置，再打开预览", style = MaterialTheme.typography.headlineSmall)
            Text(
                "预览界面只负责浏览和显示文件，不会在预览过程中修改设置。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { pickArchive.launch(ArchiveEngineProvider.engine.supportedArchiveMimeTypes()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (archiveUri == null) "选择 ZIP、RAR、7z、TAR 或 Zstandard 文件" else "重新选择压缩包")
            }
            archiveUri?.let {
                AssistChip(
                    onClick = {},
                    label = { Text("格式：${archiveFormat.label}") },
                    leadingIcon = { Icon(Icons.Outlined.CheckCircle, contentDescription = null) },
                )
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { advancedExpanded = !advancedExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                            contentDescription = null,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("高级选项（预览前设置）")
                    }
                    if (advancedExpanded) {
                        if (archiveFormat.supportsPassword) {
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("密码（可选）") },
                                visualTransformation = if (passwordVisible) {
                                    VisualTransformation.None
                                } else {
                                    PasswordVisualTransformation()
                                },
                                trailingIcon = {
                                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(if (passwordVisible) "隐藏" else "显示")
                                    }
                                },
                                supportingText = { Text("${archiveFormat.label} 支持密码读取") },
                            )
                        } else if (archiveUri != null) {
                            Text(
                                if (archiveFormat == ArchiveFormat.ZIP) {
                                    "ZIP 不支持密码读取，不需要设置密码。"
                                } else {
                                    "当前格式不支持密码读取。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text("已有文件处理：", style = MaterialTheme.typography.labelLarge)
                        Box {
                            OutlinedButton(onClick = { overwriteMenuExpanded = true }) {
                                Text(OverwritePolicy.valueOf(overwritePolicy).label)
                            }
                            DropdownMenu(
                                expanded = overwriteMenuExpanded,
                                onDismissRequest = { overwriteMenuExpanded = false },
                            ) {
                                OverwritePolicy.entries.forEach { policy ->
                                    DropdownMenuItem(
                                        text = { Text(policy.label) },
                                        onClick = {
                                            overwritePolicy = policy.name
                                            overwriteMenuExpanded = false
                                        },
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = maxEntriesText,
                            onValueChange = { maxEntriesText = it.filter(Char::isDigit) },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("最多处理文件数") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = maxSizeGbText,
                            onValueChange = { maxSizeGbText = it.filter { char -> char.isDigit() || char == '.' } },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("最大展开体积（GB）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        Text("解压任务线程：$extractionThreads", style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = extractionThreads.toFloat(),
                            onValueChange = { extractionThreads = it.roundToInt().coerceIn(1, 8) },
                            valueRange = 1f..8f,
                            steps = 6,
                        )
                        Text(
                            "路径安全保护始终开启，预览不会执行压缩包中的内容。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedButton(onClick = { pickDestination.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (destinationUri == null) "选择解压目标目录（可选）" else "已选择解压目标目录")
            }
            if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    val uri = archiveUri
                    when {
                        uri == null -> status = "请先选择压缩包"
                        archiveFormat == ArchiveFormat.UNKNOWN -> status = "请选择 ZIP、RAR、7z、TAR 或 Zstandard 文件"
                        else -> onOpenPreview(
                            PreviewRequest(
                                archiveUri = uri,
                                format = archiveFormat,
                                password = if (archiveFormat.supportsPassword) password else "",
                                extractOptions = ArchiveExtractOptions(
                                    password = if (archiveFormat.supportsPassword) password else "",
                                    overwritePolicy = OverwritePolicy.valueOf(overwritePolicy),
                                    maxEntries = maxEntriesText.toIntOrNull()?.coerceAtLeast(1) ?: 100000,
                                    maxExpandedBytes = (
                                        (
                                            maxSizeGbText.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 50.0
                                        ) * 1024 * 1024 * 1024
                                    ).toLong(),
                                    threads = extractionThreads,
                                ),
                                maxPreviewBytes = archiveDefaults.maxPreviewBytes,
                                destinationUri = destinationUri,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Visibility, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("开始预览")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PreviewBrowserScreen(
    request: PreviewRequest,
    taskViewModel: ArchiveTaskViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(request) { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var selectedPaths by remember(request) { mutableStateOf<Set<String>>(emptySet()) }
    var selectionMode by remember(request) { mutableStateOf(false) }
    var destinationUri by remember(request) { mutableStateOf(request.destinationUri) }
    var loading by remember(request) { mutableStateOf(true) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<ArchivePreview?>(null) }
    var previewErrorPath by remember { mutableStateOf<String?>(null) }
    var fallbackPath by remember { mutableStateOf<String?>(null) }
    var fallbackError by remember { mutableStateOf<String?>(null) }
    var pendingExtractPath by remember { mutableStateOf<String?>(null) }
    var fallbackJob by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }

    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveEngineProvider.engine.persistUriPermission(context, it) }
        destinationUri = uri
        val path = pendingExtractPath
        if (uri != null && path != null) {
            pendingExtractPath = null
            previewItem = null
            previewErrorPath = null
            operationJob = startPreviewExtraction(
                context = context,
                scope = scope,
                request = request,
                taskViewModel = taskViewModel,
                destinationUri = uri,
                selectedPaths = setOf(path),
                allFiles = false,
                setBusy = { busy = it },
                setStatus = { status = it },
                setProgress = { progress = it },
            )
        }
    }

    fun openExtractedFile(file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, ArchiveEngineProvider.engine.mimeTypeForPath(file.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(Intent.createChooser(intent, "选择应用打开"))
            status = "已请求系统应用打开 ${file.name}"
        } catch (_: ActivityNotFoundException) {
            status = "文件已准备到应用缓存，但没有可打开此类型的应用"
        }
    }

    fun openExternalFile(path: String) {
        fallbackPath = path
        fallbackError = null
        previewErrorPath = null
        previewLoading = true
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            try {
                val result = ArchiveEngineProvider.engine.extractEntryToCache(
                    context = context,
                    archiveUri = request.archiveUri,
                    path = path,
                    password = request.password,
                    maxBytes = PreviewPolicy.cacheLimitFor(
                        path,
                        request.extractOptions.maxExpandedBytes,
                    ),
                )
                result.fold(
                    onSuccess = { file ->
                        openExtractedFile(file)
                        fallbackPath = null
                        previewLoading = false
                    },
                    onFailure = { error ->
                        fallbackError = error.message ?: "无法准备外部打开文件"
                        previewErrorPath = path
                        fallbackPath = null
                        previewLoading = false
                    },
                )
            } finally {
                ArchiveEngineProvider.engine.cleanupTemporaryFiles(context)
                fallbackJob = null
            }
        }
    }

    LaunchedEffect(request) {
        ArchiveEngineProvider.engine.initializeAppStorage(context)
        val result = ArchiveEngineProvider.engine.list(context, request.archiveUri, request.password)
        loading = false
        result.fold(
            onSuccess = { loaded ->
                items = loaded
                status = "已读取 ${loaded.size} 个条目"
            },
            onFailure = { error ->
                status = "读取失败：${error.message ?: "密码错误或文件损坏"}"
            },
        )
    }

    fun openPreview(item: ArchiveItem) {
        if (item.isDirectory) {
            status = "目录：${item.path}"
            return
        }
        previewLoading = true
        fallbackPath = item.path
        status = "正在预览 ${item.path}…"
        scope.launch {
            val result = ArchiveEngineProvider.engine.preview(
                context = context,
                uri = request.archiveUri,
                path = item.path,
                password = request.password,
                maxBytes = request.maxPreviewBytes,
            )
            result.fold(
                onSuccess = {
                    previewItem = it
                    previewErrorPath = null
                    fallbackPath = null
                    fallbackError = null
                    previewLoading = false
                    status = null
                },
                onFailure = { error ->
                    previewItem = null
                    previewErrorPath = item.path
                    fallbackPath = null
                    fallbackError = error.message
                    previewLoading = false
                    status = null
                },
            )
        }
    }

    fun toggleSelection(path: String) {
        selectedPaths = if (selectedPaths.contains(path)) selectedPaths - path else selectedPaths + path
    }

    fun extractPath(path: String) {
        if (destinationUri == null) {
            pendingExtractPath = path
            pickDestination.launch(null)
            return
        }
        previewItem = null
        previewErrorPath = null
        operationJob = startPreviewExtraction(
            context = context,
            scope = scope,
            request = request,
            taskViewModel = taskViewModel,
            destinationUri = destinationUri,
            selectedPaths = setOf(path),
            allFiles = false,
            setBusy = { busy = it },
            setStatus = { status = it },
            setProgress = { progress = it },
        )
    }

    if (previewItem != null || previewErrorPath != null || fallbackPath != null) {
        PreviewDetailScreen(
            item = previewItem,
            errorPath = previewErrorPath,
            fallbackPath = fallbackPath,
            fallbackLoading = fallbackPath != null && previewLoading,
            fallbackError = fallbackError,
            onBack = {
                fallbackJob?.cancel()
                previewItem = null
                previewErrorPath = null
                fallbackPath = null
                fallbackError = null
                previewLoading = false
            },
            onExtract = { path -> extractPath(path) },
            onFallback = { path -> openExternalFile(path) },
        )
        return
    }

    Scaffold(topBar = { PreviewBackTopBar("预览 ${request.format.label}", onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("压缩包内容", style = MaterialTheme.typography.headlineSmall)
                Text(
                    if (selectionMode) {
                        "已进入选择模式，点击文件切换选择；点击完成返回浏览。"
                    } else {
                        "点击文件打开独立预览界面；长按文件进入选择模式。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AssistChip(
                    onClick = {},
                    label = { Text("格式：${request.format.label}") },
                    leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                )
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall)
                if (previewLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                if (busy) {
                    PreviewStatusCard(
                        message = status ?: "正在解压…",
                        progress = progress?.fraction,
                        onCancel = { operationJob?.cancel() },
                    )
                }
            }
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        if (selectionMode) "已选择 ${selectedPaths.size} 项" else "${items.size} 个条目",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectionMode) {
                            TextButton(onClick = {
                                val filePaths = items.filterNot { it.isDirectory }.map { it.path }.toSet()
                                selectedPaths = if (selectedPaths == filePaths) emptySet() else filePaths
                            }) {
                                val fileCount = items.count { !it.isDirectory }
                                Text(if (selectedPaths.size == fileCount) "取消全选" else "全选")
                            }
                            TextButton(onClick = {
                                selectionMode = false
                                selectedPaths = emptySet()
                            }) {
                                Text("完成")
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(
                    items = items.sortedWith(compareBy<ArchiveItem>({ !it.isDirectory }, { it.path.lowercase() })),
                    key = { it.path },
                ) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = {
                                    if (selectionMode) toggleSelection(item.path) else openPreview(item)
                                },
                                onLongClick = {
                                    if (!selectionMode) {
                                        selectionMode = true
                                        selectedPaths = setOf(item.path)
                                    } else {
                                        toggleSelection(item.path)
                                    }
                                },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = selectedPaths.contains(item.path),
                                onCheckedChange = { toggleSelection(item.path) },
                                enabled = !busy,
                            )
                        }
                        PreviewFileIcon(item.path, item.isDirectory)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.path.substringAfterLast('/'), maxLines = 1)
                            Text(
                                if (item.isDirectory) {
                                    item.path.substringBeforeLast('/', "压缩包根目录")
                                } else {
                                    "${previewFormatSize(item.size)} · ${item.path.substringBeforeLast('/', "根目录")}"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Outlined.Visibility,
                            contentDescription = if (item.isDirectory) null else "预览 ${item.path}",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (items.isNotEmpty()) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { pickDestination.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (destinationUri == null) "选择解压目标目录" else "已选择目标目录")
                    }
                    if (selectionMode) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                operationJob = startPreviewExtraction(
                                    context,
                                    scope,
                                    request,
                                    taskViewModel,
                                    destinationUri,
                                    selectedPaths,
                                    allFiles = false,
                                    setBusy = { busy = it },
                                    setStatus = { status = it },
                                    setProgress = { progress = it },
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy && selectedPaths.isNotEmpty(),
                        ) { Text("解压选中") }
                        Button(
                            onClick = {
                                operationJob = startPreviewExtraction(
                                    context,
                                    scope,
                                    request,
                                    taskViewModel,
                                    destinationUri,
                                    emptySet(),
                                    allFiles = true,
                                    setBusy = { busy = it },
                                    setStatus = { status = it },
                                    setProgress = { progress = it },
                                )
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                        ) { Text("解压全部") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewFileIcon(path: String, isDirectory: Boolean) {
    val mimeType = ArchiveEngineProvider.engine.mimeTypeForPath(path)
    val icon = when {
        isDirectory -> Icons.Outlined.Folder
        mimeType.startsWith("image/") -> Icons.Outlined.Image
        mimeType.startsWith("audio/") -> Icons.Outlined.MusicNote
        mimeType.startsWith("video/") -> Icons.Outlined.Movie
        else -> Icons.Outlined.Description
    }
    Icon(
        icon,
        contentDescription = if (isDirectory) "目录" else "文件",
        modifier = Modifier.size(40.dp),
        tint = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewDetailScreen(
    item: ArchivePreview?,
    errorPath: String?,
    fallbackPath: String?,
    fallbackLoading: Boolean,
    fallbackError: String?,
    onBack: () -> Unit,
    onExtract: (String) -> Unit,
    onFallback: (String) -> Unit,
) {
    val displayPath = item?.path ?: errorPath ?: fallbackPath
    var fallbackRequested by remember(displayPath) { mutableStateOf(false) }

    fun requestFallback() {
        if (!fallbackRequested) fallbackRequested = true
    }

    Scaffold(topBar = { PreviewBackTopBar("文件预览", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (displayPath != null) {
                    PreviewFileIcon(displayPath, isDirectory = false)
                    Text(displayPath, style = MaterialTheme.typography.titleLarge)
                }
                if (item != null) {
                    Text(
                        when {
                            item.text != null -> "文本文件"
                            item.mimeType.startsWith("image/") -> "图片文件"
                            item.mimeType.startsWith("audio/") -> "音频文件"
                            item.mimeType.startsWith("video/") -> "视频文件"
                            else -> item.mimeType
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when {
                        item.text != null -> Text(
                            item.text,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(420.dp)
                                .verticalScroll(rememberScrollState()),
                        )
                        item.mimeType.startsWith("audio/") || item.mimeType.startsWith("video/") -> {
                            PreviewMediaContent(item, onPlaybackError = ::requestFallback)
                        }
                        item.bytes != null -> {
                            val bitmap = remember(item.bytes) { decodePreviewBitmap(item.bytes) }
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = item.path,
                                    modifier = Modifier.fillMaxWidth().height(420.dp),
                                )
                            } else {
                                LaunchedEffect(item.path) { requestFallback() }
                                Text("图片无法在应用内解码，请选择外部应用打开或解压。")
                            }
                        }
                        else -> {
                            Text("该文件无法在应用内预览，请选择解压或外部应用打开。")
                        }
                    }
                    if (fallbackRequested) {
                        Text("应用内处理失败；BetterPak 不执行宏、脚本或嵌套归档。")
                    }
                } else if (fallbackLoading) {
                    Text("无法在应用内预览", style = MaterialTheme.typography.headlineSmall)
                    Text("正在准备文件并等待系统应用打开…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (errorPath != null) {
                    Text("无法预览此文件", style = MaterialTheme.typography.headlineSmall)
                    Text("BetterPak 不在应用内执行办公文档、宏、脚本或嵌套归档。请明确选择解压或交给系统应用。")
                    if (fallbackError != null) {
                        Text(fallbackError, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (displayPath != null) {
                if (fallbackRequested || errorPath != null) {
                    OutlinedButton(
                        onClick = { onFallback(displayPath) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !fallbackLoading,
                    ) {
                        Text("解压到缓存并选择其他应用打开")
                    }
                }
                Button(
                    onClick = { onExtract(displayPath) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !fallbackLoading,
                ) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("解压到…")
                }
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回文件列表") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewBackTopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
        },
    )
}

@Composable
private fun PreviewStatusCard(
    message: String,
    progress: Float?,
    onCancel: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(message, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("取消") }
            }
            if (progress != null && progress > 0f) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PreviewMediaContent(item: ArchivePreview, onPlaybackError: () -> Unit) {
    val context = LocalContext.current
    var mediaFile by remember(item.path) { mutableStateOf<File?>(null) }
    var materializeError by remember(item.path) { mutableStateOf<String?>(null) }

    DisposableEffect(item.path, item.mimeType, item.bytes) {
        var temporaryFile: File? = null
        val fileResult = runCatching {
            temporaryFile = File.createTempFile("betterpak-preview-", ".media", context.cacheDir)
            temporaryFile!!.outputStream().use { output -> output.write(item.bytes ?: error("预览内容为空")) }
            temporaryFile!!
        }.onFailure {
            temporaryFile?.delete()
        }
        mediaFile = fileResult.getOrNull()
        materializeError = fileResult.exceptionOrNull()?.message
        onDispose {
            fileResult.getOrNull()?.delete()
            ArchiveEngineProvider.engine.cleanupTemporaryFiles(context)
        }
    }

    LaunchedEffect(materializeError) {
        if (materializeError != null) onPlaybackError()
    }

    when {
        materializeError != null -> Text("媒体文件准备失败：$materializeError\n请解压后使用其他播放器打开。")
        mediaFile == null -> Text("正在准备媒体预览…")
        item.mimeType.startsWith("audio/") -> PreviewAudio(mediaFile!!, onPlaybackError)
        else -> PreviewVideo(mediaFile!!, onPlaybackError)
    }
}

@Composable
private fun PreviewAudio(file: File, onPlaybackError: () -> Unit) {
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableIntStateOf(0) }
    var speed by remember(file) { mutableStateOf(1.0f) }
    var speedMenuExpanded by remember(file) { mutableStateOf(false) }
    val player = remember(file) { MediaPlayer() }
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    DisposableEffect(file) {
        player.setOnPreparedListener {
            prepared = true
            duration = player.duration.coerceAtLeast(0)
        }
        player.setOnCompletionListener {
            playing = false
            position = 0
        }
        player.setOnErrorListener { _, what, extra ->
            playbackError = "播放器不支持此音频（$what/$extra）"
            playing = false
            currentOnPlaybackError()
            true
        }
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }.onFailure { error ->
            playbackError = error.message ?: "播放器初始化失败"
            currentOnPlaybackError()
        }
        onDispose {
            runCatching { player.stop() }
            player.release()
        }
    }

    LaunchedEffect(prepared, playing) {
        while (prepared) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            if (!playing) break
            delay(250)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (playbackError != null) {
            Text("$playbackError\n请解压后使用其他播放器打开。")
        } else {
            Text("音频预览")
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { value ->
                    position = (value * duration).roundToInt()
                    if (prepared) runCatching { player.seekTo(position) }
                },
                enabled = prepared && duration > 0,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        runCatching {
                            if (playing) player.pause() else player.start()
                            playing = !playing
                        }.onFailure { error ->
                            playbackError = error.message ?: "音频播放失败"
                            currentOnPlaybackError()
                        }
                    },
                    enabled = prepared,
                ) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "暂停" else if (prepared) "播放" else "准备中…")
                }
                Spacer(Modifier.width(10.dp))
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }, enabled = prepared) {
                        Icon(Icons.Outlined.Speed, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("${speed}x")
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false },
                    ) {
                        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { value ->
                            DropdownMenuItem(
                                text = { Text("${value}x") },
                                onClick = {
                                    runCatching { player.playbackParams = player.playbackParams.setSpeed(value) }
                                    speed = value
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewVideo(file: File, onPlaybackError: () -> Unit) {
    var videoPlayerView by remember(file) { mutableStateOf<VideoView?>(null) }
    var preparedMediaPlayer by remember(file) { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var duration by remember(file) { mutableIntStateOf(0) }
    var position by remember(file) { mutableIntStateOf(0) }
    var speed by remember(file) { mutableStateOf(1.0f) }
    var speedMenuExpanded by remember(file) { mutableStateOf(false) }
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    val currentOnPlaybackError by rememberUpdatedState(onPlaybackError)

    DisposableEffect(file) {
        onDispose { runCatching { videoPlayerView?.stopPlayback() } }
    }

    LaunchedEffect(prepared, playing) {
        while (prepared) {
            position = runCatching { videoPlayerView?.currentPosition ?: position }.getOrDefault(position)
            if (!playing) break
            delay(250)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            factory = { videoContext ->
                val videoView = VideoView(videoContext)
                videoView.setOnPreparedListener { mediaPlayer ->
                    preparedMediaPlayer = mediaPlayer
                    prepared = true
                    duration = videoView.duration.coerceAtLeast(0)
                }
                videoView.setOnErrorListener { _, what, extra ->
                    playbackError = "播放器不支持此视频（$what/$extra）"
                    currentOnPlaybackError()
                    true
                }
                videoView.setOnCompletionListener {
                    playing = false
                    position = 0
                }
                videoPlayerView = videoView
                videoView.setVideoPath(file.absolutePath)
                videoView
            },
            update = { videoPlayerView = it },
        )
        if (playbackError != null) {
            Text("$playbackError\n请解压后使用其他播放器打开。")
        } else {
            Slider(
                value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { value ->
                    position = (value * duration).roundToInt()
                    videoPlayerView?.seekTo(position)
                },
                enabled = prepared && duration > 0,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        runCatching {
                            val view = videoPlayerView ?: error("视频播放器未准备好")
                            if (playing) view.pause() else view.start()
                            playing = !playing
                        }.onFailure { error ->
                            playbackError = error.message ?: "视频播放失败"
                            currentOnPlaybackError()
                        }
                    },
                    enabled = prepared,
                ) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (playing) "暂停" else if (prepared) "播放" else "准备中…")
                }
                Spacer(Modifier.width(10.dp))
                Box {
                    TextButton(onClick = { speedMenuExpanded = true }, enabled = prepared) {
                        Icon(Icons.Outlined.Speed, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("${speed}x")
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { speedMenuExpanded = false },
                    ) {
                        listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { value ->
                            DropdownMenuItem(
                                text = { Text("${value}x") },
                                onClick = {
                                    runCatching {
                                        preparedMediaPlayer?.let { player ->
                                            player.playbackParams = player.playbackParams.setSpeed(value)
                                        }
                                    }
                                    speed = value
                                    speedMenuExpanded = false
                                },
                            )
                        }
                    }
                }
                Text(
                    "${formatPlaybackTime(position)} / ${formatPlaybackTime(duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun startPreviewExtraction(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    request: PreviewRequest,
    taskViewModel: ArchiveTaskViewModel,
    destinationUri: Uri?,
    selectedPaths: Set<String>,
    allFiles: Boolean,
    setBusy: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setProgress: (ArchiveProgress?) -> Unit,
): Job? {
    if (destinationUri == null) {
        setStatus("请选择解压目标目录")
        return null
    }
    if (!allFiles && selectedPaths.isEmpty()) {
        setStatus("请至少选择一个文件")
        return null
    }
    setBusy(true)
    setProgress(null)
    setStatus(if (allFiles) "正在解压当前页面全部文件…" else "正在解压选中文件…")
    val task = taskViewModel.enqueue(
        kind = com.dai2010.betterpak.domain.ArchiveTaskKind.EXTRACT,
        sourceUri = request.archiveUri.toString(),
        targetUri = destinationUri.toString(),
        format = request.format,
    )
    taskViewModel.start(task.id)
    return scope.launch {
        try {
            val result = ArchiveEngineProvider.engine.extract(
                context = context,
                archiveUri = request.archiveUri,
                destinationUri = destinationUri,
                selectedPaths = if (allFiles) null else selectedPaths,
                options = request.extractOptions,
                onProgress = {
                    setProgress(it)
                    taskViewModel.updateProgress(
                        task.id,
                        "${it.processedEntries}/${it.totalEntries} entries, ${it.processedBytes} bytes",
                    )
                },
            )
            setStatus(
                result.fold(
                    {
                        taskViewModel.complete(task.id)
                        "解压完成，共处理 $it 个文件"
                    },
                    {
                        taskViewModel.fail(task.id, ArchiveErrorClassifier.classify(it))
                        "解压失败：${it.message ?: "未知错误"}"
                    },
                ),
            )
        } catch (_: CancellationException) {
            taskViewModel.cancel(task.id)
            setStatus("已取消解压")
        } finally {
            setBusy(false)
            ArchiveEngineProvider.engine.cleanupTemporaryFiles(context)
        }
    }
}

private fun previewFormatSize(size: Long): String {
    if (size < 0) return "大小未知"
    if (size < 1024) return "$size B"
    if (size < 1024 * 1024) return "%.1f KB".format(size / 1024.0)
    if (size < 1024L * 1024L * 1024L) return "%.1f MB".format(size / (1024.0 * 1024.0))
    return "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}

private fun decodePreviewBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (
        width <= 0 ||
        height <= 0 ||
        width > MAX_PREVIEW_IMAGE_DIMENSION ||
        height > MAX_PREVIEW_IMAGE_DIMENSION ||
        width.toLong() * height.toLong() > MAX_PREVIEW_IMAGE_PIXELS
    ) {
        return null
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}

private fun formatPlaybackTime(milliseconds: Int): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
