package com.yuklive.app.feature.studio

import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yuklive.app.audio.AudioChannel
import com.yuklive.app.audio.AudioMixer
import com.yuklive.app.audio.MixerState
import com.yuklive.app.core.common.UiMessage
import com.yuklive.app.core.destination.DestinationRepository
import com.yuklive.app.core.destination.DestinationState
import com.yuklive.app.core.live.LiveSessionRepository
import com.yuklive.app.core.settings.SettingsRepository
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.StreamSettings
import com.yuklive.app.overlay.OverlayDocument
import com.yuklive.app.overlay.OverlayRepository
import com.yuklive.app.stream.EngineState
import com.yuklive.app.stream.StreamEngine
import com.yuklive.app.stream.StreamStats
import com.yuklive.app.subtitle.SubtitleEngine
import com.yuklive.app.subtitle.SubtitleRepository
import com.yuklive.app.video.CameraController
import com.yuklive.app.video.CameraLens
import com.yuklive.app.video.CameraUiState
import com.yuklive.app.video.WhiteBalance
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val engine: StreamEngine,
    private val camera: CameraController,
    private val mixer: AudioMixer,
    private val destinations: DestinationRepository,
    private val overlayRepo: OverlayRepository,
    private val subtitleRepo: SubtitleRepository,
    private val settingsRepo: SettingsRepository,
    liveSession: LiveSessionRepository,
    subtitleEngine: SubtitleEngine,
) : ViewModel() {

    val engineState: StateFlow<EngineState> = engine.state
    val stats: StateFlow<StreamStats> = engine.stats
    val messages: SharedFlow<UiMessage> = engine.messages
    val cameraState: StateFlow<CameraUiState> = camera.state
    val mixerState: StateFlow<MixerState> = mixer.state
    val overlay: StateFlow<OverlayDocument> = overlayRepo.document
    val recognizer = subtitleEngine.recognizer

    val destinationState: StateFlow<DestinationState> = destinations.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DestinationState())

    val subtitleEnabled: StateFlow<Boolean> = subtitleRepo.config.map { it.enabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val elapsedMillis: StateFlow<Long> = liveSession.state.flatMapLatest { session ->
        val started = session.startedAtElapsed
        if (session.isLive && started != null) {
            flow {
                while (true) {
                    emit(SystemClock.elapsedRealtime() - started)
                    delay(500)
                }
            }
        } else {
            flowOf(0L)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0L)

    private val _overlayOn = MutableStateFlow(true)
    val overlayOn: StateFlow<Boolean> = _overlayOn.asStateFlow()

    // ---------------------------------------------------------------- Preview

    fun attachPreview(view: SurfaceView) = engine.attachPreview(view)
    fun detachPreview(view: SurfaceView) = engine.detachPreview(view)

    fun onPreviewTap(view: View, event: MotionEvent) {
        if (engine.state.value.source == SourceKind.Camera) camera.tapToFocus(view, event)
    }

    fun onPreviewPinch(event: MotionEvent) {
        if (engine.state.value.source == SourceKind.Camera) camera.onPinch(event)
    }

    fun startMonitoring() = mixer.acquireMonitor()
    fun stopMonitoring() = mixer.releaseMonitor()

    // ---------------------------------------------------------------- Live

    /** @return false kalau belum ada tujuan stream (UI menampilkan pemilih). */
    fun goLive(): Boolean {
        val dest = destinationState.value.selected ?: return false
        engine.goLive(dest)
        return true
    }

    fun endLive() = engine.endLive()

    fun selectDestination(id: String) {
        viewModelScope.launch { destinations.select(id) }
    }

    fun selectDestinationAndGo(id: String) {
        viewModelScope.launch {
            destinations.select(id)
            destinations.current().selected?.let { engine.goLive(it) }
        }
    }

    // ---------------------------------------------------------------- Kontrol

    fun switchSource(kind: SourceKind) = engine.switchSource(kind)

    fun applyScene(id: String) {
        overlay.value.scenes.firstOrNull { it.id == id }?.let(engine::applyScene)
    }

    fun toggleSubtitle() {
        viewModelScope.launch { subtitleRepo.update { it.copy(enabled = !it.enabled) } }
    }

    fun toggleMute() = engine.setMuted(!mixer.state.value.masterMuted)

    fun toggleOverlay() {
        val next = !_overlayOn.value
        _overlayOn.value = next
        engine.setOverlayEnabled(next)
    }

    fun updateSettings(transform: (StreamSettings) -> StreamSettings) {
        viewModelScope.launch { settingsRepo.update(transform) }
    }

    fun setLiveBitrate(kbps: Int) = engine.setLiveBitrate(kbps)
    fun setRecording(on: Boolean) = engine.setRecording(on)

    // Kamera
    fun selectLens(lens: CameraLens) = camera.selectLens(lens)
    fun setZoom(z: Float) = camera.setZoom(z)
    fun setExposure(e: Int) = camera.setExposure(e)
    fun setTorch(on: Boolean) = camera.setTorch(on)
    fun setFocusLocked(on: Boolean) = camera.setFocusLocked(on)
    fun setWhiteBalance(wb: WhiteBalance) = camera.setWhiteBalance(wb)
    fun setStabilization(on: Boolean) = camera.setStabilization(on)

    // Audio
    fun setGain(ch: AudioChannel, g: Float) = mixer.setGain(ch, g)
    fun setChannelMuted(ch: AudioChannel, m: Boolean) = mixer.setMuted(ch, m)
}
