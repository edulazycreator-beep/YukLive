package com.yuklive.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.enumOr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/** Menyimpan scene & layer di filesDir/overlay.json. Gambar disalin ke filesDir/overlay_images. */
@OptIn(FlowPreview::class)
@Singleton
class OverlayRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val file = File(context.filesDir, "overlay.json")
    private val imageDir = File(context.filesDir, "overlay_images").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _doc = MutableStateFlow(load())
    val document: StateFlow<OverlayDocument> = _doc.asStateFlow()

    init {
        _doc.drop(1).debounce(400).onEach { save(it) }.launchIn(scope)
    }

    fun update(transform: (OverlayDocument) -> OverlayDocument) = _doc.update(transform)

    fun updateActiveLayers(transform: (List<OverlayLayer>) -> List<OverlayLayer>) =
        updateSceneLayers(_doc.value.activeScene.id, transform)

    fun updateSceneLayers(sceneId: String, transform: (List<OverlayLayer>) -> List<OverlayLayer>) = update { doc ->
        doc.copy(scenes = doc.scenes.map { if (it.id == sceneId) it.copy(layers = transform(it.layers)) else it })
    }

    fun updateLayerIn(sceneId: String, id: String, transform: (OverlayLayer) -> OverlayLayer) =
        updateSceneLayers(sceneId) { layers -> layers.map { if (it.id == id) transform(it) else it } }

    fun moveScene(id: String, up: Boolean) = update { doc ->
        val i = doc.scenes.indexOfFirst { it.id == id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in doc.scenes.indices) doc
        else doc.copy(scenes = doc.scenes.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun updateLayer(id: String, transform: (OverlayLayer) -> OverlayLayer) =
        updateActiveLayers { layers -> layers.map { if (it.id == id) transform(it) else it } }

    fun setActiveScene(id: String) = update { it.copy(activeSceneId = id) }

    /** @return id scene baru, atau null kalau sudah 8 scene. */
    fun addScene(name: String, source: SourceKind, copyFrom: Scene?, activate: Boolean = false): String? {
        if (_doc.value.scenes.size >= OverlayDocument.MAX_SCENES) return null
        val scene = Scene(
            name = name,
            source = source,
            layers = copyFrom?.layers?.map { it.copy(id = UUID.randomUUID().toString()) }.orEmpty(),
            showSubtitle = copyFrom?.showSubtitle ?: true,
        )
        update { doc ->
            doc.copy(
                scenes = doc.scenes + scene,
                activeSceneId = if (activate) scene.id else (doc.activeSceneId ?: doc.activeScene.id),
            )
        }
        return scene.id
    }

    fun deleteScene(id: String) = update { doc ->
        if (doc.scenes.size <= 1) return@update doc
        val rest = doc.scenes.filterNot { it.id == id }
        doc.copy(scenes = rest, activeSceneId = if (doc.activeSceneId == id) rest.first().id else doc.activeSceneId)
    }

    fun updateScene(id: String, transform: (Scene) -> Scene) = update { doc ->
        doc.copy(scenes = doc.scenes.map { if (it.id == id) transform(it) else it })
    }

    /** Salin gambar PNG dari pemilih file, diperkecil maks 1920px. */
    fun importImage(uri: Uri): String? = runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (max(opts.outWidth, opts.outHeight) / sample > 1920) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val target = File(imageDir, "img_${System.currentTimeMillis()}.png")
        target.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        target.absolutePath
    }.getOrNull()

    // ---------------------------------------------------------------- JSON

    private fun load(): OverlayDocument = runCatching {
        if (!file.exists()) return OverlayDocument()
        val root = JSONObject(file.readText())
        val scenes = root.getJSONArray("scenes").let { arr ->
            (0 until arr.length()).map { sceneFrom(arr.getJSONObject(it)) }
        }
        if (scenes.isEmpty()) OverlayDocument()
        else OverlayDocument(scenes = scenes, activeSceneId = root.optString("active").ifBlank { null })
    }.getOrDefault(OverlayDocument())

    private fun save(doc: OverlayDocument) {
        runCatching {
            val root = JSONObject()
                .put("active", doc.activeSceneId ?: "")
                .put("scenes", JSONArray().apply { doc.scenes.forEach { put(sceneTo(it)) } })
            val tmp = File(file.parentFile, "overlay.json.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(file)
        }
    }

    private fun sceneTo(s: Scene) = JSONObject()
        .put("id", s.id).put("name", s.name).put("source", s.source.name).put("subtitle", s.showSubtitle)
        .put("layers", JSONArray().apply { s.layers.forEach { put(layerTo(it)) } })

    private fun sceneFrom(o: JSONObject) = Scene(
        id = o.getString("id"),
        name = o.optString("name", "Scene"),
        source = enumOr(o.optString("source"), SourceKind.Camera),
        showSubtitle = o.optBoolean("subtitle", true),
        layers = o.optJSONArray("layers")?.let { arr -> (0 until arr.length()).map { layerFrom(arr.getJSONObject(it)) } }.orEmpty(),
    )

    private fun layerTo(l: OverlayLayer) = JSONObject()
        .put("id", l.id).put("type", l.type.name).put("name", l.name)
        .put("x", l.x.toDouble()).put("y", l.y.toDouble()).put("scale", l.scale.toDouble())
        .put("rotation", l.rotation.toDouble()).put("opacity", l.opacity.toDouble())
        .put("visible", l.visible).put("locked", l.locked)
        .put("text", l.text).put("subText", l.subText).put("font", l.fontKey).put("weight", l.fontWeight)
        .put("textSize", l.textSize.toDouble()).put("textColor", l.textColor).put("strokeColor", l.strokeColor)
        .put("strokeWidth", l.strokeWidth.toDouble()).put("bgColor", l.bgColor).put("bgEnabled", l.bgEnabled)
        .put("image", l.imagePath ?: "").put("clockMode", l.clockMode.name).put("countdown", l.countdownSeconds)
        .put("timerStart", l.timerStartedAt).put("tickerSpeed", l.tickerSpeed.toDouble())
        .put("animIn", l.animIn.name).put("animOut", l.animOut.name)

    private fun layerFrom(o: JSONObject): OverlayLayer {
        val d = OverlayLayer(type = LayerType.Text, name = "")
        return OverlayLayer(
            id = o.optString("id", d.id),
            type = enumOr(o.optString("type"), LayerType.Text),
            name = o.optString("name"),
            x = o.optDouble("x", 0.5).toFloat(),
            y = o.optDouble("y", 0.5).toFloat(),
            scale = o.optDouble("scale", 1.0).toFloat(),
            rotation = o.optDouble("rotation", 0.0).toFloat(),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            visible = o.optBoolean("visible", true),
            locked = o.optBoolean("locked", false),
            text = o.optString("text"),
            subText = o.optString("subText"),
            fontKey = o.optString("font", d.fontKey),
            fontWeight = o.optInt("weight", d.fontWeight),
            textSize = o.optDouble("textSize", d.textSize.toDouble()).toFloat(),
            textColor = o.optInt("textColor", d.textColor),
            strokeColor = o.optInt("strokeColor", d.strokeColor),
            strokeWidth = o.optDouble("strokeWidth", 0.0).toFloat(),
            bgColor = o.optInt("bgColor", d.bgColor),
            bgEnabled = o.optBoolean("bgEnabled", false),
            imagePath = o.optString("image").ifBlank { null },
            clockMode = enumOr(o.optString("clockMode"), ClockMode.Clock),
            countdownSeconds = o.optInt("countdown", 300),
            timerStartedAt = o.optLong("timerStart", System.currentTimeMillis()),
            tickerSpeed = o.optDouble("tickerSpeed", 0.12).toFloat(),
            animIn = enumOr(o.optString("animIn"), LayerAnim.Fade),
            animOut = enumOr(o.optString("animOut"), LayerAnim.Fade),
        )
    }
}
