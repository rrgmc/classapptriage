package com.rrgmc.classapptriage.ui

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Formats the time elapsed since the ISO-8601 timestamp [iso] as hours
 * ("<1h", "5h") under a day, and whole days ("3d") otherwise. Returns "" when
 * the timestamp is missing or unparseable.
 */
fun formatElapsed(iso: String?, now: Instant = Instant.now()): String {
    if (iso.isNullOrEmpty()) return ""
    val then = try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (e: Exception) {
        return ""
    }
    val elapsed = Duration.between(then, now).coerceAtLeast(Duration.ZERO)
    return when {
        elapsed.toHours() < 1 -> "<1h"
        elapsed.toDays() < 1 -> "${elapsed.toHours()}h"
        else -> "${elapsed.toDays()}d"
    }
}

private fun Duration.coerceAtLeast(min: Duration) = if (this < min) min else this
