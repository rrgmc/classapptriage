package com.rrgmc.classapptriage.data

import android.content.Context
import com.rrgmc.classapptriage.triage.Config
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Persists the triage rules as JSON (the Config format in docs/TRIAGE.md) in the
 * app's private files directory. Falls back to [Config.default] when no file
 * exists or it cannot be parsed.
 */
class RulesStore(context: Context) {
    private val file = File(context.filesDir, "rules.json")
    private val _config = MutableStateFlow(load())
    val config: StateFlow<Config> = _config.asStateFlow()

    fun save(config: Config) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(config.toJson())
        tmp.renameTo(file)
        _config.value = config
    }

    fun resetToDefault() = save(Config.default())

    private fun load(): Config = try {
        if (file.exists()) Config.fromJson(file.readText()) else Config.default()
    } catch (e: Exception) {
        Config.default()
    }
}
