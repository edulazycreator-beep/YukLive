package com.yuklive.app.subtitle

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yuklive.app.core.settings.enumOr
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.subtitleStore: DataStore<Preferences> by preferencesDataStore(name = "subtitle")

@Singleton
class SubtitleRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("config_json")

    val config: Flow<SubtitleConfig> = context.subtitleStore.data.map { decode(it[key]) }

    suspend fun current(): SubtitleConfig = config.first()

    suspend fun update(transform: (SubtitleConfig) -> SubtitleConfig) {
        context.subtitleStore.edit { prefs -> prefs[key] = encode(transform(decode(prefs[key]))) }
    }

    // ---------------------------------------------------------------- JSON

    private fun encode(c: SubtitleConfig): String = JSONObject()
        .put("enabled", c.enabled)
        .put("engine", c.engine.name)
        .put("language", c.language)
        .put("voskModel", c.voskModel ?: "")
        .put("style", styleTo(c.style))
        .put("presets", JSONArray().apply { c.presets.forEach { put(it?.let(this@SubtitleRepository::styleTo) ?: JSONObject.NULL) } })
        .put("corrections", JSONArray().apply { c.corrections.forEach { put(JSONObject().put("w", it.wrong).put("r", it.right)) } })
        .toString()

    private fun decode(json: String?): SubtitleConfig {
        if (json.isNullOrBlank()) return SubtitleConfig()
        return runCatching {
            val o = JSONObject(json)
            val presetsArr = o.optJSONArray("presets")
            val presets = List(SubtitleConfig.PRESET_SLOTS) { i ->
                presetsArr?.optJSONObject(i)?.let(this@SubtitleRepository::styleFrom)
            }
            val corr = o.optJSONArray("corrections")?.let { arr ->
                (0 until arr.length()).map { i -> arr.getJSONObject(i).let { Correction(it.optString("w"), it.optString("r")) } }
            }.orEmpty()
            SubtitleConfig(
                enabled = o.optBoolean("enabled", false),
                engine = enumOr(o.optString("engine"), SpeechEngineType.Android),
                language = o.optString("language", "id-ID"),
                voskModel = o.optString("voskModel").ifBlank { null },
                style = o.optJSONObject("style")?.let(this@SubtitleRepository::styleFrom) ?: SubtitleStyle(),
                presets = presets,
                corrections = corr,
            )
        }.getOrDefault(SubtitleConfig())
    }

    private fun styleTo(s: SubtitleStyle) = JSONObject()
        .put("words", s.maxWordsPerLine).put("lines", s.maxLines).put("clear", s.clearAfterSec.toDouble())
        .put("anim", s.animation.name).put("look", s.look.name).put("font", s.fontKey).put("weight", s.fontWeight)
        .put("size", s.size.toDouble()).put("text", s.textColor).put("accent", s.accentColor).put("stroke", s.strokeColor)
        .put("strokeW", s.strokeWidth.toDouble()).put("box", s.boxColor).put("opacity", s.opacity.toDouble())
        .put("hollow", s.hollow).put("pos", s.position.name).put("align", s.align.name)

    private fun styleFrom(o: JSONObject): SubtitleStyle {
        val d = SubtitleStyle()
        return SubtitleStyle(
            maxWordsPerLine = o.optInt("words", d.maxWordsPerLine).coerceIn(1, 12),
            maxLines = o.optInt("lines", d.maxLines).coerceIn(1, 4),
            clearAfterSec = o.optDouble("clear", d.clearAfterSec.toDouble()).toFloat().coerceIn(0.5f, 5f),
            animation = enumOr(o.optString("anim"), d.animation),
            look = enumOr(o.optString("look"), d.look),
            fontKey = o.optString("font", d.fontKey),
            fontWeight = o.optInt("weight", d.fontWeight),
            size = o.optDouble("size", d.size.toDouble()).toFloat(),
            textColor = o.optInt("text", d.textColor),
            accentColor = o.optInt("accent", d.accentColor),
            strokeColor = o.optInt("stroke", d.strokeColor),
            strokeWidth = o.optDouble("strokeW", d.strokeWidth.toDouble()).toFloat(),
            boxColor = o.optInt("box", d.boxColor),
            opacity = o.optDouble("opacity", 1.0).toFloat(),
            hollow = o.optBoolean("hollow", false),
            position = enumOr(o.optString("pos"), d.position),
            align = enumOr(o.optString("align"), d.align),
        )
    }
}
