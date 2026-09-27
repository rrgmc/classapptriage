package com.rrgmc.classapptriage.ui.rules

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.api.Label
import com.rrgmc.classapptriage.api.UnauthorizedException
import com.rrgmc.classapptriage.triage.Config
import com.rrgmc.classapptriage.triage.Rule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val GROUP_IMPORTANT = "important"
const val GROUP_ROUTINE = "routine"

/**
 * Identifies a rule to edit ([index] >= 0) or a new rule (-1) in [group],
 * optionally pre-filled from a message's label and sender.
 */
data class RuleDraft(
    val group: String = GROUP_ROUTINE,
    val index: Int = -1,
    val label: String? = null,
    val labelId: Long? = null,
    val sender: String? = null,
    val senderId: Long? = null,
)

class RulesViewModel(private val container: AppContainer) : ViewModel() {
    val config: StateFlow<Config> = container.rulesStore.config

    private val _labels = MutableStateFlow<List<Label>>(emptyList())
    /** The organization's labels, offered as choices in the editor. */
    val labels: StateFlow<List<Label>> = _labels.asStateFlow()

    fun loadLabels() {
        if (_labels.value.isNotEmpty()) return
        val client = container.client() ?: return
        val entity = container.settings.entity.value ?: return
        viewModelScope.launch {
            try {
                _labels.value = client.labels(entity.id).sortedBy { it.title.lowercase() }
            } catch (e: UnauthorizedException) {
                container.sessionExpired()
            } catch (e: Exception) {
                // Labels are only a convenience; free text still works.
            }
        }
    }

    fun rule(group: String, index: Int): Rule? = rules(config.value, group).getOrNull(index)

    /** Saves [rule] at [index] of [fromGroup] (or appends when -1), moving it to [toGroup] if changed. */
    fun save(fromGroup: String, index: Int, toGroup: String, rule: Rule) {
        var important = config.value.important.toMutableList()
        var routine = config.value.routine.toMutableList()
        fun list(g: String) = if (g == GROUP_IMPORTANT) important else routine
        if (index >= 0 && fromGroup == toGroup) {
            list(toGroup)[index] = rule
        } else {
            if (index >= 0) list(fromGroup).removeAt(index)
            list(toGroup).add(rule)
        }
        container.rulesStore.save(Config(important = important, routine = routine))
    }

    fun delete(group: String, index: Int) {
        val c = config.value
        container.rulesStore.save(
            if (group == GROUP_IMPORTANT) c.copy(important = c.important.filterIndexed { i, _ -> i != index })
            else c.copy(routine = c.routine.filterIndexed { i, _ -> i != index }),
        )
    }

    fun move(group: String, index: Int, delta: Int) {
        val c = config.value
        val l = rules(c, group).toMutableList()
        val to = index + delta
        if (to !in l.indices) return
        l.add(to, l.removeAt(index))
        container.rulesStore.save(if (group == GROUP_IMPORTANT) c.copy(important = l) else c.copy(routine = l))
    }

    fun resetToDefault() = container.rulesStore.resetToDefault()

    fun exportJson(): String = config.value.toJson()

    /** Replaces the rules with the parsed [json]; throws on invalid input. */
    fun importJson(json: String) = container.rulesStore.save(Config.fromJson(json))

    companion object {
        fun rules(c: Config, group: String) = if (group == GROUP_IMPORTANT) c.important else c.routine
    }
}
