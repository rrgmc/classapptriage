package com.rrgmc.classapptriage.ui.messages

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Assignment
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Drafts
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Paid
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.api.MessageStatus
import com.rrgmc.classapptriage.ui.formatElapsed
import com.rrgmc.classapptriage.ui.parseColor
import java.time.Instant

/**
 * One message row, modeled on the official app: avatar with an unread dot,
 * bold text while unread, a received/sent icon with the other party's name,
 * the label, the age and icons for attachments and interactive content.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: Message,
    rule: String?,
    now: Instant,
    localStatus: MessageStatus?,
    /** True/false when known (see [MessagesState.isRead]), null when unknown. */
    readState: Boolean?,
    inboxId: Long,
    selecting: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val read = readState == true
    val unread = readState == false
    // Messages sent from the selected inbox show the recipient, like the official app.
    val sent = inboxId != 0L && message.entity?.id == inboxId
    val party = if (sent) message.toEntity?.fullname else message.entity?.fullname
    val bg = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        // Read rows get a faint gray tint (works in light and dark themes).
        read -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f).compositeOver(MaterialTheme.colorScheme.surface)
        else -> MaterialTheme.colorScheme.surface
    }
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Row(
        Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = { if (selecting) onToggle() else onOpen() }, onLongClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
            Spacer(Modifier.width(4.dp))
        }
        Avatar(
            name = message.entity?.fullname.orEmpty(),
            pictureUri = message.entity?.picture?.uri,
            unread = unread,
            background = bg,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.summary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // Fills the remaining width so the label and time stay right-aligned.
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(formatElapsed(message.sentAt ?: message.created, now), style = MaterialTheme.typography.labelMedium, color = muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (sent) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Drafts,
                    contentDescription = stringResource(if (sent) R.string.row_sent else R.string.row_received),
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    party.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ContentIcons(message, muted)
            }
            // Optional third line: label pill, classifying rule and in-app status.
            val label = message.label?.takeIf { it.title.isNotEmpty() }
            if (label != null || rule != null || localStatus != null) Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                label?.let { LabelChip(it.title, it.color) }
                rule?.let { Text(stringResource(R.string.row_rule, it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                when (localStatus) {
                    MessageStatus.READ -> Text(stringResource(R.string.row_read), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    MessageStatus.UNREAD -> Text(stringResource(R.string.row_unread), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    else -> {}
                }
            }
        }
    }
}

/** Icons for attachments and interactive content, as in the official app. */
@Composable
private fun ContentIcons(m: Message, tint: Color) {
    val icons = buildList<Pair<ImageVector, Int>> {
        if (m.imagesCount > 0) add(Icons.Outlined.Image to R.string.row_images)
        if (m.videosCount > 0) add(Icons.Outlined.Videocam to R.string.row_videos)
        if (m.audiosCount > 0) add(Icons.Outlined.Mic to R.string.row_audios)
        if (m.filesCount > 0) add(Icons.Outlined.AttachFile to R.string.row_files)
        if (m.surveysCount > 0) add(Icons.Outlined.BarChart to R.string.row_surveys)
        if (m.reportsCount > 0) add(Icons.Outlined.Assignment to R.string.row_reports)
        if (m.formsCount > 0) add(Icons.Outlined.ListAlt to R.string.row_forms)
        if (m.commitmentsCount > 0) add(Icons.Outlined.Event to R.string.row_commitments)
        if (m.chargesCount > 0) add(Icons.Outlined.Paid to R.string.row_charges)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        icons.forEach { (icon, desc) ->
            Icon(icon, contentDescription = stringResource(desc), tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

private val avatarPalette = listOf(
    Color(0xFFE35656), Color(0xFF2E6BD6), Color(0xFF34A853), Color(0xFFF08C00),
    Color(0xFF8E44AD), Color(0xFF00897B), Color(0xFF6D4C41), Color(0xFFC2185B),
)

/** The sender's picture, or colored initials; a blue dot marks unread. */
@Composable
internal fun Avatar(name: String, pictureUri: String?, unread: Boolean, background: Color) {
    val size = 48.dp
    Box(Modifier.size(size)) {
        val initials: @Composable () -> Unit = {
            val color = avatarPalette[Math.floorMod(name.hashCode(), avatarPalette.size)]
            Box(
                Modifier.size(size).clip(CircleShape).background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(initialsOf(name), color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        if (pictureUri.isNullOrBlank()) {
            initials()
        } else {
            SubcomposeAsyncImage(
                model = pictureUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape),
                loading = { initials() },
                error = { initials() },
            )
        }
        if (unread) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(14.dp)
                    .border(2.dp, background, CircleShape)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1A73E8)),
            )
        }
    }
}

/** Up to two initials from the first and last words of [name]. */
internal fun initialsOf(name: String): String {
    val words = name.split(' ', '-', '.').filter { w -> w.firstOrNull()?.isLetterOrDigit() == true }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words.first().take(1) + words.last().take(1)).uppercase()
    }
}

/** A label pill in the label's own color, as in the official app. */
@Composable
internal fun LabelChip(title: String, color: String?) {
    val c = parseColor(color)
    val bg = c ?: MaterialTheme.colorScheme.secondaryContainer
    val fg = when {
        c == null -> MaterialTheme.colorScheme.onSecondaryContainer
        c.luminance() > 0.5f -> Color.Black
        else -> Color.White
    }
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp).widthIn(max = MaxLabelWidth),
        )
    }
}

/** Keeps very long labels from pushing the rest of the third line away. */
private val MaxLabelWidth = 180.dp
