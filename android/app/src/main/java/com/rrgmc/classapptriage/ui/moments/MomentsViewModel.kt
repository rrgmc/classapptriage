package com.rrgmc.classapptriage.ui.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.api.Moment
import com.rrgmc.classapptriage.api.UnauthorizedException
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MomentsState(
    val entityId: Long = 0,
    val entityName: String = "",
    val moments: List<Moment> = emptyList(),
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Moments whose like is being sent. */
    val liking: Set<Long> = emptySet(),
)

/** The inbox's moments feed (docs/API.md §5.6), newest first, paged as the list scrolls. */
class MomentsViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(
        container.settings.entity.value.let { MomentsState(entityId = it?.id ?: 0, entityName = it?.name.orEmpty()) },
    )
    val state: StateFlow<MomentsState> = _state.asStateFlow()

    init {
        load(reset = true, initial = true)
    }

    fun refresh() = load(reset = true, initial = false)

    fun loadMore() {
        if (_state.value.hasMore) load(reset = false, initial = false)
    }

    private fun load(reset: Boolean, initial: Boolean) {
        val client = container.client() ?: return
        val current = _state.value
        if (current.entityId == 0L || current.loading || current.refreshing) return
        _state.update { it.copy(loading = initial || !reset, refreshing = reset && !initial, error = null) }
        viewModelScope.launch {
            try {
                val offset = if (reset) 0 else current.moments.size
                val page = client.momentsPage(current.entityId, PAGE_SIZE, offset)
                _state.update { s ->
                    val base = if (reset) emptyList() else s.moments
                    val seen = base.mapTo(HashSet()) { it.id }
                    s.copy(moments = base + page.moments.filter { it.id !in seen }, hasMore = page.pageInfo.hasNextPage)
                }
            } catch (e: Exception) {
                handle(e)
            } finally {
                _state.update { it.copy(loading = false, refreshing = false) }
            }
        }
    }

    /** Likes or unlikes [id]; shown at once and undone if the server rejects it. */
    fun toggleLike(id: Long) {
        val client = container.client() ?: return
        val s = _state.value
        val moment = s.moments.firstOrNull { it.id == id } ?: return
        if (id in s.liking) return
        val liked = !moment.liked
        setLiked(id, liked)
        _state.update { it.copy(liking = it.liking + id, error = null) }
        viewModelScope.launch {
            try {
                client.setMomentLiked(s.entityId, id, liked)
            } catch (e: Exception) {
                setLiked(id, !liked)
                handle(e)
            } finally {
                _state.update { it.copy(liking = it.liking - id) }
            }
        }
    }

    private fun setLiked(id: Long, liked: Boolean) = _state.update { s ->
        s.copy(moments = s.moments.map {
            if (it.id != id || it.liked == liked) it
            else it.copy(liked = liked, likes = (it.likes + if (liked) 1 else -1).coerceAtLeast(0))
        })
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun handle(e: Exception) {
        if (e is UnauthorizedException) {
            container.sessionExpired()
        } else {
            _state.update { it.copy(error = e.userMessage(container.res)) }
        }
    }

    private companion object {
        const val PAGE_SIZE = 20
    }
}
