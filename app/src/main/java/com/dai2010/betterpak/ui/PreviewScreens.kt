package com.dai2010.betterpak.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
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
import com.dai2010.betterpak.data.ArchiveRepository
import com.dai2010.betterpak.domain.ArchiveExtractOptions
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveItem
import com.dai2010.betterpak.domain.ArchivePreview
import com.dai2010.betterpak.domain.ArchiveProgress
import com.dai2010.betterpak.domain.OverwritePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

data class PreviewRequest(
    val archiveUri: Uri,
    val format: ArchiveFormat,
    val password: String,
    val extractOptions: ArchiveExtractOptions,
    val destinationUri: Uri?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSetupScreen(
    initialArchiveUri: Uri? = null,
    onOpenPreview: (PreviewRequest) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var archiveUri by remember(initialArchiveUri) { mutableStateOf(initialArchiveUri) }
    var archiveFormat by remember(initialArchiveUri) {
        mutableStateOf(initialArchiveUri?.let { ArchiveRepository.detectFormat(context, it) } ?: ArchiveFormat.UNKNOWN)
    }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var overwritePolicy by rememberSaveable { mutableStateOf(OverwritePolicy.REPLACE.name) }
    var maxEntriesText by rememberSaveable { mutableStateOf("100000") }
    var maxSizeGbText by rememberSaveable { mutableStateOf("50") }
    var extractionThreads by rememberSaveable { mutableIntStateOf(2) }
    var advancedExpanded by rememberSaveable { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var overwriteMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(initialArchiveUri) {
        initialArchiveUri?.let { ArchiveRepository.persistUriPermission(context, it) }
    }

    fun selectArchive(uri: Uri?) {
        val format = uri?.let { ArchiveRepository.detectFormat(context, it) } ?: ArchiveFormat.UNKNOWN
        archiveUri = uri
        archiveFormat = format
        if (!format.supportsPassword) password = ""
        status = when {
            uri == null -> null
            format == ArchiveFormat.UNKNOWN -> "无法识别格式，请选择 ZIP、RAR 或 7z 文件"
            else -> "已选择 ${format.label}，完成设置后开始预览"
        }
    }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        selectArchive(uri)
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
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
                onClick = { pickArchive.launch(ArchiveRepository.supportedArchiveMimeTypes()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (archiveUri == null) "选择 ZIP、RAR 或 7z 压缩包" else "重新选择压缩包")
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
                        archiveFormat == ArchiveFormat.UNKNOWN -> status = "请选择 ZIP、RAR 或 7z 压缩包"
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewBrowserScreen(request: PreviewRequest, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(request) { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var selectedPaths by remember(request) { mutableStateOf<Set<String>>(emptySet()) }
    var destinationUri by remember(request) { mutableStateOf(request.destinationUri) }
    var loading by remember(request) { mutableStateOf(true) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewItem by remember { mutableStateOf<ArchivePreview?>(null) }
    var previewErrorPath by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }

    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        destinationUri = uri
    }

    LaunchedEffect(request) {
        val result = ArchiveRepository.list(context, request.archiveUri, request.password)
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
        previewLoading = true
        status = "正在预览 ${item.path}…"
        scope.launch {
            val result = ArchiveRepository.preview(context, request.archiveUri, item.path, request.password)
            previewLoading = false
            result.fold(
                onSuccess = {
                    previewItem = it
                    previewErrorPath = null
                    status = null
                },
                onFailure = {
                    previewItem = null
                    previewErrorPath = item.path
                    status = null
                },
            )
        }
    }

    fun extractPath(path: String) {
        if (destinationUri == null) {
            status = "请先选择解压目标目录"
            previewItem = null
            previewErrorPath = null
            return
        }
        previewItem = null
        previewErrorPath = null
        operationJob = startPreviewExtraction(
            context = context,
            scope = scope,
            request = request,
            destinationUri = destinationUri,
            selectedPaths = setOf(path),
            allFiles = false,
            setBusy = { busy = it },
            setStatus = { status = it },
            setProgress = { progress = it },
        )
    }

    if (previewItem != null || previewErrorPath != null) {
        PreviewDetailScreen(
            item = previewItem,
            errorPath = previewErrorPath,
            onBack = {
                previewItem = null
                previewErrorPath = null
            },
            onExtract = { path -> extractPath(path) },
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
                    "点击文件右侧的预览图标打开独立预览界面；勾选文件仅用于解压。",
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
                    Text("${items.size} 个条目", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        val filePaths = items.filterNot { it.isDirectory }.map { it.path }.toSet()
                        selectedPaths = if (selectedPaths == filePaths) emptySet() else filePaths
                    }) {
                        val fileCount = items.count { !it.isDirectory }
                        Text(if (selectedPaths.size == fileCount) "取消全选" else "全选文件")
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = items, key = { it.path }) { item ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedPaths.contains(item.path),
                            onCheckedChange = { checked ->
                                selectedPaths = if (checked) selectedPaths + item.path else selectedPaths - item.path
                            },
                            enabled = !item.isDirectory && !busy,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.path, maxLines = 2)
                            Text(
                                if (item.isDirectory) "目录" else previewFormatSize(item.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!item.isDirectory) {
                            IconButton(
                                onClick = { openPreview(item) },
                                enabled = !previewLoading && !busy,
                            ) {
                                Icon(
                                    Icons.Outlined.Visibility,
                                    contentDescription = "预览 ${item.path}",
                                )
                            }
                        }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = {
                                operationJob = startPreviewExtraction(
                                    context,
                                    scope,
                                    request,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewDetailScreen(
    item: ArchivePreview?,
    errorPath: String?,
    onBack: () -> Unit,
    onExtract: (String) -> Unit,
) {
    Scaffold(topBar = { PreviewBackTopBar("文件预览", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (item != null) {
                Text(item.path, style = MaterialTheme.typography.titleLarge)
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
                        PreviewMediaContent(item)
                    }
                    item.bytes != null -> {
                        val bitmap = remember(item.bytes) {
                            BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = item.path,
                                modifier = Modifier.fillMaxWidth().height(420.dp),
                            )
                        } else {
                            Text("图片无法解码，请选择解压")
                        }
                    }
                    else -> Text("该文件没有可显示的预览内容")
                }
                Button(onClick = { onExtract(item.path) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("解压此文件")
                }
            } else if (errorPath != null) {
                Text("无法预览此文件", style = MaterialTheme.typography.headlineSmall)
                Text(errorPath)
                Text("当前版本无法安全预览此类型，请选择解压查看原文件。")
                Button(onClick = { onExtract(errorPath) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("解压此文件")
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
private fun PreviewMediaContent(item: ArchivePreview) {
    val context = LocalContext.current
    var mediaFile by remember(item.path) { mutableStateOf<File?>(null) }
    var materializeError by remember(item.path) { mutableStateOf<String?>(null) }

    DisposableEffect(item.path, item.mimeType, item.bytes) {
        val fileResult = runCatching {
            val file = File.createTempFile("betterpak-preview-", ".media", context.cacheDir)
            file.outputStream().use { output -> output.write(item.bytes ?: error("预览内容为空")) }
            file
        }
        mediaFile = fileResult.getOrNull()
        materializeError = fileResult.exceptionOrNull()?.message
        onDispose { fileResult.getOrNull()?.delete() }
    }

    when {
        materializeError != null -> Text("媒体文件准备失败：$materializeError\n请解压后使用其他播放器打开。")
        mediaFile == null -> Text("正在准备媒体预览…")
        item.mimeType.startsWith("audio/") -> PreviewAudio(mediaFile!!)
        else -> PreviewVideo(mediaFile!!)
    }
}

@Composable
private fun PreviewAudio(file: File) {
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    val player = remember(file) { MediaPlayer() }

    DisposableEffect(file) {
        player.setOnPreparedListener { prepared = true }
        player.setOnCompletionListener { playing = false }
        player.setOnErrorListener { _, what, extra ->
            playbackError = "播放器不支持此音频（$what/$extra）"
            playing = false
            true
        }
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }.onFailure { error -> playbackError = error.message ?: "播放器初始化失败" }
        onDispose { player.release() }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (playbackError != null) {
            Text("$playbackError\n请解压后使用其他播放器打开。")
        } else {
            Text("音频预览")
            Button(
                onClick = {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        player.start()
                        playing = true
                    }
                },
                enabled = prepared,
            ) { Text(if (playing) "暂停" else if (prepared) "播放" else "准备中…") }
        }
    }
}

@Composable
private fun PreviewVideo(file: File) {
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            factory = { videoContext ->
                val videoView = VideoView(videoContext)
                val controller = MediaController(videoContext)
                controller.setAnchorView(videoView)
                videoView.setMediaController(controller)
                videoView.setVideoPath(file.absolutePath)
                videoView.setOnPreparedListener { mediaPlayer ->
                    mediaPlayer.isLooping = true
                    videoView.start()
                }
                videoView.setOnErrorListener { _, what, extra ->
                    playbackError = "播放器不支持此视频（$what/$extra）"
                    true
                }
                videoView
            },
        )
        if (playbackError != null) Text("$playbackError\n请解压后使用其他播放器打开。")
    }
}

private fun startPreviewExtraction(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    request: PreviewRequest,
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
    return scope.launch {
        try {
            val result = ArchiveRepository.extract(
                context = context,
                archiveUri = request.archiveUri,
                destinationUri = destinationUri,
                selectedPaths = if (allFiles) null else selectedPaths,
                options = request.extractOptions,
                onProgress = { setProgress(it) },
            )
            setStatus(result.fold({ "解压完成，共处理 $it 个文件" }, { "解压失败：${it.message ?: "未知错误"}" }))
        } catch (_: CancellationException) {
            setStatus("已取消解压")
        } finally {
            setBusy(false)
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
