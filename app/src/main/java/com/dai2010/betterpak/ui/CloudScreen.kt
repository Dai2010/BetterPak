package com.dai2010.betterpak.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.dai2010.betterpak.domain.CloudAccount
import com.dai2010.betterpak.domain.CloudItem
import com.dai2010.betterpak.domain.CloudProviderId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudScreen(
    viewModel: CloudViewModel,
    callbackUri: android.net.Uri?,
    onBack: () -> Unit,
    onOpenArchive: (android.net.Uri) -> Unit,
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::upload)
    }

    LaunchedEffect(callbackUri) {
        callbackUri?.let(viewModel::handleCallback)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("云端文件") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.selectedAccount != null) {
                        IconButton(onClick = viewModel::refresh, enabled = !state.busy) {
                            Icon(Icons.Outlined.Refresh, contentDescription = "刷新")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("OneDrive / Google Drive", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "只接入官方 API；其他云盘继续使用系统文件选择器。云端文件会先下载到受限缓存，再交给本地归档引擎。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                AccountChooser(
                    accounts = state.accounts,
                    selected = state.selectedAccount,
                    isConfigured = viewModel::isConfigured,
                    onSignIn = { provider ->
                        viewModel.signInUri(provider)?.let { uri ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            } catch (_: ActivityNotFoundException) {
                                viewModel.showMessage("系统没有可用浏览器，无法开始云端登录")
                            }
                        }
                    },
                    onSelect = viewModel::selectAccount,
                    onSignOut = viewModel::signOut,
                )
            }
            if (state.selectedAccount != null) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (state.path.isNotEmpty()) {
                            OutlinedButton(
                                onClick = viewModel::goUp,
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            ) { Text("返回上级") }
                        }
                        Button(
                            onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                            Text("上传文件", modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                }
            }
            if (state.busy) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (state.progress != null) {
                            LinearProgressIndicator(
                                progress = { state.progress.fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        TextButton(onClick = viewModel::cancelTransfer) { Text("取消云端操作") }
                    }
                }
            }
            state.message?.let { message ->
                item {
                    Text(message, color = MaterialTheme.colorScheme.primary)
                }
            }
            items(state.items, key = { it.id }) { item ->
                CloudItemRow(
                    item = item,
                    enabled = !state.busy,
                    onOpenFolder = { viewModel.openFolder(item) },
                    onDownload = {
                        viewModel.download(item) { file ->
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
                            onOpenArchive(uri)
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccountChooser(
    accounts: List<CloudAccount>,
    selected: CloudAccount?,
    isConfigured: (CloudProviderId) -> Boolean,
    onSignIn: (CloudProviderId) -> Unit,
    onSelect: (CloudAccount) -> Unit,
    onSignOut: (CloudAccount) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (accounts.isNotEmpty()) {
            Text("已登录账号", style = MaterialTheme.typography.titleMedium)
            accounts.forEach { account ->
                Card(
                    onClick = { onSelect(account) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(account.displayName)
                            Text(
                                account.email ?: providerLabel(account.provider),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (selected?.id == account.id && selected.provider == account.provider) {
                            Text("当前", color = MaterialTheme.colorScheme.primary)
                        }
                        IconButton(onClick = { onSignOut(account) }) {
                            Icon(Icons.Outlined.Logout, contentDescription = "退出登录")
                        }
                    }
                }
            }
        }
        Text("添加云端账号", style = MaterialTheme.typography.titleMedium)
        CloudProviderId.entries.forEach { provider ->
            OutlinedButton(
                onClick = { onSignIn(provider) },
                enabled = isConfigured(provider),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Cloud, contentDescription = null)
                Text(
                    if (isConfigured(provider)) "登录 ${providerLabel(provider)}" else "${providerLabel(provider)}（未配置）",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun CloudItemRow(
    item: CloudItem,
    enabled: Boolean,
    onOpenFolder: () -> Unit,
    onDownload: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (item.isDirectory) Icons.Outlined.Folder else Icons.Outlined.CloudDownload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(item.name)
                item.size?.let {
                    Text(
                        "${it / 1024} KiB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(
                onClick = if (item.isDirectory) onOpenFolder else onDownload,
                enabled = enabled,
            ) { Text(if (item.isDirectory) "打开" else "下载") }
        }
    }
}

private fun providerLabel(provider: CloudProviderId): String = when (provider) {
    CloudProviderId.ONEDRIVE -> "OneDrive"
    CloudProviderId.GOOGLE_DRIVE -> "Google Drive"
}
