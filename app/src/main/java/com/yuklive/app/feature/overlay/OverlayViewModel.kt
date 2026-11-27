package com.yuklive.app.feature.overlay

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuklive.app.R
import com.yuklive.app.core.live.LiveSessionRepository
import com.yuklive.app.core.settings.SettingsRepository
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.StreamOrientation
import com.yuklive.app.overlay.FontOption
import com.yuklive.app.overlay.FontRegistry
import com.yuklive.app.overlay.LayerDefaults
import com.yuklive.app.overlay.LayerType
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.OverlayLayer
import com.yuklive.app.overlay.OverlayRenderer
import com.yuklive.app.overlay.OverlayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class OverlayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: OverlayRepository,
    private val fonts: FontRegistry,
    val renderer: OverlayRenderer,
    settings: SettingsRepository,
    liveSession: LiveSessionRepository,
) : ViewModel() {

    val document: StateFlow<OverlayDocument> = repo.document
    val fontOptions: StateFlow<List<FontOption>> = fonts.options

    val portrait: StateFlow<Boolean> = settings.settings.map { it.orientation == StreamOrientation.Portrait }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val liveStartedAt: StateFlow<Long?> = liveSession.state.map { it.startedAtElapsed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    fun select(id: String?) {
        _selectedId.value = id
    }

    // ---------------------------------------------------------------- Layer

    fun addLayer(type: LayerType) {
        val count = repo.document.value.activeScene.layers.count { it.type == type } + 1
        val name = context.getString(layerTypeName(type)) + " $count"
        val layer = LayerDefaults.create(type, name)
        repo.updateActiveLayers { it + layer }
        _selectedId.value = layer.id
    }

    fun update(id: String, transform: (OverlayLayer) -> OverlayLayer) = repo.updateLayer(id, transform)

    fun delete(id: String) {
        repo.updateActiveLayers { list -> list.filterNot { it.id == id } }
        if (_selectedId.value == id) _selectedId.value = null
    }

    fun duplicate(id: String) {
        val src = repo.document.value.activeScene.layers.firstOrNull { it.id == id } ?: return
        val copy = src.copy(id = UUID.randomUUID().toString(), name = src.name + " (2)", x = (src.x + 0.03f).coerceAtMost(1f), y = (src.y + 0.03f).coerceAtMost(1f))
        repo.updateActiveLayers { it + copy }
        _selectedId.value = copy.id
    }

    /** Naik = digambar lebih atas (lebih akhir di daftar). */
    fun move(id: String, up: Boolean) = repo.updateActiveLayers { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) list
        else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Gestur di preview: geser (pecahan lebar/tinggi), cubit (skala), putar (derajat). */
    fun transformSelected(dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val id = _selectedId.value ?: return
        repo.updateLayer(id) { l ->
            if (l.locked) l
            else l.copy(
                x = (l.x + dx).coerceIn(0f, 1f),
                y = (l.y + dy).coerceIn(0f, 1f),
                scale = (l.scale * zoom).coerceIn(0.1f, 6f),
                rotation = (l.rotation + rotation) % 360f,
            )
        }
    }

    fun importImage(id: String, uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { repo.importImage(uri) } ?: return@launch
            repo.updateLayer(id) { it.copy(imagePath = path) }
        }
    }

    fun importFont(uri: Uri, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { fonts.importFont(uri) }
            onDone(key)
        }
    }

    // ---------------------------------------------------------------- Scene

    fun activateScene(id: String) = repo.setActiveScene(id)

    fun addScene(): Boolean {
        val doc = repo.document.value
        val name = context.getString(R.string.overlay_scene_default_name, doc.scenes.size + 1)
        return repo.addScene(name, doc.activeScene.source, copyFrom = doc.activeScene)
    }

    fun renameScene(id: String, name: String) = repo.updateScene(id) { it.copy(name = name.ifBlank { it.name }) }
    fun setSceneSource(id: String, source: SourceKind) = repo.updateScene(id) { it.copy(source = source) }
    fun deleteScene(id: String) = repo.deleteScene(id)

    companion object {
        fun layerTypeName(type: LayerType): Int = when (type) {
            LayerType.Text -> R.string.layer_text
            LayerType.Image -> R.string.layer_image
            LayerType.LowerThird -> R.string.layer_lower_third
            LayerType.Clock -> R.string.layer_clock
            LayerType.LiveBadge -> R.string.layer_live_badge
            LayerType.Ticker -> R.string.layer_ticker
        }
    }
}
