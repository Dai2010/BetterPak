package com.dai2010.betterpak.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Preview
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.dai2010.betterpak.data.AppSettings
import com.dai2010.betterpak.data.ArchiveRepository
import com.dai2010.betterpak.data.SettingsRepository
import com.dai2010.betterpak.data.ThemeMode
import com.dai2010.betterpak.domain.ArchiveCreateOptions
import com.dai2010.betterpak.domain.ArchiveExtractOptions
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveProgress
import com.dai2010.betterpak.domain.CompressionAlgorithm
import com.dai2010.betterpak.domain.OverwritePolicy
import com.dai2010.betterpak.ui.theme.BetterPakTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val EXTRACT = "extract"
    const val PREVIEW = "preview"
    const val PREVIEW_BROWSER = "preview_browser"
    const val SETTINGS = "settings"
}

@Composable
fun BetterPakApp(initialArchiveUri: Uri? = null) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val navController = rememberNavController()
    var previewRequest by remember { mutableStateOf<PreviewRequest?>(null) }

    BetterPakTheme(settings) {
        NavHost(
            navController = navController,
            startDestination = if (initialArchiveUri == null) Routes.HOME else Routes.PREVIEW,
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onCreate = { navController.navigate(Routes.CREATE) },
                    onExtract = { navController.navigate(Routes.EXTRACT) },
                    onPreview = { navController.navigate(Routes.PREVIEW) },
                    onSettings = { navController.navigate(Routes.SETTINGS) },
                )
            }
            composable(Routes.CREATE) { CreateScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.EXTRACT) { ExtractScreen(onBack = { navController.popBackStack() }) }
            composable(Routes.PREVIEW) {
                PreviewSetupScreen(
                    initialArchiveUri = initialArchiveUri,
                    onBack = { navController.popBackStack() },
                    onOpenPreview = { request ->
                        previewRequest = request
                        navController.navigate(Routes.PREVIEW_BROWSER)
                    },
                )
            }
            composable(Routes.PREVIEW_BROWSER) {
                previewRequest?.let { request ->
                    PreviewBrowserScreen(
                        request = request,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    repository = settingsRepository,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    onCreate: () -> Unit,
    onExtract: () -> Unit,
    onPreview: () -> Unit,
    onSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BetterPak") },
                actions = {
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("压缩包管理", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "创建、解压、预览。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                HomeActionCard(
                    title = "创建压缩包",
                    subtitle = "选择文件，设置格式和高级压缩参数",
                    icon = Icons.Outlined.Upload,
                    onClick = onCreate,
                )
            }
            item {
                HomeActionCard(
                    title = "解压压缩包",
                    subtitle = "选择压缩包和目标目录，一键解压全部内容",
                    icon = Icons.Outlined.ArrowDownward,
                    onClick = onExtract,
                )
            }
            item {
                HomeActionCard(
                    title = "预览压缩包",
                    subtitle = "先设置参数，再用独立界面预览文件",
                    icon = Icons.Outlined.Preview,
                    onClick = onPreview,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeActionCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var selectedFormat by rememberSaveable { mutableStateOf(ArchiveFormat.ZIP.name) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var algorithm by rememberSaveable { mutableStateOf(CompressionAlgorithm.DEFLATE.name) }
    var compressionLevel by rememberSaveable { mutableIntStateOf(5) }
    var threads by rememberSaveable { mutableIntStateOf(2) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var pendingOptions by remember { mutableStateOf(ArchiveCreateOptions()) }
    var pendingFormat by remember { mutableStateOf(ArchiveFormat.ZIP.name) }

    val pickInputs = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { ArchiveRepository.persistUriPermission(context, it) }
        selectedUris = uris
        status = if (uris.isEmpty()) null else "已选择 ${uris.size} 个文件"
    }
    val pickInputDirectory = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && uri !in selectedUris) {
            ArchiveRepository.persistUriPermission(context, uri)
            selectedUris = selectedUris + uri
            status = "已选择 ${selectedUris.size} 个文件或目录"
        }
    }
    val startCreate: (Uri?) -> Unit = { outputUri ->
        if (outputUri != null) {
            busy = true
            progress = null
            val format = ArchiveFormat.valueOf(pendingFormat)
            status = "正在创建 ${format.label} 压缩包…"
            operationJob = scope.launch {
                try {
                    val result = when (format) {
                        ArchiveFormat.ZIP -> ArchiveRepository.createZip(
                            context,
                            selectedUris,
                            outputUri,
                            pendingOptions,
                            onProgress = { progress = it },
                        )
                        ArchiveFormat.SEVEN_Z -> ArchiveRepository.createSevenZ(
                            context,
                            selectedUris,
                            outputUri,
                            pendingOptions,
                            onProgress = { progress = it },
                        )
                        ArchiveFormat.TAR -> ArchiveRepository.createTar(
                            context,
                            selectedUris,
                            outputUri,
                            pendingOptions,
                            onProgress = { progress = it },
                        )
                        ArchiveFormat.TAR_ZSTANDARD -> ArchiveRepository.createTarZstandard(
                            context,
                            selectedUris,
                            outputUri,
                            pendingOptions,
                            onProgress = { progress = it },
                        )
                        else -> Result.failure(IllegalArgumentException("暂不支持创建该格式"))
                    }
                    status = result.fold(
                        onSuccess = { "创建完成，共打包 $it 个文件" },
                        onFailure = { "创建失败：${it.message ?: "未知错误"}" },
                    )
                } catch (_: CancellationException) {
                    status = "已取消创建"
                } finally {
                    busy = false
                    operationJob = null
                }
            }
        }
    }
    val createZipOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
        startCreate,
    )
    val createSevenZOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-7z-compressed"),
        startCreate,
    )
    val createTarOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-tar"),
        startCreate,
    )
    val createTarZstandardOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zstd"),
        startCreate,
    )

    Scaffold(topBar = { BackTopBar("创建压缩包", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionTitle("1. 选择格式")
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedFormat == ArchiveFormat.ZIP.name,
                    onClick = {
                    selectedFormat = ArchiveFormat.ZIP.name
                    algorithm = CompressionAlgorithm.DEFLATE.name
                    password = ""
                    },
                    label = { Text("ZIP（可创建）") },
                )
                FilterChip(
                    selected = selectedFormat == ArchiveFormat.SEVEN_Z.name,
                    onClick = {
                        selectedFormat = ArchiveFormat.SEVEN_Z.name
                        algorithm = CompressionAlgorithm.LZMA2.name
                    },
                    label = { Text("7z（可创建）") },
                )
                FilterChip(
                    selected = selectedFormat == ArchiveFormat.RAR.name,
                    onClick = { selectedFormat = ArchiveFormat.RAR.name },
                    enabled = false,
                    label = { Text("RAR（仅解压）") },
                )
                FilterChip(
                    selected = selectedFormat == ArchiveFormat.TAR.name,
                    onClick = {
                        selectedFormat = ArchiveFormat.TAR.name
                        algorithm = CompressionAlgorithm.COPY.name
                        password = ""
                    },
                    label = { Text("TAR（可创建）") },
                )
                FilterChip(
                    selected = selectedFormat == ArchiveFormat.TAR_ZSTANDARD.name,
                    onClick = {
                        selectedFormat = ArchiveFormat.TAR_ZSTANDARD.name
                        algorithm = CompressionAlgorithm.COPY.name
                        password = ""
                    },
                    label = { Text("TAR.ZST（可创建）") },
                )
            }
            SectionTitle("2. 选择文件")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { pickInputs.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (selectedUris.isEmpty()) "选择文件" else "重新选文件")
                }
                OutlinedButton(onClick = { pickInputDirectory.launch(null) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("选择目录")
                }
            }
            selectedUris.take(8).forEach { uri ->
                Text(
                    "• ${uri.lastPathSegment?.substringAfterLast('/') ?: "未命名文件"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedUris.size > 8) {
                Text("还有 ${selectedUris.size - 8} 个文件", style = MaterialTheme.typography.bodySmall)
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    TextButton(onClick = { advancedExpanded = !advancedExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("高级选项")
                    }
                    if (advancedExpanded) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (selectedFormat == ArchiveFormat.SEVEN_Z.name) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("密码加密（可选）") },
                                    supportingText = { Text("7z 使用 AES-256；密码不会保存") },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Text(if (passwordVisible) "隐藏" else "显示")
                                        }
                                    },
                                )
                            } else {
                                Text(
                                    "${ArchiveFormat.valueOf(selectedFormat).label} 不支持标准密码，不需要设置密码。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "压缩算法：${CompressionAlgorithm.valueOf(algorithm).label}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val algorithms = if (selectedFormat == ArchiveFormat.ZIP.name) {
                                    listOf(CompressionAlgorithm.DEFLATE, CompressionAlgorithm.COPY)
                                } else if (selectedFormat == ArchiveFormat.TAR.name || selectedFormat == ArchiveFormat.TAR_ZSTANDARD.name) {
                                    listOf(CompressionAlgorithm.COPY)
                                } else {
                                    CompressionAlgorithm.entries
                                }
                                algorithms.forEach { item ->
                                    FilterChip(
                                        selected = algorithm == item.name,
                                        onClick = { algorithm = item.name },
                                        label = { Text(item.label) },
                                    )
                                }
                            }
                            Text("压缩级别：$compressionLevel", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = compressionLevel.toFloat(),
                                onValueChange = { compressionLevel = it.roundToInt() },
                                valueRange = 0f..9f,
                                steps = 8,
                            )
                            Text("并行准备线程：$threads", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = threads.toFloat(),
                                onValueChange = { threads = it.roundToInt().coerceIn(1, 8) },
                                valueRange = 1f..8f,
                                steps = 6,
                            )
                            Text(
                                if (selectedFormat == ArchiveFormat.ZIP.name) {
                                    "ZIP 使用 Deflate 或仅存储；线程数用于输入准备。"
                                } else if (selectedFormat == ArchiveFormat.TAR.name || selectedFormat == ArchiveFormat.TAR_ZSTANDARD.name) {
                                    "TAR 按顺序写入条目；TAR.ZST 额外使用 Zstandard 流压缩。密码和特殊条目不支持。"
                                } else {
                                    "当前 7z 写入引擎按顺序写入压缩流，线程数用于输入准备；原生并行压缩引擎接入后会进一步利用该设置。"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (status != null) {
                StatusCard(
                    message = status!!,
                    busy = busy,
                    progress = progress?.fraction,
                    onCancel = if (busy) ({ operationJob?.cancel() }) else null,
                )
            }
            Button(
                onClick = {
                    if (selectedFormat == ArchiveFormat.RAR.name) {
                        status = "RAR 创建需要单独的官方编码器授权，当前版本暂不提供。"
                    } else if (selectedUris.isEmpty()) {
                        status = "请先选择文件"
                    } else {
                        pendingOptions = ArchiveCreateOptions(
                            password = password,
                            algorithm = CompressionAlgorithm.valueOf(algorithm),
                            compressionLevel = compressionLevel,
                            threads = threads,
                        )
                        pendingFormat = selectedFormat
                        when (selectedFormat) {
                            ArchiveFormat.ZIP.name -> createZipOutput.launch("betterpak.zip")
                            ArchiveFormat.SEVEN_Z.name -> createSevenZOutput.launch("betterpak.7z")
                            ArchiveFormat.TAR.name -> createTarOutput.launch("betterpak.tar")
                            ArchiveFormat.TAR_ZSTANDARD.name -> createTarZstandardOutput.launch("betterpak.tar.zst")
                            else -> status = "当前格式不支持创建"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("创建 ${ArchiveFormat.valueOf(selectedFormat).label} 压缩包")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExtractScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var archiveUri by remember { mutableStateOf<Uri?>(null) }
    var archiveFormat by remember { mutableStateOf(ArchiveFormat.UNKNOWN) }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var password by remember { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var overwritePolicy by rememberSaveable { mutableStateOf(OverwritePolicy.REPLACE.name) }
    var maxEntriesText by rememberSaveable { mutableStateOf("100000") }
    var maxSizeGbText by rememberSaveable { mutableStateOf("50") }
    var threads by rememberSaveable { mutableIntStateOf(2) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var overwriteMenuExpanded by remember { mutableStateOf(false) }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        archiveUri = uri
        archiveFormat = uri?.let { ArchiveRepository.detectFormat(context, it) } ?: ArchiveFormat.UNKNOWN
        if (!archiveFormat.supportsPassword) password = ""
        status = when {
            uri == null -> null
            archiveFormat == ArchiveFormat.UNKNOWN -> "无法识别格式，请选择 ZIP、RAR、7z、TAR 或 Zstandard 文件"
            else -> "已选择 ${archiveFormat.label} 压缩包"
        }
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        destinationUri = uri
    }

    Scaffold(topBar = { BackTopBar("解压压缩包", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            OutlinedButton(onClick = { pickArchive.launch(ArchiveRepository.supportedArchiveMimeTypes()) }, modifier = Modifier.fillMaxWidth()) {
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
            OutlinedButton(onClick = { pickDestination.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (destinationUri == null) "选择解压目标目录" else "已选择目标目录")
            }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column {
                    TextButton(onClick = { advancedExpanded = !advancedExpanded }, modifier = Modifier.fillMaxWidth()) {
                        Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("高级选项")
                    }
                    if (advancedExpanded) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (archiveFormat.supportsPassword) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("解压密码（可选）") },
                                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                    trailingIcon = {
                                        TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                            Text(if (passwordVisible) "隐藏" else "显示")
                                        }
                                    },
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
                            Text("解压任务线程：$threads", style = MaterialTheme.typography.labelLarge)
                            Slider(
                                value = threads.toFloat(),
                                onValueChange = { threads = it.roundToInt().coerceIn(1, 8) },
                                valueRange = 1f..8f,
                                steps = 6,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = true, onCheckedChange = null)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text("路径安全保护")
                                    Text("始终阻止路径穿越和特殊路径", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
            if (status != null) {
                StatusCard(
                    message = status!!,
                    busy = busy,
                    progress = progress?.fraction,
                    onCancel = if (busy) ({ operationJob?.cancel() }) else null,
                )
            }
            Button(
                onClick = {
                    val archive = archiveUri
                    val destination = destinationUri
                    if (archive == null || destination == null) {
                        status = "请选择压缩包和目标目录"
                    } else {
                        val maxEntries = maxEntriesText.toIntOrNull()?.coerceAtLeast(1) ?: 100000
                        val maxGb = maxSizeGbText.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 50.0
                        val options = ArchiveExtractOptions(
                            password = if (archiveFormat.supportsPassword) password else "",
                            overwritePolicy = OverwritePolicy.valueOf(overwritePolicy),
                            maxEntries = maxEntries,
                            maxExpandedBytes = (maxGb * 1024 * 1024 * 1024).toLong(),
                            threads = threads,
                        )
                        busy = true
                        progress = null
                        status = "正在解压全部文件…"
                        operationJob = scope.launch {
                            try {
                                val result = ArchiveRepository.extract(
                                    context,
                                    archive,
                                    destination,
                                    null,
                                    options,
                                    onProgress = { progress = it },
                                )
                                status = result.fold(
                                    onSuccess = { "解压完成，共处理 $it 个文件" },
                                    onFailure = { "解压失败：${it.message ?: "未知错误"}" },
                                )
                            } catch (_: CancellationException) {
                                status = "已取消解压"
                            } finally {
                                busy = false
                                operationJob = null
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("解压全部文件")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    settings: AppSettings,
    repository: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var customSeed by remember(settings.customSeedHex) { mutableStateOf(settings.customSeedHex) }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("设置", onBack) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("外观", style = MaterialTheme.typography.titleLarge)
            ThemeMode.entries.forEach { mode ->
                Card(onClick = { scope.launch { repository.setThemeMode(mode) } }, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { repository.setThemeMode(mode) } },
                        )
                        Column {
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "跟随系统"
                                    ThemeMode.LIGHT -> "浅色模式"
                                    ThemeMode.DARK -> "深色模式"
                                },
                            )
                            Text(
                                when (mode) {
                                    ThemeMode.SYSTEM -> "使用系统当前的明暗设置"
                                    ThemeMode.LIGHT -> "始终使用浅色 Material You 界面"
                                    ThemeMode.DARK -> "始终使用深色 Material You 界面"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("主题色", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = customSeed,
                onValueChange = { customSeed = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("自定义颜色（HEX）") },
                placeholder = { Text("例如 #6750A4；留空使用系统动态颜色") },
                leadingIcon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
                singleLine = true,
            )
            Button(
                onClick = {
                    val normalized = normalizeHex(customSeed)
                    if (normalized == null && customSeed.isNotBlank()) {
                        status = "请输入合法的 HEX 颜色，例如 #6750A4"
                    } else {
                        scope.launch { repository.setCustomSeedHex(normalized.orEmpty()) }
                        status = "主题色已保存"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("保存主题色") }
            Text(
                "Android 12 及以上在未设置自定义颜色时使用系统动态取色；自定义颜色会覆盖系统动态颜色。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (status != null) Text(status!!, color = MaterialTheme.colorScheme.primary)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("正式版会继续增加通知、任务历史、默认解压目录和预览类型设置。")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackTopBar(title: String, onBack: () -> Unit) {
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
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun StatusCard(
    message: String,
    busy: Boolean,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                } else {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Text(message, modifier = Modifier.weight(1f))
                if (busy && onCancel != null) {
                    TextButton(onClick = onCancel) { Text("取消") }
                }
            }
            if (busy) {
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

private fun normalizeHex(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    return if (normalized.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) normalized else null
}
