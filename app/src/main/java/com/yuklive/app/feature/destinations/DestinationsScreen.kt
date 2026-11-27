package com.yuklive.app.feature.destinations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yuklive.app.R
import com.yuklive.app.core.destination.Destination
import com.yuklive.app.core.destination.DestinationError
import com.yuklive.app.core.destination.DestinationRepository
import com.yuklive.app.core.destination.DestinationState
import com.yuklive.app.core.destination.Platform
import com.yuklive.app.ui.components.EmptyState
import com.yuklive.app.ui.components.HairlineDivider
import com.yuklive.app.ui.components.ListRow
import com.yuklive.app.ui.components.PillButton
import com.yuklive.app.ui.components.PillToggle
import com.yuklive.app.ui.components.PillVariant
import com.yuklive.app.ui.components.RowGroup
import com.yuklive.app.ui.components.ScreenHeader
import com.yuklive.app.ui.components.ScrollPills
import com.yuklive.app.ui.components.SectionHeader
import com.yuklive.app.ui.components.YukTextField
import com.yuklive.app.ui.theme.Dimens
import com.yuklive.app.ui.theme.SharpShape
import com.yuklive.app.ui.theme.YukColors
import com.yuklive.app.ui.theme.YukType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DestinationsViewModel @Inject constructor(
    private val repo: DestinationRepository,
) : ViewModel() {
    val state: StateFlow<DestinationState> = repo.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DestinationState())

    fun save(d: Destination) = viewModelScope.launch { repo.save(d) }
    fun delete(id: String) = viewModelScope.launch { repo.delete(id) }
    fun select(id: String) = viewModelScope.launch { repo.select(id) }
}

@Composable
fun DestinationsScreen(onBack: () -> Unit, viewModel: DestinationsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Destination?>(null) }
    var confirmDelete by remember { mutableStateOf<Destination?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.screen_destinations), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Dimens.ScreenPadding),
        ) {
            SectionHeader(stringResource(R.string.destinations_saved))
            if (state.destinations.isEmpty()) {
                EmptyState(
                    title = stringResource(R.string.home_destinations_empty_title),
                    body = stringResource(R.string.home_destinations_empty_body),
                )
            } else {
                RowGroup {
                    state.destinations.forEachIndexed { i, d ->
                        if (i > 0) HairlineDivider()
                        ListRow(
                            title = d.name,
                            subtitle = "${d.platform.displayName} · ${d.platform.protocolLabel} · ${maskKey(d.streamKey)}",
                            onClick = { viewModel.select(d.id) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconAction(Icons.Outlined.Edit, stringResource(R.string.action_edit)) { editing = d }
                                    IconAction(Icons.Outlined.Delete, stringResource(R.string.action_delete)) { confirmDelete = d }
                                    PillToggle(d.id == state.selected?.id, { viewModel.select(d.id) })
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(Dimens.S2))
            PillButton(
                text = stringResource(R.string.home_destinations_add),
                onClick = { editing = Destination(platform = Platform.YouTube, name = "YouTube", server = Platform.YouTube.defaultServer, streamKey = "") },
                icon = Icons.Outlined.Add,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Dimens.S2))
            Text(stringResource(R.string.destinations_stage_note), style = YukType.BodySmall, color = YukColors.TextSecondary)
            Spacer(Modifier.height(Dimens.S4))
        }
    }

    editing?.let { d ->
        DestinationDialog(
            initial = d,
            isNew = state.destinations.none { it.id == d.id },
            onDismiss = { editing = null },
            onSave = {
                viewModel.save(it)
                editing = null
            },
        )
    }

    confirmDelete?.let { d ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            shape = SharpShape,
            containerColor = YukColors.Surface,
            tonalElevation = 0.dp,
            title = { Text(stringResource(R.string.destinations_delete_title, d.name), style = YukType.Title) },
            confirmButton = {
                PillButton(stringResource(R.string.action_delete), onClick = {
                    viewModel.delete(d.id)
                    confirmDelete = null
                }, height = 40.dp)
            },
            dismissButton = {
                PillButton(stringResource(R.string.action_cancel), onClick = { confirmDelete = null },
                    variant = PillVariant.Secondary, height = 40.dp)
            },
        )
    }
}

@Composable
private fun IconAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = YukColors.TextSecondary, modifier = Modifier.size(18.dp))
    }
}

private fun maskKey(key: String): String =
    if (key.length <= 4) "••••" else "••••" + key.takeLast(4)

@Composable
private fun DestinationDialog(
    initial: Destination,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (Destination) -> Unit,
) {
    var platform by remember { mutableStateOf(initial.platform) }
    var name by remember { mutableStateOf(initial.name) }
    var server by remember { mutableStateOf(initial.server) }
    var key by remember { mutableStateOf(initial.streamKey) }
    var showKey by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<DestinationError?>(null) }

    val draft = initial.copy(platform = platform, name = name.trim(), server = server.trim(), streamKey = key.trim())

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = SharpShape,
        containerColor = YukColors.Surface,
        tonalElevation = 0.dp,
        title = { Text(stringResource(if (isNew) R.string.destinations_add_title else R.string.destinations_edit_title), style = YukType.Title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Dimens.S2),
            ) {
                val platforms = Platform.entries
                ScrollPills(platforms.map { it.displayName }, platforms.indexOf(platform), { i ->
                    val p = platforms[i]
                    if (name.isBlank() || Platform.entries.any { it.displayName == name }) name = p.displayName
                    if (server.isBlank() || Platform.entries.any { it.defaultServer == server }) server = p.defaultServer
                    platform = p
                })
                YukTextField(name, { name = it }, label = stringResource(R.string.destinations_name),
                    error = if (error == DestinationError.EmptyName) stringResource(R.string.err_dest_name) else null)
                YukTextField(server, { server = it }, label = stringResource(R.string.destinations_server),
                    placeholder = "rtmp://…", keyboardType = KeyboardType.Uri,
                    error = when (error) {
                        DestinationError.EmptyServer -> stringResource(R.string.err_dest_server)
                        DestinationError.BadScheme -> stringResource(R.string.err_dest_scheme)
                        else -> null
                    })
                if (platform == Platform.TikTok || platform == Platform.Instagram) {
                    Text(stringResource(R.string.destinations_server_hint_dynamic), style = YukType.BodySmall, color = YukColors.TextSecondary)
                }
                YukTextField(key, { key = it }, label = stringResource(
                    if (platform == Platform.CustomSrt) R.string.destinations_streamid else R.string.destinations_key,
                ), password = !showKey,
                    error = if (error == DestinationError.EmptyKey) stringResource(R.string.err_dest_key) else null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.destinations_show_key), style = YukType.BodySmall, color = YukColors.TextSecondary,
                        modifier = Modifier.weight(1f))
                    PillToggle(showKey, { showKey = it })
                }
            }
        },
        confirmButton = {
            PillButton(stringResource(R.string.action_save), onClick = {
                val e = draft.validate()
                error = e
                if (e == null) onSave(draft)
            }, height = 40.dp)
        },
        dismissButton = {
            PillButton(stringResource(R.string.action_cancel), onClick = onDismiss, variant = PillVariant.Secondary, height = 40.dp)
        },
    )
}
