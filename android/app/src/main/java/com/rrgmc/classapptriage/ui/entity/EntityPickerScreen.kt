package com.rrgmc.classapptriage.ui.entity

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rrgmc.classapptriage.AppContainer
import com.rrgmc.classapptriage.R
import com.rrgmc.classapptriage.api.UnauthorizedException
import com.rrgmc.classapptriage.api.ViewerEntity
import com.rrgmc.classapptriage.data.SelectedEntity
import com.rrgmc.classapptriage.ui.appViewModel
import com.rrgmc.classapptriage.ui.userMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EntityPickerState(
    val loading: Boolean = true,
    val viewerName: String = "",
    val entities: List<ViewerEntity> = emptyList(),
    val error: String? = null,
)

class EntityPickerViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(EntityPickerState())
    val state: StateFlow<EntityPickerState> = _state.asStateFlow()
    val selected = container.settings.entity

    init {
        load()
    }

    fun load() {
        val client = container.client() ?: return
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            _state.value = try {
                val v = client.viewer()
                EntityPickerState(
                    loading = false,
                    viewerName = v.fullname,
                    entities = v.entities.sortedWith(compareBy({ it.disabled }, { it.fullname })),
                )
            } catch (e: UnauthorizedException) {
                container.sessionExpired()
                return@launch
            } catch (e: Exception) {
                _state.value.copy(loading = false, error = e.userMessage(container.res))
            }
        }
    }

    fun select(entity: ViewerEntity) = container.settings.selectEntity(SelectedEntity(entity.id, entity.fullname))

    fun logout() = container.logout()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityPickerScreen(canGoBack: Boolean, onBack: () -> Unit, onSelected: () -> Unit) {
    val vm = appViewModel { EntityPickerViewModel(it) }
    val state by vm.state.collectAsState()
    val selected by vm.selected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.entities_title)) },
                navigationIcon = {
                    if (canGoBack) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                actions = {
                    IconButton(onClick = vm::logout) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(R.string.log_out))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::load) { Text(stringResource(R.string.retry)) }
                }
                else -> LazyColumn {
                    if (state.viewerName.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.entities_signed_in_as, state.viewerName),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(state.entities, key = { it.id }) { e ->
                        ListItem(
                            headlineContent = { Text(e.fullname) },
                            supportingContent = {
                                val parts = listOfNotNull(
                                    e.organization?.fullname?.takeIf { it.isNotEmpty() },
                                    e.type,
                                    if (e.disabled) stringResource(R.string.entities_disabled) else null,
                                    stringResource(R.string.entities_id, e.id),
                                )
                                Text(parts.joinToString(" · "))
                            },
                            trailingContent = {
                                if (selected?.id == e.id) Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.entities_selected))
                            },
                            modifier = Modifier.clickable {
                                vm.select(e)
                                onSelected()
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
