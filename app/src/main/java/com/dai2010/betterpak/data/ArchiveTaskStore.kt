package com.dai2010.betterpak.data

import android.content.Context
import android.util.Base64
import com.dai2010.betterpak.domain.ArchiveErrorCode
import com.dai2010.betterpak.domain.ArchiveFormat
import com.dai2010.betterpak.domain.ArchiveTask
import com.dai2010.betterpak.domain.ArchiveTaskKind
import com.dai2010.betterpak.domain.ArchiveTaskStatus

class ArchiveTaskStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(task: ArchiveTask) {
        preferences.edit().putString(taskKey(task.id), encode(task)).apply()
    }

    fun find(id: String): ArchiveTask? = preferences.getString(taskKey(id), null)?.let(::decode)

    fun list(): List<ArchiveTask> = preferences.all
        .filterKeys { it.startsWith(TASK_KEY_PREFIX) }
        .values
        .mapNotNull { value -> (value as? String)?.let(::decode) }
        .sortedByDescending { it.updatedAt }

    fun remove(id: String) {
        preferences.edit().remove(taskKey(id)).apply()
    }

    private fun encode(task: ArchiveTask): String = listOf(
        task.id,
        task.kind.name,
        task.sourceUri,
        task.targetUri,
        task.format.name,
        task.status.name,
        task.errorCode?.name.orEmpty(),
        task.progressSummary,
        task.createdAt.toString(),
        task.updatedAt.toString(),
    ).joinToString(FIELD_SEPARATOR) { value ->
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    }

    private fun decode(value: String): ArchiveTask? = runCatching {
        val fields = value.split(FIELD_SEPARATOR).map { encoded ->
            String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
        }
        require(fields.size == FIELD_COUNT)
        ArchiveTask(
            id = fields[0],
            kind = ArchiveTaskKind.valueOf(fields[1]),
            sourceUri = fields[2],
            targetUri = fields[3],
            format = ArchiveFormat.valueOf(fields[4]),
            status = ArchiveTaskStatus.valueOf(fields[5]),
            errorCode = fields[6].takeIf(String::isNotEmpty)?.let(ArchiveErrorCode::valueOf),
            progressSummary = fields[7],
            createdAt = fields[8].toLong(),
            updatedAt = fields[9].toLong(),
        )
    }.getOrNull()

    private fun taskKey(id: String): String = "$TASK_KEY_PREFIX$id"

    private companion object {
        const val PREFERENCES_NAME = "betterpak_archive_tasks"
        const val TASK_KEY_PREFIX = "task_"
        const val FIELD_SEPARATOR = ":"
        const val FIELD_COUNT = 10
    }
}
