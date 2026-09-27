package com.rrgmc.classapptriage.ui.messages

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Media
import com.rrgmc.classapptriage.api.MessageDetail
import com.rrgmc.classapptriage.api.MessageStatus
import com.rrgmc.classapptriage.api.UnauthorizedException
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.formatElapsed
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

data class MessageDetailState(
    val loading: Boolean = true,
    val detail: MessageDetail? = null,
    val error: String? = null,
)

/** Loads the full message (MessageQuery). Status changes go through the list view model. */
class MessageDetailViewModel(private val container: AppContainer, private val id: Long) : ViewModel() {
    private val _state = MutableStateFlow(MessageDetailState())
    val state: StateFlow<MessageDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val client = container.client() ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                MessageDetailState(loading = false, detail = client.message(id))
            } catch (e: UnauthorizedException) {
                container.sessionExpired()
                return@launch
            } catch (e: Exception) {
                MessageDetailState(loading = false, error = e.userMessage(container.res))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(id: Long, listVm: MessagesViewModel, onBack: () -> Unit) {
    val vm = appViewModel(key = "message-$id") { MessageDetailViewModel(it, id) }
    val state by vm.state.collectAsState()
    val list by listVm.state.collectAsState()
    val listed = remember(list) { listVm.find(id) }
    val detail = state.detail
    var confirmDelete by remember { mutableStateOf(false) }

    // In-app changes win over the server's unread folder (see MessagesState.isRead).
    val readState = listed?.message?.let { list.isRead(it) }
    val read = readState ?: true

    // Opening a message marks it read, once per opening: after "Mark unread"
    // it stays unread while this screen is shown (also across rotation).
    var autoMarked by rememberSaveable(id) { mutableStateOf(false) }
    LaunchedEffect(id, readState, list.working) {
        if (!autoMarked && listed != null && readState != true && !list.working) {
            autoMarked = true
            listVm.apply(listOf(id), MessageStatus.READ, quiet = true)
        }
    }
    val sender = detail?.entity ?: listed?.message?.entity

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(sender?.fullname.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    if (read) {
                        IconButton(onClick = { listVm.apply(listOf(id), MessageStatus.UNREAD) }, enabled = !list.working) {
                            Icon(Icons.Filled.MarkEmailUnread, stringResource(R.string.action_mark_unread))
                        }
                    } else {
                        IconButton(onClick = { listVm.apply(listOf(id), MessageStatus.READ) }, enabled = !list.working) {
                            Icon(Icons.Filled.MarkEmailRead, stringResource(R.string.action_mark_read))
                        }
                    }
                    IconButton(onClick = { confirmDelete = true }, enabled = !list.working) {
                        Icon(Icons.Filled.Delete, stringResource(R.string.delete))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (list.working) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                detail == null && state.loading -> Box(Modifier.fillMaxSize()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                detail == null -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(state.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.size(12.dp))
                    Button(onClick = vm::load) { Text(stringResource(R.string.retry)) }
                }
                else -> MessageBody(detail, read, inboxId = list.entityId, inboxName = list.entityName)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.dialog_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.confirm_delete_selected, 1, 1)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    listVm.apply(listOf(id), MessageStatus.DELETED, onSuccess = onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MessageBody(m: MessageDetail, read: Boolean, inboxId: Long, inboxName: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(
                name = m.entity?.fullname.orEmpty(),
                pictureUri = m.entity?.picture?.uri,
                unread = !read,
                background = MaterialTheme.colorScheme.surface,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(m.entity?.fullname.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                recipientsText(m, inboxId, inboxName)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatElapsed(m.sentAt ?: m.created, Instant.now()), style = MaterialTheme.typography.labelMedium, color = muted)
                m.label?.takeIf { it.title.isNotEmpty() }?.let {
                    Spacer(Modifier.size(4.dp))
                    LabelChip(it.title, it.color)
                }
            }
        }
        val title = m.subject?.takeIf { it.isNotBlank() } ?: m.summary
        Text(title, style = MaterialTheme.typography.headlineSmall)
        HorizontalDivider()
        val content = m.content.orEmpty()
        SelectionContainer {
            when {
                content.isBlank() -> Text(stringResource(R.string.detail_empty), color = muted)
                looksLikeHtml(content) -> Text(AnnotatedString.fromHtml(content), style = MaterialTheme.typography.bodyLarge)
                else -> Text(content, style = MaterialTheme.typography.bodyLarge)
            }
        }
        if (m.medias.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.detail_attachments), style = MaterialTheme.typography.titleSmall)
            m.medias.forEach { MediaItem(it) }
        }
    }
}

private fun looksLikeHtml(s: String) = Regex("<(p|br|div|span|a|b|i|strong|em|ul|ol|li|h[1-6])\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)

/** Images are shown inline; every attachment opens in an external app on tap. */
@Composable
private fun MediaItem(media: Media) {
    val context = LocalContext.current
    val failed = stringResource(R.string.detail_open_failed)
    val open: () -> Unit = {
        val uri = media.uri
        try {
            if (uri.isNullOrBlank()) throw ActivityNotFoundException()
            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
        }
    }
    if (media.type == "IMAGE" && !media.uri.isNullOrBlank()) {
        AsyncImage(
            model = media.uri,
            contentDescription = media.filename,
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 80.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = open),
        )
        return
    }
    val icon = when (media.type) {
        "IMAGE" -> Icons.Outlined.Image
        "VIDEO" -> Icons.Outlined.Videocam
        "AUDIO" -> Icons.Outlined.Mic
        else -> Icons.Outlined.AttachFile
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().clickable(onClick = open),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(12.dp))
            Text(
                media.filename ?: media.uri.orEmpty(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (media.size > 0) {
                Spacer(Modifier.width(8.dp))
                Text(formatSize(media.size), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

/**
 * "To:" line. `toEntity` is only one recipient, so for received messages show
 * the recipient groups (tags) or this inbox, plus how many others got it.
 */
@Composable
private fun recipientsText(m: MessageDetail, inboxId: Long, inboxName: String): String? {
    val sent = inboxId != 0L && m.entity?.id == inboxId
    if (sent || inboxName.isEmpty()) {
        val to = m.toEntity?.fullname?.takeIf { it.isNotEmpty() } ?: return null
        val others = m.recipientsCount - 1
        return if (others > 0) pluralStringResource(R.plurals.detail_to_more, others, to, others)
        else stringResource(R.string.detail_to, to)
    }
    if (m.tags.isNotEmpty()) return stringResource(R.string.detail_to, m.tags.joinToString(", ") { it.name })
    val others = m.recipientsCount - 1
    return if (others > 0) pluralStringResource(R.plurals.detail_to_more, others, inboxName, others)
    else stringResource(R.string.detail_to, inboxName)
}
