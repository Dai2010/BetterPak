package com.dai2010.betterpak.data

import android.content.Context
import com.dai2010.betterpak.domain.ArchiveTask
import com.dai2010.betterpak.domain.ArchiveTaskCodec

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

    private fun encode(task: ArchiveTask): String = ArchiveTaskCodec.encode(task)

    private fun decode(value: String): ArchiveTask? = ArchiveTaskCodec.decode(value)

    private fun taskKey(id: String): String = "$TASK_KEY_PREFIX$id"

    private companion object {
        const val PREFERENCES_NAME = "betterpak_archive_tasks"
        const val TASK_KEY_PREFIX = "task_"
    }
}
