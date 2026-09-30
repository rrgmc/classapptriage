package com.rrgmc.classapptriage.ui.messages

import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.rrgmc.classapptriage.api.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class UnsupportedTest {
    @Test
    fun removesObjectsKeepingStyles() {
        val s = buildAnnotatedString {
            append("Pay ￼")
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("now") }
            append("￼")
        }
        val out = s.withoutObjects()
        assertEquals("Pay now", out.text)
        assertEquals(1, out.spanStyles.size)
        assertEquals(4, out.spanStyles[0].start)
        assertEquals(7, out.spanStyles[0].end)
    }

    @Test
    fun keepsTextWithoutObjects() {
        val s = buildAnnotatedString { append("plain") }
        assertSame(s, s.withoutObjects())
    }

    @Test
    fun listsUnsupportedFromCounts() {
        assertEquals(emptyList<UnsupportedContent>(), unsupportedContent(null))
        assertEquals(emptyList<UnsupportedContent>(), unsupportedContent(Message(reportsCount = 1, filesCount = 2)))
        assertEquals(
            listOf(UnsupportedContent.SURVEY, UnsupportedContent.CHARGE),
            unsupportedContent(Message(chargesCount = 1, surveysCount = 2)),
        )
    }
}
