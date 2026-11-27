package com.yuklive.app.feature.studio

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuklive.app.R
import com.yuklive.app.core.live.LiveSessionRepository
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.overlay.FontOption
import com.yuklive.app.overlay.FontRegistry
import com.yuklive.app.overlay.LayerDefaults
import com.yuklive.app.overlay.LayerType
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.OverlayLayer
import com.yuklive.app.overlay.OverlayRenderer
import com.yuklive.app.overlay.OverlayRepository
import com.yuklive.app.overlay.Scene
import com.yuklive.app.stream.StreamEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

/** Item di dock Sources: subtitle (global), layer overlay, atau sumber video scene. */
sealed interface SourceItem {
    val key: String

    data object Subtitle : SourceItem {
        override val key = "subtitle"
    }

    data class Layer(val layer: OverlayLayer) : SourceItem {
        override val key = layer.id
    }

    data class Video(val kind: SourceKind) : SourceItem {
        override val key = "video"
    }
}

/**
 * Scene & Sources ala OBS.
 * - Mode biasa: scene yang dipilih langsung tayang (PROGRAM) dan itulah yang diedit.
 * - Studio Mode: scene dipilih ke PREVIEW untuk disiapkan, lalu tombol Transisi
 *   memindahkannya ke PROGRAM.
 */
@HiltViewModel
class SceneEditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: OverlayRepository,
    private val fonts: FontRegistry,
    private val engine: StreamEngine,
    val renderer: OverlayRenderer,
    liveSession: LiveSessionRepository,
) : ViewModel() {

    val document: StateFlow<OverlayDocument> = repo.document
    val fontOptions: StateFlow<List<FontOption>> = fonts.options

    val liveStartedAt: StateFlow<Long?> = liveSession.state.map { it.startedAtElapsed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _studioMode = MutableStateFlow(false)
    val studioMode: StateFlow<Boolean> = _studioMode.asStateFlow()

    private val _previewSceneId = MutableStateFlow<String?>(null)

    /** Scene yang sedang diedit (Preview di Studio Mode, Program di mode biasa). */
    val editingScene: StateFlow<Scene> = combine(repo.document, _studioMode, _previewSceneId) { doc, studio, pid ->
        if (studio) doc.scenes.firstOrNull { it.id == pid } ?: doc.activeScene else doc.activeScene
    }.stateIn(viewModelScope, SharingStarted.Eagerly, repo.document.value.activeScene)

    private val _selected = MutableStateFlow<String?>(null)
    /** Key [SourceItem] yang dipilih. */
    val selected: StateFlow<String?> = _selected.asStateFlow()

    fun select(key: String?) {
        _selected.value = key
    }

    // ---------------------------------------------------------------- Studio Mode & scene

    fun setStudioMode(on: Boolean) {
        if (on) _previewSceneId.value = repo.document.value.activeScene.id
        _studioMode.value = on
        _selected.value = null
    }

    fun selectScene(id: String) {
        _selected.value = null
        if (_studioMode.value) _previewSceneId.value = id else engine.transitionToScene(id)
    }

    /** Tombol TRANSISI: Preview → Program. */
    fun transition() {
        val id = _previewSceneId.value ?: return
        engine.transitionToScene(id)
    }

    fun addScene() {
        val doc = repo.document.value
        val name = context.getString(R.string.overlay_scene_default_name, doc.scenes.size + 1)
        val base = editingScene.value
        val id = repo.addScene(name, base.source, copyFrom = base) ?: return
        selectScene(id)
    }

    fun renameScene(id: String, name: String) = repo.updateScene(id) { it.copy(name = name.ifBlank { it.name }) }

    fun deleteScene(id: String) {
        val wasProgram = repo.document.value.activeScene.id == id
        repo.deleteScene(id)
        if (_previewSceneId.value == id) _previewSceneId.value = repo.document.value.activeScene.id
        if (wasProgram) {
            val now = repo.document.value.activeScene
            engine.switchSource(now.source, announce = false)
        }
    }

    fun moveScene(id: String, up: Boolean) = repo.moveScene(id, up)

    // ---------------------------------------------------------------- Sources

    fun sourceItems(scene: Scene): List<SourceItem> =
        listOf<SourceItem>(SourceItem.Subtitle) +
            scene.layers.reversed().map { SourceItem.Layer(it) } +
            SourceItem.Video(scene.source)

    fun setVideoSource(kind: SourceKind) {
        val scene = editingScene.value
        repo.updateScene(scene.id) { it.copy(source = kind) }
        if (scene.id == repo.document.value.activeScene.id) engine.switchSource(kind)
    }

    fun addLayer(type: LayerType) {
        val scene = editingScene.value
        val count = scene.layers.count { it.type == type } + 1
        val name = context.getString(layerTypeName(type)) + " $count"
        val layer = LayerDefaults.create(type, name)
        repo.updateSceneLayers(scene.id) { it + layer }
        _selected.value = layer.id
    }

    fun update(id: String, transform: (OverlayLayer) -> OverlayLayer) =
        repo.updateLayerIn(editingScene.value.id, id, transform)

    fun deleteLayer(id: String) {
        repo.updateSceneLayers(editingScene.value.id) { list -> list.filterNot { it.id == id } }
        if (_selected.value == id) _selected.value = null
    }

    fun duplicate(id: String) {
        val src = editingScene.value.layers.firstOrNull { it.id == id } ?: return
        val copy = src.copy(
            id = UUID.randomUUID().toString(),
            name = src.name + " (2)",
            x = (src.x + 0.03f).coerceAtMost(1f),
            y = (src.y + 0.03f).coerceAtMost(1f),
        )
        repo.updateSceneLayers(editingScene.value.id) { it + copy }
        _selected.value = copy.id
    }

    /** Naik = tampil lebih di depan. */
    fun moveLayer(id: String, up: Boolean) = repo.updateSceneLayers(editingScene.value.id) { list ->
        val i = list.indexOfFirst { it.id == id }
        val j = if (up) i + 1 else i - 1
        if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
    }

    /** Gestur: geser (pecahan lebar/tinggi), cubit (skala), putar (derajat). */
    fun transformSelected(dx: Float, dy: Float, zoom: Float, rotation: Float) {
        val id = _selected.value ?: return
        update(id) { l ->
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
        val sceneId = editingScene.value.id
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) { repo.importImage(uri) } ?: return@launch
            repo.updateLayerIn(sceneId, id) { it.copy(imagePath = path) }
        }
    }

    companion object {
        fun layerTypeName(type: LayerType): Int = when (type) {
            LayerType.Text -> R.string.layer_text
            LayerType.Image -> R.string.layer_image
            LayerType.LowerThird -> R.string.layer_lower_third
            LayerType.Clock -> R.string.layer_clock
            LayerType.LiveBadge -> R.string.layer_live_badge
            LayerType.Ticker -> R.string.layer_ticker
        }

        fun sourceName(kind: SourceKind): Int = when (kind) {
            SourceKind.Camera -> R.string.sources_camera
            SourceKind.Usb -> R.string.sources_usb
            SourceKind.Wifi -> R.string.sources_wifi
        }
    }
}
