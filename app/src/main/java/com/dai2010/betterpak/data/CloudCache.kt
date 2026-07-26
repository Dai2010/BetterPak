package com.dai2010.betterpak.data

import android.content.Context
import java.io.File

class CloudCache(context: Context) {
    private val directory = File(context.applicationContext.cacheDir, DIRECTORY_NAME)

    init {
        cleanupExpired()
    }

    fun createDownloadFile(name: String): File {
        require(name.isNotBlank()) { "云端文件名不能为空" }
        require(!name.contains('/') && !name.contains('\\') && !name.contains('\u0000')) {
            "云端文件名无效"
        }
        require(directory.isDirectory || directory.mkdirs()) { "无法创建云端缓存目录" }
        return File(directory, "${System.currentTimeMillis()}-${name}.part")
    }

    fun completePart(file: File): File {
        require(file.parentFile?.canonicalFile == directory.canonicalFile) { "云端缓存路径无效" }
        val completed = File(directory, file.name.removeSuffix(".part"))
        require(file.renameTo(completed)) { "无法完成云端缓存写入" }
        return completed
    }

    fun clear() {
        directory.deleteRecursively()
    }

    private fun cleanupExpired() {
        val expiration = System.currentTimeMillis() - MAX_AGE_MILLIS
        directory.listFiles().orEmpty()
            .filter { it.lastModified() < expiration }
            .forEach { it.deleteRecursively() }
    }

    private companion object {
        const val DIRECTORY_NAME = "betterpak-cloud"
        const val MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}
