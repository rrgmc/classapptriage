package com.rrgmc.classapptriage.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.Message
import com.rrgmc.classapptriage.api.MessageStatus
import com.rrgmc.classapptriage.api.UnauthorizedException
import com.rrgmc.classapptriage.triage.Category
import com.rrgmc.classapptriage.triage.Result
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ClassifiedMessage(val message: Message, val result: Result)

/** Raw state; the classification is derived from it and the current rules. */
private data class RawState(
    val messages: List<Message> = emptyList(),
    /** Server offset of the next page (fetched minus deleted). */
    val nextOffset: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val selection: Set<Long> = emptySet(),
    /** Messages marked read/unread in this session, for display. */
    val localStatus: Map<Long, MessageStatus> = emptyMap(),
    /** Ids in the UNREAD_BY_NTF folder; null when it could not be loaded. */
    val unreadIds: Set<Long>? = null,
)

data class MessagesState(
    val entityId: Long = 0,
    val entityName: String = "",
    val important: List<ClassifiedMessage> = emptyList(),
    val routine: List<ClassifiedMessage> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val working: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val selection: Set<Long> = emptySet(),
    val localStatus: Map<Long, MessageStatus> = emptyMap(),
    val unreadIds: Set<Long>? = null,
    val maxMessages: Int = 0,
) {
    val selecting: Boolean get() = selection.isNotEmpty()

    /**
     * Read state of [m]: an in-app change wins, then the server's unread
     * folder; messages sent from this inbox are read. Null when unknown.
     */
    fun isRead(m: Message): Boolean? = when (localStatus[m.id]) {
        MessageStatus.READ -> true
        MessageStatus.UNREAD -> false
        else -> when {
            entityId != 0L && m.entity?.id == entityId -> true
            else -> unreadIds?.let { m.id !in it }
        }
    }

    /** Unread messages in [list] by [isRead]; null when unknown. */
    fun unreadCount(list: List<ClassifiedMessage>): Int? =
        if (unreadIds == null) null else list.count { isRead(it.message) == false }

    fun category(c: Category) = if (c == Category.ROUTINE) routine else important
}

class MessagesViewModel(private val container: AppContainer) : ViewModel() {
    private val raw = MutableStateFlow(RawState())
    private val maxMessages = MutableStateFlow(container.settings.maxMessages)

    val state: StateFlow<MessagesState> = combine(
        raw, container.rulesStore.config, container.settings.entity, maxMessages,
    ) { r, config, entity, max ->
        val classified = r.messages.map { ClassifiedMessage(it, config.classify(it)) }
        MessagesState(
            entityId = entity?.id ?: 0,
            entityName = entity?.name.orEmpty(),
            important = classified.filter { it.result.category == Category.IMPORTANT },
            routine = classified.filter { it.result.category == Category.ROUTINE },
            hasMore = r.hasMore,
            loading = r.loading,
            refreshing = r.refreshing,
            working = r.working,
            error = r.error,
            info = r.info,
            selection = r.selection,
            localStatus = r.localStatus,
            unreadIds = r.unreadIds,
            maxMessages = max,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MessagesState(loading = true))

    private val entityId: Long? get() = container.settings.entity.value?.id

    init {
        refresh(initial = true)
    }

    /** Reloads the most recent messages, up to the configured maximum. */
    fun refresh(initial: Boolean = false) = launchLoad(reset = true, initial = initial)

    /** Loads the next batch of older messages. */
    fun loadMore() = launchLoad(reset = false, initial = false)

    private fun launchLoad(reset: Boolean, initial: Boolean) {
        val client = container.client() ?: return
        val id = entityId ?: return
        val current = raw.value
        if (current.loading || current.refreshing) return
        raw.update { it.copy(loading = initial || !reset, refreshing = reset && !initial, error = null) }
        viewModelScope.launch {
            try {
                val offset = if (reset) 0 else current.nextOffset
                val unread = if (!reset) raw.value.unreadIds else try {
                    client.unreadMessageIds(id)
                } catch (e: UnauthorizedException) {
                    throw e
                } catch (e: Exception) {
                    null // read state unknown; the list still loads
                }
                val page = client.recentMessages(id, maxMessages.value, offset = offset)
                raw.update { r ->
                    val base = if (reset) emptyList() else r.messages
                    val seen = base.mapTo(HashSet()) { it.id }
                    r.copy(
                        messages = base + page.messages.filter { it.id !in seen },
                        nextOffset = offset + page.messages.size,
                        hasMore = page.pageInfo.hasNextPage,
                        selection = if (reset) emptySet() else r.selection,
                        localStatus = if (reset) emptyMap() else r.localStatus,
                        unreadIds = unread,
                    )
                }
            } catch (e: Exception) {
                handle(e)
            } finally {
                raw.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    fun toggleSelection(id: Long) = raw.update {
        it.copy(selection = if (id in it.selection) it.selection - id else it.selection + id)
    }

    fun selectAll(ids: Collection<Long>) = raw.update { it.copy(selection = it.selection + ids) }

    fun clearSelection() = raw.update { it.copy(selection = emptySet()) }

    fun dismissMessages() = raw.update { it.copy(error = null, info = null) }

    fun setMaxMessages(max: Int) {
        container.settings.maxMessages = max
        maxMessages.value = max
        refresh()
    }

    /** The listed message with [id], if still loaded. */
    fun find(id: Long): ClassifiedMessage? =
        state.value.let { s -> (s.important + s.routine).firstOrNull { it.message.id == id } }

    /**
     * Applies [status] to [ids] on the server and updates the local list;
     * [onSuccess] runs after the server accepted the change. A [quiet] change
     * (e.g. auto-mark on open) shows no confirmation message.
     */
    fun apply(ids: Collection<Long>, status: MessageStatus, quiet: Boolean = false, onSuccess: () -> Unit = {}) {
        val client = container.client() ?: return
        val id = entityId ?: return
        if (ids.isEmpty() || raw.value.working) return
        val list = ids.toList()
        raw.update { it.copy(working = true, error = null, info = null) }
        viewModelScope.launch {
            try {
                client.setMessagesStatus(id, list, status)
                onSuccess()
                val set = list.toSet()
                raw.update { r ->
                    if (status == MessageStatus.DELETED) {
                        val removed = r.messages.count { it.id in set }
                        r.copy(
                            messages = r.messages.filter { it.id !in set },
                            nextOffset = (r.nextOffset - removed).coerceAtLeast(0),
                            selection = r.selection - set,
                            info = if (quiet) null else container.res.getQuantityString(R.plurals.info_deleted, list.size, list.size),
                        )
                    } else {
                        r.copy(
                            localStatus = r.localStatus + list.associateWith { status },
                            selection = r.selection - set,
                            info = if (quiet) null else container.res.getQuantityString(
                                if (status == MessageStatus.READ) R.plurals.info_marked_read else R.plurals.info_marked_unread,
                                list.size, list.size,
                            ),
                        )
                    }
                }
            } catch (e: Exception) {
                handle(e)
            } finally {
                raw.update { it.copy(working = false) }
            }
        }
    }

    private fun handle(e: Exception) {
        if (e is UnauthorizedException) {
            container.sessionExpired()
        } else {
            raw.update { it.copy(error = e.userMessage(container.res)) }
        }
    }

    fun logout() = container.logout()
}
