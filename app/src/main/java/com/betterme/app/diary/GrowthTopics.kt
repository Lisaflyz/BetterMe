package com.betterme.app.diary

import java.util.Locale

/** The same key is used by the picker, grouped feed and restored expansion state. */
object GrowthTopics {
    val presets = listOf("Android", "AI", "英语")
    const val MAX_LENGTH = 30

    fun normalize(value: String): String {
        val trimmed = value.trim().replace(Regex("\\s+"), " ")
        return presets.firstOrNull { it.equals(trimmed, ignoreCase = true) } ?: trimmed
    }

    fun key(value: String): String = normalize(value).lowercase(Locale.ROOT)

    fun choices(records: List<DiaryEntry>, current: String): List<String> =
        (presets + records.map { it.topic } + current)
            .map(::normalize).filter { it.isNotBlank() }.distinctBy(::key)
}
