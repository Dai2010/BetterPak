package com.dai2010.betterpak.data

import com.dai2010.betterpak.domain.CloudErrorClassifier
import com.dai2010.betterpak.domain.CloudErrorCode
import com.dai2010.betterpak.domain.CloudOperationException
import com.dai2010.betterpak.domain.CloudTokenProvider
import com.dai2010.betterpak.domain.CloudTransferPolicy
import com.dai2010.betterpak.domain.CloudTransferProgress
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class CloudHttpClient(
    private val tokenProvider: CloudTokenProvider,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) {
    suspend fun getJson(accountId: String, url: String): String =
        withRetry { authorizedRequest(accountId, "GET", url).requireSuccess().bodyAsString() }

    suspend fun postJson(accountId: String, url: String, body: String): String =
        responseBody(postJsonResponse(accountId, url, body).also { it.requireSuccess() })

    suspend fun postJsonResponse(accountId: String, url: String, body: String): Response =
        withRetry {
            authorizedRequest(
                accountId = accountId,
                method = "POST",
                url = url,
                contentType = "application/json; charset=utf-8",
                contentLength = body.toByteArray(Charsets.UTF_8).size.toLong(),
                writeBody = { output -> output.write(body.toByteArray(Charsets.UTF_8)) },
            )
        }

    suspend fun putFile(
        accountId: String,
        url: String,
        source: File,
        mimeType: String,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): String = withRetry {
        require(source.isFile) { "上传源文件不存在" }
        val totalBytes = source.length()
        authorizedRequest(
            accountId = accountId,
            method = "PUT",
            url = url,
            contentType = mimeType,
            contentLength = totalBytes,
            writeBody = { output -> copyFile(source, output, 0L, totalBytes, totalBytes, onProgress) },
        ).requireSuccess().bodyAsString()
    }

    suspend fun download(
        accountId: String,
        url: String,
        destination: File,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): File = withRetry {
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) {
            "无法创建云端下载目录"
        }
        val temporary = File(
            destination.parentFile,
            ".${destination.name}.${UUID.randomUUID()}.part",
        )
        var response: Response? = null
        try {
            val currentResponse = authorizedRequest(accountId, "GET", url)
            response = currentResponse
            currentResponse.requireSuccess()
            require(
                currentResponse.contentLength < 0L ||
                    currentResponse.contentLength <= CloudTransferPolicy.MAX_LOCAL_DOWNLOAD_BYTES,
            ) {
                "云端文件超过本地缓存限制"
            }
            withContext(Dispatchers.IO) {
                currentResponse.connection.inputStream.use { input ->
                    temporary.outputStream().use { output ->
                        val copied = copyStream(
                            input,
                            output,
                            currentResponse.contentLength,
                            0L,
                            maxBytes = CloudTransferPolicy.MAX_LOCAL_DOWNLOAD_BYTES,
                            enforceLimit = true,
                            onProgress = onProgress,
                        )
                        if (currentResponse.contentLength >= 0L && copied != currentResponse.contentLength) {
                            throw CloudOperationException(
                                CloudErrorCode.NETWORK_UNAVAILABLE,
                                "云端下载内容不完整",
                            )
                        }
                    }
                }
            }
            require(temporary.renameTo(destination)) { "无法完成云端下载" }
            destination
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        } finally {
            response?.close()
        }
    }

    suspend fun putFileRange(
        accountId: String?,
        url: String,
        source: File,
        start: Long,
        endExclusive: Long,
        totalBytes: Long,
        contentType: String,
        extraHeaders: Map<String, String> = emptyMap(),
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): Response = withRetry {
        require(
            start >= 0L && (
                (totalBytes == 0L && start == 0L && endExclusive == 0L) ||
                    endExclusive in (start + 1L)..totalBytes
                ),
        ) {
            "云端分片范围无效"
        }
        val length = endExclusive - start
        val response = request(
            token = accountId?.let { tokenProvider.accessToken(it).getOrThrow().value },
            method = "PUT",
            url = url,
            contentType = contentType,
            contentLength = length,
            extraHeaders = extraHeaders,
            writeBody = { output -> copyFile(source, output, start, length, totalBytes, onProgress) },
        )
        if (response.statusCode !in 200..299 && response.statusCode != HTTP_RESUME_INCOMPLETE) {
            response.close()
            throw httpError(response.statusCode)
        }
        response
    }

    private suspend fun authorizedRequest(
        accountId: String,
        method: String,
        url: String,
        contentType: String? = null,
        contentLength: Long? = null,
        writeBody: (suspend (OutputStream) -> Unit)? = null,
    ): Response {
        val token = tokenProvider.accessToken(accountId).getOrThrow()
        if (token.value.isBlank()) {
            throw CloudOperationException(CloudErrorCode.AUTH_REQUIRED, "云端账号未授权")
        }
        return request(
            token = token.value,
            method = method,
            url = url,
            contentType = contentType,
            contentLength = contentLength,
            writeBody = writeBody,
        )
    }

    private suspend fun request(
        token: String?,
        method: String,
        url: String,
        contentType: String? = null,
        contentLength: Long? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        writeBody: (suspend (OutputStream) -> Unit)? = null,
    ): Response = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = method
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            contentType?.let { connection.setRequestProperty("Content-Type", it) }
            contentLength?.let {
                connection.setFixedLengthStreamingMode(it)
                connection.setRequestProperty("Content-Length", it.toString())
            }
            extraHeaders.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (writeBody != null) {
                connection.doOutput = true
                val output = connection.outputStream
                try {
                    writeBody(output)
                } finally {
                    output.close()
                }
            }
            val statusCode = connection.responseCode
            val contentLengthHeader = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            Response(
                connection = connection,
                statusCode = statusCode,
                contentLength = contentLengthHeader,
                location = connection.getHeaderField("Location"),
                range = connection.getHeaderField("Range"),
            )
        } catch (error: Throwable) {
            connection.disconnect()
            throw error
        }
    }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val code = CloudErrorClassifier.classify(error)
                val canRetry = code.retryable &&
                    code != CloudErrorCode.AUTH_EXPIRED &&
                    code != CloudErrorCode.CONFLICT &&
                    attempt < MAX_RETRY_ATTEMPTS - 1
                if (!canRetry) throw error
                delay(RETRY_DELAY_MILLIS * (attempt + 1L))
                coroutineContext.ensureActive()
                attempt++
            }
        }
    }

    private suspend fun copyFile(
        source: File,
        output: OutputStream,
        start: Long,
        length: Long,
        totalBytes: Long,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ) {
        val input = FileInputStream(source)
        try {
            skipFully(input, start)
            copyStream(
                input,
                output,
                totalBytes,
                start,
                maxBytes = length,
                onProgress = onProgress,
            )
        } finally {
            input.close()
        }
    }

    private suspend fun copyStream(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        initialBytes: Long,
        maxBytes: Long = Long.MAX_VALUE,
        enforceLimit: Boolean = false,
        onProgress: suspend (CloudTransferProgress) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_SIZE)
        var transferred = initialBytes
        var remaining = maxBytes.takeUnless { it < 0L } ?: Long.MAX_VALUE
        while (true) {
            coroutineContext.ensureActive()
            val requested = minOf(buffer.size.toLong(), remaining).toInt()
            if (requested == 0) {
                if (enforceLimit && input.read() >= 0) {
                    throw CloudOperationException(
                        CloudErrorCode.LIMIT_EXCEEDED,
                        "云端文件超过本地缓存限制",
                    )
                }
                break
            }
            val read = input.read(buffer, 0, requested)
            if (read < 0) break
            if (read == 0) continue
            output.write(buffer, 0, read)
            transferred += read
            remaining -= read
            onProgress(CloudTransferProgress(transferred, totalBytes))
        }
        return transferred
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw CloudOperationException(CloudErrorCode.INVALID_REQUEST, "上传源文件长度不足")
            }
        }
    }

    private fun httpError(statusCode: Int): CloudOperationException =
        CloudOperationException(
            code = CloudErrorClassifier.fromHttpStatus(statusCode),
            message = "云端服务返回 HTTP $statusCode",
        )

    private fun Response.requireSuccess(): Response {
        if (statusCode !in 200..299) {
            close()
            throw httpError(statusCode)
        }
        return this
    }

    private fun Response.bodyAsString(): String {
        if (statusCode == HTTP_RESUME_INCOMPLETE) {
            close()
            return ""
        }
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            close()
        }
    }

    private fun Response.close() {
        connection.disconnect()
    }

    internal fun closeResponse(response: Response) {
        response.close()
    }

    internal fun responseBody(response: Response): String = response.bodyAsString()

    internal fun responseHeader(response: Response, name: String): String? =
        response.connection.getHeaderField(name)

    internal data class Response(
        val connection: HttpURLConnection,
        val statusCode: Int,
        val contentLength: Long,
        val location: String?,
        val range: String?,
    )

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        const val MAX_RETRY_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 400L
        const val HTTP_RESUME_INCOMPLETE = 308
    }
}
