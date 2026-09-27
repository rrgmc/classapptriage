package com.rrgmc.classapptriage.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ElapsedTest {
    private val now = Instant.parse("2026-09-27T12:00:00Z")

    @Test
    fun formats() {
        assertEquals("<1h", formatElapsed("2026-09-27T11:30:00Z", now))
        assertEquals("1h", formatElapsed("2026-09-27T11:00:00Z", now))
        assertEquals("23h", formatElapsed("2026-09-26T12:00:01Z", now))
        assertEquals("1d", formatElapsed("2026-09-26T12:00:00Z", now))
        assertEquals("10d", formatElapsed("2026-09-17T09:00:00.000-03:00", now))
        assertEquals("<1h", formatElapsed("2026-09-27T13:00:00Z", now)) // clock skew
        assertEquals("", formatElapsed(null, now))
        assertEquals("", formatElapsed("garbage", now))
    }
}
