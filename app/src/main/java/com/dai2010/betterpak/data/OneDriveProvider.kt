package com.dai2010.betterpak.data

import android.net.Uri
import com.dai2010.betterpak.domain.CloudAccount
import com.dai2010.betterpak.domain.CloudErrorClassifier
import com.dai2010.betterpak.domain.CloudItem
import com.dai2010.betterpak.domain.CloudOperationException
import com.dai2010.betterpak.domain.CloudPage
import com.dai2010.betterpak.domain.CloudProvider
import com.dai2010.betterpak.domain.CloudProviderId
import com.dai2010.betterpak.domain.CloudTokenProvider
import com.dai2010.betterpak.domain.CloudTransferProgress
import java.io.File
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class OneDriveProvider internal constructor(
    tokenProvider: CloudTokenProvider,
    private val httpClient: CloudHttpClient = CloudHttpClient(tokenProvider),
) : CloudProvider {
    override val providerId: CloudProviderId = CloudProviderId.ONEDRIVE

    override suspend fun currentAccount(accountKey: String): Result<CloudAccount> = guarded {
        val json = JSONObject(
            httpClient.getJson(
                accountId = accountKey,
                url = "$GRAPH_ROOT/me?%24select=id,displayName,mail,userPrincipalName",
            ),
        )
        val id = json.optString("id", "")
        require(id.isNotBlank()) { "OneDrive 未返回账号标识" }
        CloudAccount(
            id = id,
            provider = providerId,
            displayName = json.optString("displayName", "OneDrive 用户"),
            email = json.optString("mail", null)
                ?: json.optString("userPrincipalName", null),
        )
    }

    override suspend fun listChildren(
        account: CloudAccount,
        parentId: String?,
        pageToken: String?,
    ): Result<CloudPage> = guarded {
        require(account.provider == providerId) { "云账号与 OneDrive provider 不匹配" }
        val parentPath = parentId?.let { "/items/${Uri.encode(it)}" } ?: "/root"
        val page = pageToken?.let { "&%24skiptoken=${Uri.encode(it)}" }.orEmpty()
        val url = "$GRAPH_ROOT/me/drive$parentPath/children" +
            "?%24select=id,name,size,file,folder,lastModifiedDateTime,parentReference" +
            "&%24top=200$page"
        val response = JSONObject(httpClient.getJson(account.id, url))
        val items = response.optJSONArray("value").orEmptyObjects().map { item ->
            parseItem(item, parentId)
        }
        CloudPage(items, response.optString("@odata.nextLink", null)?.let(::extractSkipToken))
    }

    override suspend fun download(
        account: CloudAccount,
        item: CloudItem,
        destination: File,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): Result<File> = guarded {
        require(account.provider == providerId) { "云账号与 OneDrive provider 不匹配" }
        require(!item.isDirectory) { "不能下载 OneDrive 文件夹" }
        httpClient.download(
            accountId = account.id,
            url = "$GRAPH_ROOT/me/drive/items/${Uri.encode(item.id)}/content",
            destination = destination,
            onProgress = onProgress,
        )
    }

    override suspend fun upload(
        account: CloudAccount,
        parentId: String?,
        name: String,
        source: File,
        mimeType: String?,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): Result<CloudItem> = guarded {
        require(account.provider == providerId) { "云账号与 OneDrive provider 不匹配" }
        validateUploadName(name)
        require(source.isFile) { "上传源文件不存在" }
        if (source.length() <= SMALL_UPLOAD_LIMIT_BYTES) {
            val response = httpClient.putFile(
                accountId = account.id,
                url = contentUrl(parentId, name),
                source = source,
                mimeType = mimeType ?: "application/octet-stream",
                onProgress = onProgress,
            )
            parseItem(JSONObject(response), parentId)
        } else {
            uploadLarge(account, parentId, name, source, mimeType, onProgress)
        }
    }

    private suspend fun uploadLarge(
        account: CloudAccount,
        parentId: String?,
        name: String,
        source: File,
        mimeType: String?,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): CloudItem {
        val sessionBody = JSONObject()
            .put(
                "item",
                JSONObject()
                    .put("@microsoft.graph.conflictBehavior", "fail")
                    .put("name", name),
            )
            .toString()
        val session = JSONObject(
            httpClient.postJson(account.id, uploadSessionUrl(parentId, name), sessionBody),
        )
        val uploadUrl = session.optString("uploadUrl", "")
        require(uploadUrl.isNotBlank()) { "OneDrive 未返回可恢复上传地址" }

        var offset = 0L
        val totalBytes = source.length()
        while (offset < totalBytes) {
            val endExclusive = minOf(offset + CHUNK_SIZE_BYTES, totalBytes)
            val response = httpClient.putFileRange(
                accountId = null,
                url = uploadUrl,
                source = source,
                start = offset,
                endExclusive = endExclusive,
                totalBytes = totalBytes,
                contentType = mimeType ?: "application/octet-stream",
                extraHeaders = mapOf(
                    "Content-Range" to "bytes $offset-${endExclusive - 1}/$totalBytes",
                ),
                onProgress = onProgress,
            )
            val responseBody = httpClient.responseBody(response)
            if (response.statusCode in 200..299) {
                return parseItem(JSONObject(responseBody), parentId)
            }
            require(response.statusCode == HTTP_ACCEPTED) { "OneDrive 分片上传失败" }
            offset = parseNextOffset(responseBody, endExclusive)
        }
        throw CloudOperationException(
            code = com.dai2010.betterpak.domain.CloudErrorCode.UNKNOWN,
            message = "OneDrive 未返回上传结果",
        )
    }

    private fun contentUrl(parentId: String?, name: String): String {
        val parentPath = parentId?.let { "/items/${Uri.encode(it)}" } ?: "/root"
        return "$GRAPH_ROOT/me/drive$parentPath:/${Uri.encode(name)}:/content"
    }

    private fun uploadSessionUrl(parentId: String?, name: String): String {
        val parentPath = parentId?.let { "/items/${Uri.encode(it)}" } ?: "/root"
        return "$GRAPH_ROOT/me/drive$parentPath:/${Uri.encode(name)}:/createUploadSession"
    }

    private fun parseItem(json: JSONObject, fallbackParentId: String?): CloudItem {
        val folder = json.optJSONObject("folder")
        val file = json.optJSONObject("file")
        return CloudItem(
            id = json.optString("id"),
            name = json.optString("name"),
            parentId = json.optJSONObject("parentReference")?.optString("id", fallbackParentId),
            isDirectory = folder != null,
            size = json.optLong("size", -1L).takeIf { it >= 0L },
            modifiedAtEpochMillis = json.optString("lastModifiedDateTime", null)?.let(::parseInstant),
            mimeType = file?.optString("mimeType", null),
        )
    }

    private fun parseNextOffset(body: String, fallback: Long): Long =
        JSONObject(body).optJSONArray("nextExpectedRanges")
            ?.optString(0)
            ?.substringBefore('-')
            ?.toLongOrNull()
            ?: fallback

    private fun extractSkipToken(nextLink: String): String? =
        Uri.parse(nextLink).getQueryParameter("\$skiptoken")

    private fun validateUploadName(name: String) {
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "云端文件名无效"
        }
    }

    private fun parseInstant(value: String): Long? = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()

    private suspend fun <T> guarded(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(CloudErrorClassifier.wrap(error))
    }

    private fun org.json.JSONArray?.orEmptyObjects(): List<JSONObject> =
        this?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it) } }.orEmpty()

    private companion object {
        const val GRAPH_ROOT = "https://graph.microsoft.com/v1.0"
        const val SMALL_UPLOAD_LIMIT_BYTES = 4L * 1024L * 1024L
        const val CHUNK_SIZE_BYTES = 10L * 320L * 1024L
        const val HTTP_ACCEPTED = 202
    }
}
