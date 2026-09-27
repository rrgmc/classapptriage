package com.rrgmc.classapptriage.notify

import android.content.Context
import androidx.core.content.edit

/**
 * The unread message ids seen by the last check of an inbox, so each message
 * is notified at most once while it stays unread.
 */
class NotifyState(context: Context) {
    private val prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    /** The ids seen for [entityId], or null if it was never checked. */
    fun seen(entityId: Long): Set<Long>? {
        if (!prefs.contains(KEY_ENTITY) || prefs.getLong(KEY_ENTITY, 0) != entityId) return null
        return prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
    }

    fun save(entityId: Long, ids: Set<Long>) = prefs.edit {
        putLong(KEY_ENTITY, entityId)
        putStringSet(KEY_SEEN, ids.mapTo(HashSet()) { it.toString() })
    }

    fun clear() = prefs.edit { clear() }

    private companion object {
        const val KEY_ENTITY = "entity_id"
        const val KEY_SEEN = "seen"
    }
}
