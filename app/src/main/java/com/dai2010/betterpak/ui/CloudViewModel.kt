package com.dai2010.betterpak.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dai2010.betterpak.data.CloudAccountStore
import com.dai2010.betterpak.data.CloudCache
import com.dai2010.betterpak.data.CloudConfiguration
import com.dai2010.betterpak.data.CloudProviderRegistry
import com.dai2010.betterpak.data.CloudTokenStore
import com.dai2010.betterpak.data.CloudOAuthClient
import com.dai2010.betterpak.data.RefreshingCloudTokenProvider
import com.dai2010.betterpak.domain.CloudAccount
import com.dai2010.betterpak.domain.CloudErrorClassifier
import com.dai2010.betterpak.domain.CloudErrorCode
import com.dai2010.betterpak.domain.CloudItem
import com.dai2010.betterpak.domain.CloudOAuth
import com.dai2010.betterpak.domain.CloudProviderId
import com.dai2010.betterpak.domain.CloudTransferProgress
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CloudUiState(
    val accounts: List<CloudAccount> = emptyList(),
    val selectedAccount: CloudAccount? = null,
    val path: List<CloudItem> = emptyList(),
    val items: List<CloudItem> = emptyList(),
    val busy: Boolean = false,
    val progress: CloudTransferProgress? = null,
    val message: String? = null,
)

class CloudViewModel(private val appContext: Context) : ViewModel() {
    private val accountStore = CloudAccountStore(appContext)
    private val tokenStore = CloudTokenStore(appContext)
    private val oauthClient = CloudOAuthClient()
    private val configuration = CloudConfiguration(appContext)
    private val tokenProvider = RefreshingCloudTokenProvider(
        tokenStore = tokenStore,
        oauthClient = oauthClient,
        configs = configuration.configs(),
    )
    private val providers = CloudProviderRegistry(tokenProvider)
    private val cache = CloudCache(appContext)
    private val _state = MutableStateFlow(
        CloudUiState(accounts = accountStore.list()),
    )
    val state: StateFlow<CloudUiState> = _state.asStateFlow()
    private var handledCallback: String? = null
    private var transferJob: Job? = null

    init {
        accountStore.list().firstOrNull()?.let(::selectAccount)
    }

    fun isConfigured(provider: CloudProviderId): Boolean = configuration.config(provider) != null

    fun signInUri(provider: CloudProviderId): Uri? {
        val config = configuration.config(provider) ?: run {
            showMessage("${provider.label} 尚未配置 OAuth client ID")
            return null
        }
        val session = CloudOAuth.createSession()
        accountStore.savePending(provider, session)
        return oauthClient.authorizationUri(config, session)
    }

    fun handleCallback(uri: Uri) {
        if (handledCallback == uri.toString()) return
        handledCallback = uri.toString()
        val provider = providerFor(uri) ?: run {
            showMessage("无法识别云端 OAuth 回调")
            return
        }
        val pending = accountStore.pending(provider) ?: run {
            showMessage("OAuth 会话已失效，请重新登录")
            return
        }
        val code = uri.getQueryParameter("code").orEmpty()
        val returnedState = uri.getQueryParameter("state")
        viewModelScope.launch {
            update { it.copy(busy = true, message = null) }
            try {
                val config = configuration.config(provider)
                    ?: error("${provider.label} 尚未配置 OAuth client ID")
                val token = oauthClient.exchangeCode(config, pending.session, returnedState, code)
                    .getOrThrow()
                tokenStore.save(pending.accountKey, token)
                val account = providers.provider(provider).currentAccount(pending.accountKey).getOrThrow()
                tokenStore.clear(pending.accountKey)
                tokenStore.save(account, token)
                accountStore.save(account)
                accountStore.clearPending(provider)
                update {
                    it.copy(
                        accounts = accountStore.list(),
                        selectedAccount = account,
                        path = emptyList(),
                        items = emptyList(),
                        message = "已登录 ${account.displayName}",
                    )
                }
                loadChildren(account, null, emptyList())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                tokenStore.clear(pending.accountKey)
                showMessage(cloudErrorMessage(CloudErrorClassifier.classify(error)))
            } finally {
                accountStore.clearPending(provider)
                update { it.copy(busy = false) }
            }
        }
    }

    fun selectAccount(account: CloudAccount) {
        update {
            it.copy(
                selectedAccount = account,
                path = emptyList(),
                items = emptyList(),
                message = null,
            )
        }
        loadChildren(account, null, emptyList())
    }

    fun openFolder(item: CloudItem) {
        val account = state.value.selectedAccount ?: return
        if (!item.isDirectory) return
        val path = state.value.path + item
        loadChildren(account, item.id, path)
    }

    fun goUp() {
        val account = state.value.selectedAccount ?: return
        val path = state.value.path
        val nextPath = path.dropLast(1)
        loadChildren(account, nextPath.lastOrNull()?.id, nextPath)
    }

    fun refresh() {
        val account = state.value.selectedAccount ?: return
        loadChildren(account, state.value.path.lastOrNull()?.id, state.value.path)
    }

    fun download(item: CloudItem, onComplete: (File) -> Unit) {
        val account = state.value.selectedAccount ?: return
        if (item.isDirectory) return
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            update { it.copy(busy = true, progress = null, message = "正在下载 ${item.name}…") }
            var part: File? = null
            try {
                val partFile = cache.createDownloadFile(item.name)
                part = partFile
                val downloaded = providers.provider(account.provider).download(
                    account = account,
                    item = item,
                    destination = partFile,
                    onProgress = { progress -> update { it.copy(progress = progress) } },
                ).getOrThrow()
                val completed = cache.completePart(downloaded)
                update { it.copy(message = "下载完成：${item.name}", progress = null) }
                onComplete(completed)
            } catch (error: CancellationException) {
                part?.delete()
                throw error
            } catch (error: Throwable) {
                part?.delete()
                showMessage(cloudErrorMessage(CloudErrorClassifier.classify(error)))
            } finally {
                update { it.copy(busy = false, progress = null) }
            }
        }
        transferJob?.invokeOnCompletion { completedJob ->
            if (transferJob == completedJob) transferJob = null
        }
    }

    fun upload(uri: Uri) {
        val account = state.value.selectedAccount ?: return
        val parentId = state.value.path.lastOrNull()?.id
        transferJob?.cancel()
        transferJob = viewModelScope.launch {
            val source = File(appContext.cacheDir, "betterpak-cloud-upload-${UUID.randomUUID()}.part")
            try {
                val name = copyUploadSource(uri, source)
                update { it.copy(busy = true, progress = null, message = "正在上传 $name…") }
                providers.provider(account.provider).upload(
                    account = account,
                    parentId = parentId,
                    name = name,
                    source = source,
                    mimeType = appContext.contentResolver.getType(uri),
                    onProgress = { progress -> update { it.copy(progress = progress) } },
                ).getOrThrow()
                update { it.copy(message = "上传完成：$name") }
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(cloudErrorMessage(CloudErrorClassifier.classify(error)))
            } finally {
                source.delete()
                update { it.copy(busy = false, progress = null) }
            }
        }
        transferJob?.invokeOnCompletion { completedJob ->
            if (transferJob == completedJob) transferJob = null
        }
    }

    fun cancelTransfer() {
        transferJob?.cancel()
        transferJob = null
        update { it.copy(busy = false, progress = null, message = "云端操作已取消") }
    }

    fun signOut(account: CloudAccount) {
        tokenStore.clear(account.id)
        accountStore.remove(account)
        cache.clear()
        val accounts = accountStore.list()
        update {
            it.copy(
                accounts = accounts,
                selectedAccount = accounts.firstOrNull(),
                path = emptyList(),
                items = emptyList(),
                message = "已退出 ${account.displayName}",
            )
        }
        accounts.firstOrNull()?.let(::selectAccount)
    }

    fun clearMessage() {
        update { it.copy(message = null) }
    }

    override fun onCleared() {
        cache.clear()
        super.onCleared()
    }

    private fun loadChildren(account: CloudAccount, parentId: String?, path: List<CloudItem>) {
        viewModelScope.launch {
            update { it.copy(busy = true, progress = null, message = null) }
            try {
                val provider = providers.provider(account.provider)
                val allItems = buildList {
                    var pageToken: String? = null
                    do {
                        val page = provider.listChildren(account, parentId, pageToken).getOrThrow()
                        addAll(page.items)
                        pageToken = page.nextPageToken
                    } while (pageToken != null)
                }.sortedWith(compareByDescending<CloudItem> { it.isDirectory }.thenBy { it.name.lowercase() })
                update {
                    it.copy(
                        selectedAccount = account,
                        path = path,
                        items = allItems,
                        message = if (allItems.isEmpty()) "此目录为空" else null,
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                showMessage(cloudErrorMessage(CloudErrorClassifier.classify(error)))
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private suspend fun copyUploadSource(uri: Uri, destination: File): String = withContext(Dispatchers.IO) {
        val name = appContext.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.takeIf(String::isNotBlank) ?: "upload-${System.currentTimeMillis()}"
        val input = appContext.contentResolver.openInputStream(uri)
            ?: error("无法读取待上传文件")
        input.use { source ->
            FileOutputStream(destination).use { target -> source.copyTo(target) }
        }
        name
    }

    private fun providerFor(uri: Uri): CloudProviderId? = when (uri.pathSegments.lastOrNull()) {
        "onedrive" -> CloudProviderId.ONEDRIVE
        "google-drive" -> CloudProviderId.GOOGLE_DRIVE
        else -> null
    }

    fun showMessage(message: String) {
        update { it.copy(message = message) }
    }

    private fun update(transform: (CloudUiState) -> CloudUiState) {
        _state.value = transform(_state.value)
    }

    private fun cloudErrorMessage(code: CloudErrorCode): String = when (code) {
        CloudErrorCode.AUTH_REQUIRED,
        CloudErrorCode.AUTH_EXPIRED,
        -> "云端授权已失效，请重新登录"
        CloudErrorCode.ACCESS_DENIED -> "云端权限不足，无法访问此目录或文件"
        CloudErrorCode.NOT_FOUND -> "云端文件或目录不存在"
        CloudErrorCode.NETWORK_UNAVAILABLE -> "网络不可用或云端暂时无法连接"
        CloudErrorCode.TIMEOUT -> "云端请求超时，请稍后重试"
        CloudErrorCode.QUOTA_EXCEEDED -> "云端配额或请求频率已达上限"
        CloudErrorCode.LIMIT_EXCEEDED -> "文件超过 BetterPak 的本地缓存限制"
        CloudErrorCode.CONFLICT -> "云端文件冲突，请更换名称后重试"
        CloudErrorCode.CANCELLED -> "云端操作已取消"
        CloudErrorCode.INVALID_REQUEST,
        CloudErrorCode.UNKNOWN,
        -> "云端操作失败，请稍后重试"
    }
}

class CloudViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(CloudViewModel::class.java))
        return CloudViewModel(context.applicationContext) as T
    }
}

private val CloudProviderId.label: String
    get() = when (this) {
        CloudProviderId.ONEDRIVE -> "OneDrive"
        CloudProviderId.GOOGLE_DRIVE -> "Google Drive"
    }
