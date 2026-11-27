package com.yuklive.app.feature.subtitle

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuklive.app.overlay.FontOption
import com.yuklive.app.overlay.FontRegistry
import com.yuklive.app.subtitle.Correction
import com.yuklive.app.subtitle.RecognizerState
import com.yuklive.app.subtitle.SpeechEngineType
import com.yuklive.app.subtitle.SubtitleConfig
import com.yuklive.app.subtitle.SubtitleEngine
import com.yuklive.app.subtitle.SubtitleRenderer
import com.yuklive.app.subtitle.SubtitleRepository
import com.yuklive.app.subtitle.SubtitleStyle
import com.yuklive.app.subtitle.engine.VoskDownloadState
import com.yuklive.app.subtitle.engine.VoskModelManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SubtitleViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SubtitleRepository,
    private val engine: SubtitleEngine,
    private val fonts: FontRegistry,
    private val vosk: VoskModelManager,
    val renderer: SubtitleRenderer,
) : ViewModel() {

    val config: StateFlow<SubtitleConfig> = repo.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SubtitleConfig())
    val recognizer: StateFlow<RecognizerState> = engine.recognizer
    val fontOptions: StateFlow<List<FontOption>> = fonts.options
    val voskState: StateFlow<VoskDownloadState> = vosk.state
    val transcriptCount: StateFlow<Int> = engine.transcriptCount

    fun previewFrame() = engine.previewFrame()

    fun setEnabled(on: Boolean) = edit { it.copy(enabled = on) }
    fun setEngine(type: SpeechEngineType) = edit { it.copy(engine = type) }
    fun setLanguage(tag: String) = edit { it.copy(language = tag) }
    fun setVoskModel(name: String?) = edit { it.copy(voskModel = name) }

    fun style(transform: (SubtitleStyle) -> SubtitleStyle) = edit { it.copy(style = transform(it.style)) }

    fun savePreset(slot: Int) = edit { c ->
        c.copy(presets = c.presets.toMutableList().also { it[slot] = c.style })
    }

    fun loadPreset(slot: Int) = edit { c -> c.presets.getOrNull(slot)?.let { c.copy(style = it) } ?: c }

    fun addCorrection(wrong: String, right: String) {
        if (wrong.isBlank()) return
        edit { it.copy(corrections = it.corrections + Correction(wrong.trim(), right.trim())) }
    }

    fun removeCorrection(index: Int) = edit { c ->
        c.copy(corrections = c.corrections.filterIndexed { i, _ -> i != index })
    }

    fun test(text: String) = engine.testText(text)

    fun importFont(uri: Uri) {
        viewModelScope.launch {
            val key = withContext(Dispatchers.IO) { fonts.importFont(uri) } ?: return@launch
            style { it.copy(fontKey = key) }
        }
    }

    fun downloadModel(url: String) {
        viewModelScope.launch {
            val name = vosk.download(url)
            if (name != null) setVoskModel(name)
        }
    }

    fun deleteModel(name: String) {
        vosk.delete(name)
        if (config.value.voskModel == name) setVoskModel(null)
    }

    /** Tulis transkrip ke file yang dipilih pengguna. */
    fun export(uri: Uri, srt: Boolean, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val text = if (srt) engine.exportSrt() else engine.exportTxt()
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
            }
            onDone(ok)
        }
    }

    private fun edit(transform: (SubtitleConfig) -> SubtitleConfig) {
        viewModelScope.launch { repo.update(transform) }
    }
}
