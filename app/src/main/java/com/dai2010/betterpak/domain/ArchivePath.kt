package com.dai2010.betterpak.domain

object ArchivePath {
    fun normalize(rawPath: String?): String? {
        val raw = rawPath?.replace('\\', '/') ?: return null
        if (raw.isBlank() || raw.contains('\u0000') || raw.startsWith('/') || raw.matches(Regex("^[A-Za-z]:.*"))) {
            return null
        }
        val parts = raw.split('/')
        if (parts.any { it == ".." }) return null
        val normalized = parts.filter { it.isNotEmpty() && it != "." }
        if (normalized.isEmpty() || normalized.any { it == ".." || it.contains('\u0000') }) return null
        return normalized.joinToString("/")
    }
}
