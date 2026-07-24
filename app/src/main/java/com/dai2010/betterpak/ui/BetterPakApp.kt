package com.dai2010.betterpak.ui

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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
import com.dai2010.betterpak.domain.ArchiveItem
import com.dai2010.betterpak.domain.ArchivePreview
import com.dai2010.betterpak.domain.ArchiveProgress
import com.dai2010.betterpak.domain.CompressionAlgorithm
import com.dai2010.betterpak.domain.OverwritePolicy
import com.dai2010.betterpak.ui.theme.BetterPakTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private object Routes {
    const val HOME = "home"
    const val CREATE = "create"
    const val EXTRACT = "extract"
    const val PREVIEW = "preview"
    const val SETTINGS = "settings"
}

@Composable
fun BetterPakApp(initialArchiveUri: Uri? = null) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val navController = rememberNavController()

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
                PreviewScreen(
                    initialArchiveUri = initialArchiveUri,
                    onBack = { navController.popBackStack() },
                )
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
                    subtitle = "查看条目，选择单个或多个文件解压",
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
    var password by rememberSaveable { mutableStateOf("") }
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
    val createOutput = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { outputUri ->
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
                            OutlinedTextField(
                                value = password,
                                onValueChange = { password = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("密码加密（可选）") },
                                supportingText = {
                                    Text(
                                        if (selectedFormat == ArchiveFormat.ZIP.name) {
                                            "ZIP 创建暂不支持密码加密；密码不会保存"
                                        } else {
                                            "7z 使用 AES-256；密码不会保存"
                                        },
                                    )
                                },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    TextButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Text(if (passwordVisible) "隐藏" else "显示")
                                    }
                                },
                            )
                            Text(
                                "压缩算法：${CompressionAlgorithm.valueOf(algorithm).label}",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val algorithms = if (selectedFormat == ArchiveFormat.ZIP.name) {
                                    listOf(CompressionAlgorithm.DEFLATE, CompressionAlgorithm.COPY)
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
                        createOutput.launch(if (selectedFormat == ArchiveFormat.ZIP.name) "betterpak.zip" else "betterpak.7z")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("创建 ${if (selectedFormat == ArchiveFormat.ZIP.name) "ZIP" else "7z"} 压缩包")
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
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
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
        status = if (uri == null) null else "已选择压缩包"
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
            OutlinedButton(onClick = { pickArchive.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Archive, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (archiveUri == null) "选择 ZIP、RAR 或 7z 压缩包" else "重新选择压缩包")
            }
            archiveUri?.let { uri ->
                AssistChip(
                    onClick = {},
                    label = { Text("格式：${ArchiveRepository.detectFormat(context, uri).label}") },
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
                            password = password,
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

@Composable
private fun PreviewScreen(initialArchiveUri: Uri? = null, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var archiveUri by remember(initialArchiveUri) { mutableStateOf(initialArchiveUri) }
    var destinationUri by remember { mutableStateOf<Uri?>(null) }
    var password by rememberSaveable { mutableStateOf("") }
    var items by remember { mutableStateOf<List<ArchiveItem>>(emptyList()) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ArchiveProgress?>(null) }
    var operationJob by remember { mutableStateOf<Job?>(null) }
    var previewItem by remember { mutableStateOf<ArchivePreview?>(null) }
    var unsupportedPreviewPath by remember { mutableStateOf<String?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var overwritePolicy by rememberSaveable { mutableStateOf(OverwritePolicy.REPLACE.name) }
    var maxEntriesText by rememberSaveable { mutableStateOf("100000") }
    var maxSizeGbText by rememberSaveable { mutableStateOf("50") }
    var extractionThreads by rememberSaveable { mutableIntStateOf(2) }
    var overwriteMenuExpanded by remember { mutableStateOf(false) }

    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        archiveUri = uri
        items = emptyList()
        selectedPaths = emptySet()
        if (uri != null) loadArchive(scope, context, uri, password, { loading = it }, { result ->
            result.fold(
                onSuccess = { loaded -> items = loaded; status = "已读取 ${loaded.size} 个条目" },
                onFailure = { error -> status = "读取失败：${error.message ?: "可能需要密码"}" },
            )
        })
    }
    val pickDestination = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { ArchiveRepository.persistUriPermission(context, it) }
        destinationUri = uri
    }

    LaunchedEffect(initialArchiveUri) {
        initialArchiveUri?.let { uri ->
            ArchiveRepository.persistUriPermission(context, uri)
            loadArchive(scope, context, uri, password, { loading = it }, { result ->
                result.fold(
                    onSuccess = { loaded -> items = loaded; status = "已读取 ${loaded.size} 个条目" },
                    onFailure = { error -> status = "读取失败：${error.message ?: "可能需要密码"}" },
                )
            })
        }
    }

    Scaffold(topBar = { BackTopBar("预览压缩包", onBack) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = { pickArchive.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (archiveUri == null) "选择 ZIP、RAR 或 7z 压缩包" else "重新选择压缩包")
                }
                if (archiveUri != null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("密码压缩包？展开高级选项输入密码后重新读取。", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { advancedExpanded = !advancedExpanded }) {
                                Icon(if (advancedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("高级选项")
                            }
                            if (advancedExpanded) {
                                OutlinedTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("预览/解压密码") },
                                    visualTransformation = PasswordVisualTransformation(),
                                )
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
                                Button(onClick = {
                                    archiveUri?.let { uri ->
                                        loadArchive(scope, context, uri, password, { loading = it }, { result ->
                                            result.fold(
                                                onSuccess = { loaded -> items = loaded; selectedPaths = emptySet(); status = "已读取 ${loaded.size} 个条目" },
                                                onFailure = { error -> status = "读取失败：${error.message ?: "密码错误或文件损坏"}" },
                                            )
                                        })
                                    }
                                }) { Text("重新读取") }
                            }
                        }
                    }
                }
                if (status != null) Text(status!!, style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("${items.size} 个条目", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        selectedPaths = if (selectedPaths.size == items.count { !it.isDirectory }) {
                            emptySet()
                        } else {
                            items.filterNot { it.isDirectory }.map { it.path }.toSet()
                        }
                    }) {
                        Text(if (selectedPaths.size == items.count { !it.isDirectory }) "取消全选" else "全选文件")
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
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !item.isDirectory && !previewLoading) {
                                archiveUri?.let { archive ->
                                    previewLoading = true
                                    status = "正在预览 ${item.path}…"
                                    scope.launch {
                                        val result = ArchiveRepository.preview(context, archive, item.path, password)
                                        previewLoading = false
                                        result.fold(
                                            onSuccess = { previewItem = it; status = null },
                                            onFailure = {
                                                unsupportedPreviewPath = item.path
                                                status = "无法预览 ${item.path}：${it.message ?: "请先解压查看"}"
                                            },
                                        )
                                    }
                                }
                            },
                    ) {
                        Checkbox(
                            checked = selectedPaths.contains(item.path),
                            onCheckedChange = { checked ->
                                selectedPaths = if (checked) selectedPaths + item.path else selectedPaths - item.path
                            },
                            enabled = !item.isDirectory,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.path, maxLines = 2)
                            Text(
                                if (item.isDirectory) "目录" else formatSize(item.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
                                    operationJob = extractPreview(
                                    context,
                                    scope,
                                    archiveUri,
                                    destinationUri,
                                    selectedPaths,
                                    extractOptions = previewExtractOptions(
                                        password = password,
                                        overwritePolicy = overwritePolicy,
                                        maxEntriesText = maxEntriesText,
                                        maxSizeGbText = maxSizeGbText,
                                        threads = extractionThreads,
                                    ),
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
                                operationJob = extractPreview(
                                    context,
                                    scope,
                                    archiveUri,
                                    destinationUri,
                                    selectedPaths,
                                    extractOptions = previewExtractOptions(
                                        password = password,
                                        overwritePolicy = overwritePolicy,
                                        maxEntriesText = maxEntriesText,
                                        maxSizeGbText = maxSizeGbText,
                                        threads = extractionThreads,
                                    ),
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
    if (status != null && (busy || status!!.startsWith("解压") || status!!.startsWith("预览"))) {
        StatusCard(
            message = status!!,
            busy = busy || previewLoading,
            progress = progress?.fraction,
            onCancel = if (busy) ({ operationJob?.cancel() }) else null,
        )
    }
    val extractSinglePreview: (String) -> Unit = { path ->
        previewItem = null
        unsupportedPreviewPath = null
        operationJob = extractPreview(
            context = context,
            scope = scope,
            archiveUri = archiveUri,
            destinationUri = destinationUri,
            selectedPaths = setOf(path),
            extractOptions = previewExtractOptions(
                password = password,
                overwritePolicy = overwritePolicy,
                maxEntriesText = maxEntriesText,
                maxSizeGbText = maxSizeGbText,
                threads = extractionThreads,
            ),
            allFiles = false,
            setBusy = { busy = it },
            setStatus = { status = it },
            setProgress = { progress = it },
        )
    }
    previewItem?.let { item ->
        AlertDialog(
            onDismissRequest = { previewItem = null },
            title = { Text(item.path) },
            text = {
                when {
                    item.text != null -> Text(
                        item.text,
                        modifier = Modifier.fillMaxWidth().height(300.dp).verticalScroll(rememberScrollState()),
                    )
                    item.mimeType.startsWith("audio/") || item.mimeType.startsWith("video/") -> {
                        MediaPreview(item)
                    }
                    item.bytes != null -> {
                        val bitmap = BitmapFactory.decodeByteArray(item.bytes, 0, item.bytes.size)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = item.path,
                                modifier = Modifier.fillMaxWidth().height(300.dp),
                            )
                        } else {
                            Text("图片无法解码，请选择解压")
                        }
                    }
                    else -> Text("该文件没有可显示的预览内容")
                }
            },
            confirmButton = { TextButton(onClick = { extractSinglePreview(item.path) }) { Text("解压此文件") } },
            dismissButton = { TextButton(onClick = { previewItem = null }) { Text("关闭") } },
        )
    }
    unsupportedPreviewPath?.let { path ->
        AlertDialog(
            onDismissRequest = { unsupportedPreviewPath = null },
            title = { Text("无法预览此文件") },
            text = { Text("$path\n当前版本无法安全预览此类型，请选择解压查看原文件。") },
            confirmButton = { TextButton(onClick = { extractSinglePreview(path) }) { Text("解压此文件") } },
            dismissButton = { TextButton(onClick = { unsupportedPreviewPath = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun MediaPreview(item: ArchivePreview) {
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
        onDispose {
            fileResult.getOrNull()?.delete()
        }
    }

    when {
        materializeError != null -> Text("媒体文件准备失败：$materializeError\n请解压后使用其他播放器打开。")
        mediaFile == null -> Text("正在准备媒体预览…")
        item.mimeType.startsWith("audio/") -> AudioPreview(mediaFile!!)
        else -> VideoPreview(mediaFile!!)
    }
}

@Composable
private fun AudioPreview(file: File) {
    var prepared by remember(file) { mutableStateOf(false) }
    var playing by remember(file) { mutableStateOf(false) }
    var playbackError by remember(file) { mutableStateOf<String?>(null) }
    val player = remember(file) { MediaPlayer() }

    DisposableEffect(file) {
        player.setOnPreparedListener {
            prepared = true
        }
        player.setOnCompletionListener {
            playing = false
        }
        player.setOnErrorListener { _, what, extra ->
            playbackError = "播放器不支持此音频（$what/$extra）"
            playing = false
            true
        }
        runCatching {
            player.setDataSource(file.absolutePath)
            player.prepareAsync()
        }.onFailure { error ->
            playbackError = error.message ?: "播放器初始化失败"
        }
        onDispose {
            player.release()
        }
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
            ) {
                Text(if (playing) "暂停" else if (prepared) "播放" else "准备中…")
            }
        }
    }
}

@Composable
private fun VideoPreview(file: File) {
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
        if (playbackError != null) {
            Text("$playbackError\n请解压后使用其他播放器打开。")
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

private fun loadArchive(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    uri: Uri,
    password: String,
    setLoading: (Boolean) -> Unit,
    setResult: (Result<List<ArchiveItem>>) -> Unit,
) {
    setLoading(true)
    scope.launch {
        setResult(ArchiveRepository.list(context, uri, password))
        setLoading(false)
    }
}

private fun extractPreview(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    archiveUri: Uri?,
    destinationUri: Uri?,
    selectedPaths: Set<String>,
    extractOptions: ArchiveExtractOptions,
    allFiles: Boolean,
    setBusy: (Boolean) -> Unit,
    setStatus: (String) -> Unit,
    setProgress: (ArchiveProgress?) -> Unit,
): Job? {
    if (archiveUri == null || destinationUri == null) {
        setStatus("请选择目标目录")
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
                archiveUri = archiveUri,
                destinationUri = destinationUri,
                selectedPaths = if (allFiles) null else selectedPaths,
                options = extractOptions,
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

private fun previewExtractOptions(
    password: String,
    overwritePolicy: String,
    maxEntriesText: String,
    maxSizeGbText: String,
    threads: Int,
): ArchiveExtractOptions {
    val maxEntries = maxEntriesText.toIntOrNull()?.coerceAtLeast(1) ?: 100000
    val maxGb = maxSizeGbText.toDoubleOrNull()?.coerceAtLeast(0.1) ?: 50.0
    return ArchiveExtractOptions(
        password = password,
        overwritePolicy = OverwritePolicy.valueOf(overwritePolicy),
        maxEntries = maxEntries,
        maxExpandedBytes = (maxGb * 1024 * 1024 * 1024).toLong(),
        threads = threads,
    )
}

private fun formatSize(size: Long): String {
    if (size < 0) return "大小未知"
    if (size < 1024) return "$size B"
    if (size < 1024 * 1024) return "%.1f KB".format(size / 1024.0)
    if (size < 1024L * 1024L * 1024L) return "%.1f MB".format(size / (1024.0 * 1024.0))
    return "%.1f GB".format(size / (1024.0 * 1024.0 * 1024.0))
}

private fun normalizeHex(value: String): String? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    val normalized = if (trimmed.startsWith("#")) trimmed else "#$trimmed"
    return if (normalized.matches(Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) normalized else null
}
