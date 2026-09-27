package com.rrgmc.classapptriage.ui.messages

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.api.MessageStatus
import com.rrgmc.classapptriage.notify.ImportantNotifier
import com.rrgmc.classapptriage.triage.Category
import com.rrgmc.classapptriage.ui.appContainer
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.formatElapsed
import com.rrgmc.classapptriage.ui.rules.RuleDraft
import kotlinx.coroutines.delay
import java.time.Instant

/** A pending confirmation for a status change; [text] is a plural resource. */
private data class PendingAction(val ids: List<Long>, val status: MessageStatus, @PluralsRes val text: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onSwitchInbox: () -> Unit,
    onRules: () -> Unit,
    onCreateRule: (RuleDraft) -> Unit,
    onOpen: (Long) -> Unit,
) {
    val vm = appViewModel { MessagesViewModel(it) }
    val state by vm.state.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Category.ROUTINE) }
    // Show Important when Routine becomes empty (after loading, or after its
    // messages were deleted). Keyed on emptiness, so tapping an empty Routine
    // tab by hand does not bounce back.
    val routineEmpty = state.routine.isEmpty()
    val loaded = !state.loading && !state.refreshing
    LaunchedEffect(routineEmpty, loaded) {
        if (loaded && routineEmpty && state.important.isNotEmpty() && tab == Category.ROUTINE) {
            tab = Category.IMPORTANT
        }
    }
    var pending by remember { mutableStateOf<PendingAction?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var editMax by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val container = appContainer()
    val notifyOn by container.settings.notifyImportant.collectAsState()
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        container.setNotifyImportant(granted)
    }
    // Notifications are on by default: ask for the permission (Android 13+)
    // once; declining turns them off. Later changes go through the menu.
    LaunchedEffect(Unit) {
        if (notifyOn && !ImportantNotifier.canNotify(context) && !container.settings.notifyPermissionAsked) {
            container.settings.notifyPermissionAsked = true
            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    // Ticks every minute so the elapsed times on the rows stay current.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
        }
    }

    LaunchedEffect(state.error, state.info) {
        val text = state.error ?: state.info ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        vm.dismissMessages()
    }

    val visible = state.category(tab)
    val selected = state.important.plus(state.routine).filter { it.message.id in state.selection }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    navigationIcon = {
                        IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_clear_selection)) }
                    },
                    title = { Text(pluralStringResource(R.plurals.selected_count, state.selection.size, state.selection.size)) },
                    actions = {
                        IconButton(onClick = { vm.selectAll(visible.map { it.message.id }) }) {
                            Icon(Icons.Filled.SelectAll, stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = { vm.apply(state.selection, MessageStatus.READ) }, enabled = !state.working) {
                            Icon(Icons.Filled.MarkEmailRead, stringResource(R.string.action_mark_read))
                        }
                        IconButton(
                            onClick = {
                                pending = PendingAction(
                                    state.selection.toList(), MessageStatus.DELETED, R.plurals.confirm_delete_selected,
                                )
                            },
                            enabled = !state.working,
                        ) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_mark_unread)) },
                                    leadingIcon = { Icon(Icons.Filled.MarkEmailUnread, null) },
                                    onClick = {
                                        menuOpen = false
                                        vm.apply(state.selection, MessageStatus.UNREAD)
                                    },
                                )
                                if (selected.size == 1) {
                                    val m = selected.first().message
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.action_create_rule)) },
                                        leadingIcon = { Icon(Icons.Filled.Tune, null) },
                                        onClick = {
                                            menuOpen = false
                                            vm.clearSelection()
                                            onCreateRule(
                                                RuleDraft(
                                                    group = "routine",
                                                    index = -1,
                                                    label = m.label?.title?.takeIf { it.isNotEmpty() },
                                                    sender = m.entity?.fullname?.takeIf { it.isNotEmpty() },
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(stringResource(R.string.messages_title))
                            if (state.entityName.isNotEmpty()) {
                                Text(state.entityName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.refresh() }) { Icon(Icons.Filled.Refresh, stringResource(R.string.action_refresh)) }
                        IconButton(onClick = onRules) { Icon(Icons.Filled.Tune, stringResource(R.string.action_rules)) }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_switch_inbox)) },
                                    leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                    onClick = { menuOpen = false; onSwitchInbox() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_messages_to_scan, state.maxMessages)) },
                                    leadingIcon = { Icon(Icons.Filled.DoneAll, null) },
                                    onClick = { menuOpen = false; editMax = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_notify_important)) },
                                    leadingIcon = { Icon(Icons.Filled.Notifications, null) },
                                    trailingIcon = {
                                        Checkbox(checked = notifyOn && ImportantNotifier.canNotify(context), onCheckedChange = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        when {
                                            notifyOn && ImportantNotifier.canNotify(context) -> container.setNotifyImportant(false)
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ImportantNotifier.canNotify(context) ->
                                                notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                            else -> container.setNotifyImportant(true)
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.log_out)) },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                                    onClick = { menuOpen = false; vm.logout() },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                Tab(
                    selected = tab == Category.IMPORTANT,
                    onClick = { tab = Category.IMPORTANT },
                    text = { TabTitle(R.string.tab_important, state.important.size, state.unreadCount(state.important)) },
                )
                Tab(
                    selected = tab == Category.ROUTINE,
                    onClick = { tab = Category.ROUTINE },
                    text = { TabTitle(R.string.tab_routine, state.routine.size, state.unreadCount(state.routine)) },
                )
            }
            if (state.working) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (tab == Category.ROUTINE && !state.selecting && state.routine.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val ids = state.routine.map { it.message.id }
                    OutlinedButton(
                        onClick = { pending = PendingAction(ids, MessageStatus.READ, R.plurals.confirm_read_all_routine) },
                        enabled = !state.working,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.MarkEmailRead, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_mark_all_read))
                    }
                    Button(
                        onClick = { pending = PendingAction(ids, MessageStatus.DELETED, R.plurals.confirm_delete_all_routine) },
                        enabled = !state.working,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Delete, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete_all))
                    }
                }
            }

            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    state.loading && visible.isEmpty() ->
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    visible.isEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                        item {
                            Text(
                                stringResource(if (tab == Category.ROUTINE) R.string.messages_none_routine else R.string.messages_none_important),
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (state.hasMore) item { LoadMore(state.loading, vm::loadMore) }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(visible, key = { it.message.id }) { item ->
                            MessageRow(
                                message = item.message,
                                rule = item.result.rule,
                                now = now,
                                localStatus = state.localStatus[item.message.id],
                                readState = state.isRead(item.message),
                                inboxId = state.entityId,
                                selecting = state.selecting,
                                selected = item.message.id in state.selection,
                                onToggle = { vm.toggleSelection(item.message.id) },
                                onOpen = { onOpen(item.message.id) },
                            )
                            HorizontalDivider()
                        }
                        if (state.hasMore) item { LoadMore(state.loading, vm::loadMore) }
                    }
                }
            }
        }
    }

    pending?.let { p ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(if (p.status == MessageStatus.DELETED) R.string.dialog_delete_title else R.string.dialog_read_title)) },
            text = { Text(pluralStringResource(p.text, p.ids.size, p.ids.size)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    vm.apply(p.ids, p.status)
                }) { Text(stringResource(if (p.status == MessageStatus.DELETED) R.string.delete else R.string.action_mark_read)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (editMax) {
        var text by remember { mutableStateOf(state.maxMessages.toString()) }
        AlertDialog(
            onDismissRequest = { editMax = false },
            title = { Text(stringResource(R.string.scan_title)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.scan_label)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        editMax = false
                        vm.setMaxMessages(text.toInt().coerceIn(10, 2000))
                    },
                    enabled = text.toIntOrNull() != null,
                ) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { editMax = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun LoadMore(loading: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(24.dp))
        else TextButton(onClick = onClick) { Text(stringResource(R.string.messages_load_older)) }
    }
}

/** "Important (81)" with a badge showing the unread count when known and nonzero. */
@Composable
private fun TabTitle(@StringRes id: Int, total: Int, unread: Int?) {
    BadgedBox(badge = { if (unread != null && unread > 0) Badge { Text(unread.toString()) } }) {
        Text(stringResource(id, total))
    }
}
