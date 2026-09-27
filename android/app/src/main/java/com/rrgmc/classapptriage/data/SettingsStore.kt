package com.rrgmc.classapptriage.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The inbox the user picked from the viewer's entities. */
data class SelectedEntity(val id: Long, val name: String)

/** Non-secret preferences: selected inbox, how many messages to scan, notifications. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _entity = MutableStateFlow(loadEntity())
    val entity: StateFlow<SelectedEntity?> = _entity.asStateFlow()

    /** The email/phone of the last successful login, to prefill the form. */
    var lastLogin: String
        get() = prefs.getString(KEY_LAST_LOGIN, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_LAST_LOGIN, value) }

    private val _notifyImportant = MutableStateFlow(prefs.getBoolean(KEY_NOTIFY, true))

    /** Whether the hourly check notifies new Important messages. On by default. */
    val notifyImportant: StateFlow<Boolean> = _notifyImportant.asStateFlow()

    fun setNotifyImportant(on: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFY, on) }
        _notifyImportant.value = on
    }

    /** Whether the notification permission was requested once already. */
    var notifyPermissionAsked: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_ASKED, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFY_ASKED, value) }

    var maxMessages: Int
        get() = prefs.getInt(KEY_MAX, DEFAULT_MAX)
        set(value) = prefs.edit { putInt(KEY_MAX, value) }

    fun selectEntity(entity: SelectedEntity?) {
        prefs.edit {
            if (entity == null) remove(KEY_ENTITY_ID).remove(KEY_ENTITY_NAME)
            else putLong(KEY_ENTITY_ID, entity.id).putString(KEY_ENTITY_NAME, entity.name)
        }
        _entity.value = entity
    }

    private fun loadEntity(): SelectedEntity? {
        if (!prefs.contains(KEY_ENTITY_ID)) return null
        return SelectedEntity(prefs.getLong(KEY_ENTITY_ID, 0), prefs.getString(KEY_ENTITY_NAME, "").orEmpty())
    }

    companion object {
        const val DEFAULT_MAX = 200
        private const val KEY_MAX = "max_messages"
        private const val KEY_LAST_LOGIN = "last_login"
        private const val KEY_ENTITY_ID = "entity_id"
        private const val KEY_ENTITY_NAME = "entity_name"
        private const val KEY_NOTIFY = "notify_important"
        private const val KEY_NOTIFY_ASKED = "notify_permission_asked"
    }
}
