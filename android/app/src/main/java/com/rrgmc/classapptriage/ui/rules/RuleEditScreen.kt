package com.rrgmc.classapptriage.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.triage.Rule
import com.rrgmc.classapptriage.triage.normalize
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.splitIds
import com.rrgmc.classapptriage.ui.splitList

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditScreen(draft: RuleDraft, onDone: () -> Unit) {
    val vm = appViewModel { RulesViewModel(it) }
    val labels by vm.labels.collectAsState()
    LaunchedEffect(Unit) { vm.loadLabels() }

    val existing = remember(draft) { if (draft.index >= 0) vm.rule(draft.group, draft.index) else null }
    val initial = existing ?: Rule(
        name = listOfNotNull(draft.sender, draft.label).joinToString(" / "),
        labels = listOfNotNull(draft.label),
        labelIds = listOfNotNull(draft.labelId),
        senders = listOfNotNull(draft.sender),
        senderIds = listOfNotNull(draft.senderId),
    )

    var group by rememberSaveable { mutableStateOf(draft.group) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var labelsText by rememberSaveable { mutableStateOf(initial.labels.joinToString(", ")) }
    var sendersText by rememberSaveable { mutableStateOf(initial.senders.joinToString(", ")) }
    var prefixesText by rememberSaveable { mutableStateOf(initial.summaryPrefixes.joinToString(", ")) }
    var containsText by rememberSaveable { mutableStateOf(initial.summaryContains.joinToString(", ")) }
    var labelIdsText by rememberSaveable { mutableStateOf(initial.labelIds.joinToString(", ")) }
    var senderIdsText by rememberSaveable { mutableStateOf(initial.senderIds.joinToString(", ")) }

    val rule = Rule(
        name = name.trim(),
        labels = splitList(labelsText),
        labelIds = splitIds(labelIdsText),
        senders = splitList(sendersText),
        senderIds = splitIds(senderIdsText),
        summaryPrefixes = splitList(prefixesText),
        summaryContains = splitList(containsText),
    )
    val valid = rule.name.isNotEmpty() && !rule.isEmpty

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (existing == null) R.string.rule_new else R.string.rule_edit)) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Filled.Close, stringResource(R.string.cancel)) } },
                actions = {
                    TextButton(
                        onClick = {
                            vm.save(draft.group, if (existing == null) -1 else draft.index, group, rule)
                            onDone()
                        },
                        enabled = valid,
                    ) { Text(stringResource(R.string.save)) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(GROUP_IMPORTANT to R.string.group_important, GROUP_ROUTINE to R.string.group_routine).forEachIndexed { i, (g, title) ->
                    SegmentedButton(
                        selected = group == g,
                        onClick = { group = g },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(stringResource(title)) }
                }
            }
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.rule_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.rule_help),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = labelsText, onValueChange = { labelsText = it },
                label = { Text(stringResource(R.string.rule_labels)) }, modifier = Modifier.fillMaxWidth(),
            )
            if (labels.isNotEmpty()) {
                val chosen = splitList(labelsText).map(::normalize).toSet()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    labels.forEach { label ->
                        val on = normalize(label.title) in chosen
                        FilterChip(
                            selected = on,
                            onClick = {
                                val current = splitList(labelsText)
                                labelsText = (
                                    if (on) current.filter { normalize(it) != normalize(label.title) }
                                    else current + label.title
                                    ).joinToString(", ")
                            },
                            label = { Text(label.title) },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = sendersText, onValueChange = { sendersText = it },
                label = { Text(stringResource(R.string.rule_senders)) }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefixesText, onValueChange = { prefixesText = it },
                label = { Text(stringResource(R.string.rule_prefixes)) }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = containsText, onValueChange = { containsText = it },
                label = { Text(stringResource(R.string.rule_contains)) }, modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.rule_advanced), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = labelIdsText, onValueChange = { labelIdsText = it },
                label = { Text(stringResource(R.string.rule_label_ids)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = senderIdsText, onValueChange = { senderIdsText = it },
                label = { Text(stringResource(R.string.rule_sender_ids)) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            if (!valid) {
                Text(
                    stringResource(if (rule.name.isEmpty()) R.string.rule_error_name else R.string.rule_error_condition),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
