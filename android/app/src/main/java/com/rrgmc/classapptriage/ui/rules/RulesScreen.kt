package com.rrgmc.classapptriage.ui.rules

import android.content.Intent
import android.content.res.Resources
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.triage.Rule
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(onBack: () -> Unit, onEdit: (RuleDraft) -> Unit) {
    val vm = appViewModel { RulesViewModel(it) }
    val config by vm.config.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }

    fun notify(text: String) = scope.launch { snackbar.showSnackbar(text) }

    fun importRules(text: String?) {
        if (text.isNullOrBlank()) {
            notify(context.getString(R.string.rules_nothing_to_import))
            return
        }
        try {
            vm.importJson(text)
            notify(context.getString(R.string.rules_imported))
        } catch (e: Exception) {
            notify(context.getString(R.string.rules_invalid_json, e.userMessage(context.resources)))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(vm.exportJson().toByteArray()) }
            notify(context.getString(R.string.rules_exported))
        } catch (e: Exception) {
            notify(context.getString(R.string.rules_export_failed, e.userMessage(context.resources)))
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (e: Exception) {
            notify(context.getString(R.string.rules_import_failed, e.userMessage(context.resources)))
            return@rememberLauncherForActivityResult
        }
        importRules(text)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.rules_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_export_file)) }, onClick = {
                                menuOpen = false
                                exportLauncher.launch("classapp-rules.json")
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_import_file)) }, onClick = {
                                menuOpen = false
                                importLauncher.launch(arrayOf("application/json", "text/*", "application/octet-stream"))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_share_json)) }, onClick = {
                                menuOpen = false
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_TEXT, vm.exportJson())
                                }
                                context.startActivity(Intent.createChooser(send, context.getString(R.string.rules_share_title)))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_copy_json)) }, onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(vm.exportJson()))
                                notify(context.getString(R.string.rules_copied))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_paste_json)) }, onClick = {
                                menuOpen = false
                                importRules(clipboard.getText()?.text)
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.rules_reset_defaults)) }, onClick = {
                                menuOpen = false
                                confirmReset = true
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    stringResource(R.string.rules_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            for (group in listOf(GROUP_IMPORTANT, GROUP_ROUTINE)) {
                val rules = RulesViewModel.rules(config, group)
                item(key = "header-$group") {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp)) {
                        Text(
                            stringResource(if (group == GROUP_IMPORTANT) R.string.group_important else R.string.group_routine),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f).padding(top = 12.dp),
                        )
                        TextButton(onClick = { onEdit(RuleDraft(group = group)) }) {
                            Icon(Icons.Filled.Add, null)
                            Text(stringResource(R.string.rules_add))
                        }
                    }
                }
                if (rules.isEmpty()) {
                    item(key = "empty-$group") {
                        Text(stringResource(R.string.rules_none), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                itemsIndexed(rules, key = { i, _ -> "$group-$i" }) { i, rule ->
                    ListItem(
                        headlineContent = { Text(rule.name.ifEmpty { stringResource(R.string.rules_unnamed) }) },
                        supportingContent = { Text(describe(context.resources, rule)) },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { vm.move(group, i, -1) }, enabled = i > 0) {
                                    Icon(Icons.Filled.ArrowUpward, stringResource(R.string.rules_move_up))
                                }
                                IconButton(onClick = { vm.move(group, i, 1) }, enabled = i < rules.size - 1) {
                                    Icon(Icons.Filled.ArrowDownward, stringResource(R.string.rules_move_down))
                                }
                                IconButton(onClick = { confirmDelete = group to i }) {
                                    Icon(Icons.Filled.Delete, stringResource(R.string.rules_delete))
                                }
                            }
                        },
                        modifier = Modifier.clickable { onEdit(RuleDraft(group = group, index = i)) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.rules_reset_title)) },
            text = { Text(stringResource(R.string.rules_reset_text)) },
            confirmButton = {
                TextButton(onClick = { confirmReset = false; vm.resetToDefault() }) { Text(stringResource(R.string.rules_reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    confirmDelete?.let { (group, index) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.rules_delete)) },
            text = { Text(stringResource(R.string.rules_delete_text, vm.rule(group, index)?.name.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; vm.delete(group, index) }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** A one-line summary of a rule's conditions. */
private fun describe(res: Resources, r: Rule): String {
    fun cond(id: Int, values: List<Any>) = res.getString(id, values.joinToString(" | "))
    val parts = buildList {
        if (r.labels.isNotEmpty()) add(cond(R.string.cond_label, r.labels))
        if (r.labelIds.isNotEmpty()) add(cond(R.string.cond_label_id, r.labelIds))
        if (r.senders.isNotEmpty()) add(cond(R.string.cond_sender, r.senders))
        if (r.senderIds.isNotEmpty()) add(cond(R.string.cond_sender_id, r.senderIds))
        if (r.summaryPrefixes.isNotEmpty()) add(cond(R.string.cond_prefix, r.summaryPrefixes))
        if (r.summaryContains.isNotEmpty()) add(cond(R.string.cond_contains, r.summaryContains))
    }
    return if (parts.isEmpty()) res.getString(R.string.cond_none) else parts.joinToString(res.getString(R.string.cond_and))
}
