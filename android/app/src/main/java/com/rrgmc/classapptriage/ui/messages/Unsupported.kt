package com.rrgmc.classapptriage.ui.messages

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Message

/**
 * Interactive items attached to a message that this app doesn't show
 * (docs/API.md §10.2). Reports are shown, so they are not listed here.
 */
enum class UnsupportedContent(@StringRes val label: Int, val icon: ImageVector) {
    SURVEY(R.string.row_surveys, Icons.Outlined.BarChart),
    FORM(R.string.row_forms, Icons.Outlined.ListAlt),
    COMMITMENT(R.string.row_commitments, Icons.Outlined.Event),
    CHARGE(R.string.row_charges, Icons.Outlined.Paid),
}

/** The unsupported items of [m], from the list's counts; empty when unknown. */
fun unsupportedContent(m: Message?): List<UnsupportedContent> = buildList {
    if (m == null) return@buildList
    if (m.surveysCount > 0) add(UnsupportedContent.SURVEY)
    if (m.formsCount > 0) add(UnsupportedContent.FORM)
    if (m.commitmentsCount > 0) add(UnsupportedContent.COMMITMENT)
    if (m.chargesCount > 0) add(UnsupportedContent.CHARGE)
}

/** Object replacement character: what an embedded object the text can't draw turns into (shown as "OBJ"). */
const val OBJECT_CHAR = '￼'

/** This text without [OBJECT_CHAR]s, keeping the styles of the rest. */
fun AnnotatedString.withoutObjects(): AnnotatedString {
    if (OBJECT_CHAR !in text) return this
    return buildAnnotatedString {
        var start = 0
        while (true) {
            val i = text.indexOf(OBJECT_CHAR, start)
            if (i < 0) break
            append(this@withoutObjects.subSequence(start, i))
            start = i + 1
        }
        append(this@withoutObjects.subSequence(start, text.length))
    }
}
