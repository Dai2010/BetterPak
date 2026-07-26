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
import java.net.URLEncoder
import java.time.Instant
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

class GoogleDriveProvider(
    tokenProvider: CloudTokenProvider,
    private val httpClient: CloudHttpClient = CloudHttpClient(tokenProvider),
) : CloudProvider {
    override val providerId: CloudProviderId = CloudProviderId.GOOGLE_DRIVE

    override suspend fun listChildren(
        account: CloudAccount,
        parentId: String?,
        pageToken: String?,
    ): Result<CloudPage> = guarded {
        require(account.provider == providerId) { "云账号与 Google Drive provider 不匹配" }
        val parent = parentId ?: ROOT_ID
        val query = "'$parent' in parents and trashed = false"
        val url = buildString {
            append("$DRIVE_ROOT/files?spaces=drive")
            append("&q=").append(encodeQuery(query))
            append("&pageSize=1000")
            append("&fields=nextPageToken,files(id,name,mimeType,size,modifiedTime,parents)")
            pageToken?.let { append("&pageToken=").append(encodeQuery(it)) }
        }
        val response = JSONObject(httpClient.getJson(account.id, url))
        val items = response.optJSONArray("files").orEmptyObjects().map { item ->
            parseItem(item, parent)
        }
        CloudPage(items, response.optString("nextPageToken", null))
    }

    override suspend fun download(
        account: CloudAccount,
        item: CloudItem,
        destination: File,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): Result<File> = guarded {
        require(account.provider == providerId) { "云账号与 Google Drive provider 不匹配" }
        require(!item.isDirectory) { "不能下载 Google Drive 文件夹" }
        httpClient.download(
            accountId = account.id,
            url = "$DRIVE_ROOT/files/${Uri.encode(item.id)}?alt=media",
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
        require(account.provider == providerId) { "云账号与 Google Drive provider 不匹配" }
        require(name.isNotBlank() && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "云端文件名无效"
        }
        require(source.isFile) { "上传源文件不存在" }
        val metadata = JSONObject().put("name", name)
        parentId?.let { metadata.put("parents", org.json.JSONArray().put(it)) }
        val response = httpClient.postJsonResponse(
            accountId = account.id,
            url = "$UPLOAD_ROOT/files?uploadType=resumable",
            body = metadata.toString(),
        )
        response.requireSuccess(httpClient)
        val uploadUrl = response.location.orEmpty()
        httpClient.closeResponse(response)
        require(uploadUrl.isNotBlank()) { "Google Drive 未返回可恢复上传地址" }
        uploadResumable(
            account = account,
            uploadUrl = uploadUrl,
            source = source,
            parentId = parentId,
            mimeType = mimeType ?: "application/octet-stream",
            onProgress = onProgress,
        )
    }

    private suspend fun uploadResumable(
        account: CloudAccount,
        uploadUrl: String,
        source: File,
        parentId: String?,
        mimeType: String,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): CloudItem {
        var offset = 0L
        val totalBytes = source.length()
        while (offset < totalBytes || totalBytes == 0L) {
            val endExclusive = if (totalBytes == 0L) 0L else minOf(offset + CHUNK_SIZE_BYTES, totalBytes)
            val response = if (totalBytes == 0L) {
                httpClient.putFileRange(
                    accountId = account.id,
                    url = uploadUrl,
                    source = source,
                    start = 0L,
                    endExclusive = 0L,
                    totalBytes = 0L,
                    contentType = mimeType,
                    extraHeaders = mapOf("Content-Range" to "bytes */0"),
                    onProgress = {},
                )
            } else {
                httpClient.putFileRange(
                    accountId = account.id,
                    url = uploadUrl,
                    source = source,
                    start = offset,
                    endExclusive = endExclusive,
                    totalBytes = totalBytes,
                    contentType = mimeType,
                    extraHeaders = mapOf(
                        "Content-Range" to "bytes $offset-${endExclusive - 1}/$totalBytes",
                    ),
                    onProgress = onProgress,
                )
            }
            val responseBody = httpClient.responseBody(response)
            if (response.statusCode in 200..299) {
                return parseItem(JSONObject(responseBody), parentId)
            }
            if (totalBytes == 0L) {
                throw CloudOperationException(
                    code = com.dai2010.betterpak.domain.CloudErrorCode.UNKNOWN,
                    message = "Google Drive 空文件上传未完成",
                )
            }
            require(response.statusCode == HTTP_RESUME_INCOMPLETE) {
                "Google Drive 分片上传失败"
            }
            offset = parseUploadedOffset(response.range, offset, endExclusive)
        }
        throw CloudOperationException(
            code = com.dai2010.betterpak.domain.CloudErrorCode.UNKNOWN,
            message = "Google Drive 未返回上传结果",
        )
    }

    private fun parseItem(json: JSONObject, fallbackParentId: String?): CloudItem {
        val mimeType = json.optString("mimeType", null)
        return CloudItem(
            id = json.optString("id"),
            name = json.optString("name"),
            parentId = json.optJSONArray("parents")?.optString(0, fallbackParentId),
            isDirectory = mimeType == FOLDER_MIME_TYPE,
            size = json.optString("size", null)?.toLongOrNull(),
            modifiedAtEpochMillis = json.optString("modifiedTime", null)?.let(::parseInstant),
            mimeType = mimeType,
        )
    }

    private fun parseUploadedOffset(range: String?, current: Long, fallback: Long): Long =
        range?.substringAfterLast('-')?.toLongOrNull()?.plus(1L) ?: fallback

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

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

    private fun CloudHttpClient.Response.requireSuccess(client: CloudHttpClient) {
        if (statusCode !in 200..299) {
            client.closeResponse(this)
            throw CloudOperationException(
                code = CloudErrorClassifier.fromHttpStatus(statusCode),
                message = "Google Drive 返回 HTTP $statusCode",
            )
        }
    }

    private companion object {
        const val DRIVE_ROOT = "https://www.googleapis.com/drive/v3"
        const val UPLOAD_ROOT = "https://www.googleapis.com/upload/drive/v3"
        const val ROOT_ID = "root"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        const val CHUNK_SIZE_BYTES = 256L * 1024L
        const val HTTP_RESUME_INCOMPLETE = 308
    }
}
