package com.yuklive.app.stream

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.pedro.common.ConnectChecker
import com.pedro.common.VideoCodec
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.video.VideoSource
import com.pedro.encoder.utils.gl.AspectRatioMode
import com.pedro.library.base.recording.RecordController
import com.pedro.library.generic.GenericStream
import com.pedro.library.util.BitrateAdapter
import com.yuklive.app.R
import com.yuklive.app.audio.AudioMixer
import com.yuklive.app.core.common.UiMessage
import com.yuklive.app.core.common.UiText
import com.yuklive.app.core.destination.Destination
import com.yuklive.app.core.device.DeviceStatus
import com.yuklive.app.core.device.DeviceStatusMonitor
import com.yuklive.app.core.live.LiveSessionRepository
import com.yuklive.app.core.settings.SettingsRepository
import com.yuklive.app.core.settings.SourceKind
import com.yuklive.app.core.settings.StreamOrientation
import com.yuklive.app.core.settings.StreamSettings
import com.yuklive.app.core.settings.TransitionType
import com.yuklive.app.core.settings.VideoCodecOption
import com.yuklive.app.overlay.OverlayRepository
import com.yuklive.app.overlay.Scene
import com.yuklive.app.subtitle.SubtitleEngine
import com.yuklive.app.video.CameraController
import com.yuklive.app.video.CameraEvent
import com.yuklive.app.video.UvcEvent
import com.yuklive.app.video.UvcVideoSource
import com.yuklive.app.video.network.NetworkVideoSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

enum class NetworkQuality { Unknown, Good, Fair, Poor }

sealed interface StreamPhase {
    data object Idle : StreamPhase
    data object Connecting : StreamPhase
    data object Live : StreamPhase
    data class Reconnecting(val secondsLeft: Int, val attempt: Int) : StreamPhase
}

data class StreamStats(
    val bitrateKbps: Int = 0,
    val targetBitrateKbps: Int = 0,
    val fps: Int = 0,
    val droppedFrames: Long = 0,
    val quality: NetworkQuality = NetworkQuality.Unknown,
)

data class EngineState(
    val phase: StreamPhase = StreamPhase.Idle,
    val source: SourceKind = SourceKind.Camera,
    val previewing: Boolean = false,
    val recording: Boolean = false,
    val recordStartedAt: Long? = null,
    val settings: StreamSettings = StreamSettings(),
    val throttled: Boolean = false,
    val destinationName: String? = null,
    val online: Boolean = true,
    val device: DeviceStatus = DeviceStatus(),
    val switching: Boolean = false,
) {
    val isActive: Boolean get() = phase != StreamPhase.Idle
}

/**
 * Inti streaming YukLive. Satu pipeline OpenGL (RootEncoder GenericStream):
 * sumber video (kamera / USB / Wi-Fi) → texture → filter overlay+subtitle → encoder + preview.
 *
 * Tugas: siapkan encoder sesuai pengaturan, preview, GO LIVE (RTMP/RTMPS/SRT/RTSP),
 * auto-reconnect dengan hitungan mundur, adaptive bitrate, rekam lokal MP4,
 * ganti sumber tanpa memutus stream (cut / fade), dan penurunan kualitas saat HP panas.
 * Semua fungsi publik dipanggil dari main thread.
 */
@Singleton
class StreamEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val mixer: AudioMixer,
    private val camera: CameraController,
    private val uvc: UvcVideoSource,
    private val network: NetworkVideoSource,
    private val colorBar: com.yuklive.app.video.ColorBarSource,
    private val compositor: StreamCompositor,
    private val overlayRepo: OverlayRepository,
    private val subtitles: SubtitleEngine,
    private val liveSession: LiveSessionRepository,
    deviceMonitor: DeviceStatusMonitor,
) : ConnectChecker {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private var stream: GenericStream? = null
    private var preparedWith: StreamSettings? = null
    private var previewView: SurfaceView? = null

    private var endpoint: String? = null
    private var userEnding = false
    private var reconnectAttempt = 0
    private var countdownJob: Job? = null
    private var statsJob: Job? = null
    private var recordFile: File? = null
    private var batteryWarned = false

    private val bitrateAdapter = BitrateAdapter { bitrate ->
        stream?.setVideoBitrateOnFly(bitrate)
        _stats.update { it.copy(targetBitrateKbps = bitrate / 1000) }
    }

    private val _studioMode = MutableStateFlow(false)
    val studioMode: StateFlow<Boolean> = _studioMode.asStateFlow()
    private val programSnapshot = MutableStateFlow<Scene?>(null)

    /**
     * Scene yang sedang tayang. Mode biasa: scene aktif (perubahan langsung tayang).
     * Studio Mode: salinan scene saat transisi terakhir, jadi edit di Preview tidak
     * ikut tayang sebelum tombol TRANSISI ditekan.
     */
    val programScene: StateFlow<Scene> = combine(overlayRepo.document, programSnapshot) { doc, snap ->
        snap?.takeIf { s -> doc.scenes.any { it.id == s.id } } ?: doc.activeScene
    }.stateIn(scope, SharingStarted.Eagerly, overlayRepo.document.value.activeScene)

    init {
        scope.launch {
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(settings = s) }
                if (!isBusy()) reprepareIfNeeded(s)
            }
        }
        scope.launch { deviceMonitor.status.collect { onDeviceStatus(it) } }
        scope.launch {
            deviceMonitor.online.drop(1).collect { online ->
                _state.update { it.copy(online = online) }
                if (_state.value.isActive) {
                    message(if (online) R.string.msg_network_back else R.string.msg_network_lost,
                        if (online) UiMessage.Level.Success else UiMessage.Level.Warning)
                }
            }
        }
        scope.launch {
            camera.events.collect { e ->
                when (e) {
                    CameraEvent.InUseByOtherApp -> message(R.string.msg_camera_in_use, UiMessage.Level.Error)
                    is CameraEvent.Error -> message(R.string.msg_camera_error, UiMessage.Level.Error)
                }
            }
        }
        scope.launch {
            uvc.events.collect { e ->
                when (e) {
                    UvcEvent.Detached -> if (_state.value.source == SourceKind.Usb) {
                        switchSource(SourceKind.Camera, announce = false)
                        message(R.string.msg_usb_unplugged_fallback, UiMessage.Level.Warning)
                    }
                    UvcEvent.Attached -> message(R.string.msg_usb_attached, UiMessage.Level.Info)
                    is UvcEvent.Error -> message(R.string.msg_usb_permission, UiMessage.Level.Error)
                }
            }
        }
        // Scene Program menentukan sumber video & overlay yang tayang.
        scope.launch {
            var lastKey: Pair<String, SourceKind>? = null
            programScene.collect { scene ->
                compositor.programOverride = if (studioMode.value) scene else null
                compositor.invalidate()
                val key = scene.id to scene.source
                if (key != lastKey) {
                    val first = lastKey == null
                    lastKey = key
                    if (first && stream == null) {
                        // Belum ada pipeline: cukup pakai sumber scene aktif sejak awal.
                        _state.update { it.copy(source = scene.source) }
                    } else if (!sceneSwitchInProgress && scene.source != _state.value.source) {
                        switchSource(scene.source, announce = false)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Preview

    // ---------------------------------------------------------------- Studio Mode

    fun setStudioMode(on: Boolean) {
        _studioMode.value = on
        programSnapshot.value = if (on) overlayRepo.document.value.activeScene else null
        // Studio Mode: overlay hanya dibakar ke output (encoder). Kedua panel menampilkan
        // video bersih dan overlay digambar di UI, supaya Preview & Program bisa beda.
        compositor.filter.renderMode =
            if (on) com.pedro.encoder.input.gl.render.RenderMode.OUTPUT else com.pedro.encoder.input.gl.render.RenderMode.ALL
        compositor.programOverride = programSnapshot.value
        compositor.invalidate()
        if (!on) pvwSurface?.let { surf -> runCatching { stream?.getGlInterface()?.removeMultiPreviewSurface(surf) } }
    }

    // Panel PREVIEW: permukaan kedua yang menerima video langsung.
    private var pvwSurface: android.view.Surface? = null
    private var pvwSize = 0 to 0

    fun attachPvw(surface: android.view.Surface, width: Int, height: Int) {
        pvwSurface = surface
        pvwSize = width to height
        addPvw()
    }

    fun detachPvw(surface: android.view.Surface) {
        runCatching { stream?.getGlInterface()?.removeMultiPreviewSurface(surface) }
        if (pvwSurface === surface) pvwSurface = null
    }

    private fun addPvw() {
        val surf = pvwSurface ?: return
        val s = stream ?: return
        if (!_studioMode.value || !surf.isValid) return
        val gl = s.getGlInterface()
        if (!gl.isRunning) return
        val portrait = _state.value.settings.orientation == StreamOrientation.Portrait &&
            _state.value.source == SourceKind.Camera
        runCatching {
            gl.addMultiPreviewSurface(
                surf,
                com.pedro.library.view.preview.MultiPreviewConfig(
                    width = pvwSize.first, height = pvwSize.second,
                    aspectRatioMode = AspectRatioMode.Adjust, isPortrait = portrait,
                ),
            )
        }
    }

    fun attachPreview(view: SurfaceView) {
        val old = previewView
        previewView = view
        uvc.startMonitoring()
        val s = ensureStream()
        if (preparedWith == null && !prepare(_state.value.settings)) return
        runCatching {
            // Layar preview diganti (mis. Studio Mode dinyalakan): lepas yang lama dulu.
            if (s.isOnPreview && old != null && old !== view) {
                s.stopPreview(true)
                if (!isBusy()) camera.onClosed()
            }
            if (!s.isOnPreview) {
                ensureOverlayFilter(s)
                s.startPreview(view, true)
            }
            watchSurface(view)
            addPvw()
            compositor.start()
            _state.update { it.copy(previewing = true) }
            if (_state.value.source == SourceKind.Camera) scope.launch {
                delay(600)
                camera.applySelectedLens()
            }
        }.onFailure { message(R.string.msg_preview_failed, UiMessage.Level.Error) }
    }

    /**
     * RootEncoder melepas callback-nya saat surface hancur (aplikasi ke background),
     * jadi preview tidak menyala lagi saat kembali. Callback ini yang menyalakan ulang.
     */
    private val watchedViews = java.util.WeakHashMap<SurfaceView, Boolean>()

    private fun watchSurface(view: SurfaceView) {
        if (watchedViews.containsKey(view)) return
        watchedViews[view] = true
        view.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                view.post {
                    val s = stream ?: return@post
                    if (previewView !== view || s.isOnPreview || preparedWith == null) return@post
                    runCatching {
                        ensureOverlayFilter(s)
                        s.startPreview(view, true)
                        applyOrientation(s, _state.value.source)
                        addPvw()
                        compositor.invalidate()
                    }
                    if (_state.value.source == SourceKind.Camera) scope.launch {
                        delay(600)
                        camera.applySelectedLens()
                    }
                }
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {
                if (previewView === view) runCatching { stream?.getGlInterface()?.setPreviewResolution(width, height) }
            }
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {}
        })
    }

    fun detachPreview(view: SurfaceView) {
        if (previewView !== view) return
        previewView = null
        val s = stream ?: return
        runCatching { if (s.isOnPreview) s.stopPreview(true) }
        if (!isBusy()) {
            compositor.stop()
            camera.onClosed()
        }
        _state.update { it.copy(previewing = false) }
    }

    // ---------------------------------------------------------------- Live

    fun goLive(destination: Destination) {
        if (_state.value.isActive) return
        val s = ensureStream()
        val settings = _state.value.settings
        if (preparedWith == null && !prepare(settings)) return
        val url = destination.endpoint()
        endpoint = url
        userEnding = false
        reconnectAttempt = 0
        batteryWarned = false
        subtitles.resetTranscript()
        _state.update { it.copy(phase = StreamPhase.Connecting, destinationName = destination.name) }
        _stats.value = StreamStats(targetBitrateKbps = settings.videoBitrateKbps)
        bitrateAdapter.setMaxBitrate(settings.videoBitrateKbps * 1000)
        bitrateAdapter.reset()

        updateService()
        compositor.start()
        runCatching {
            s.getStreamClient().setReTries(MAX_RETRIES)
            ensureOverlayFilter(s)
            s.startStream(url)
            if (settings.recordLocally && !s.isRecording) startRecording(s)
        }.onFailure {
            message(UiText.res(R.string.msg_start_failed, it.message.orEmpty()), UiMessage.Level.Error)
            cleanupAfterEnd()
        }
    }

    fun endLive() {
        val s = stream ?: return
        if (!_state.value.isActive) return
        userEnding = true
        countdownJob?.cancel()
        runCatching { if (s.isStreaming) s.stopStream() }
        cleanupAfterEnd()
        message(R.string.msg_live_ended, UiMessage.Level.Info)
    }

    private fun cleanupAfterEnd() {
        statsJob?.cancel()
        countdownJob?.cancel()
        liveSession.stop()
        compositor.liveStartedAt = null
        val stillRecording = stream?.isRecording == true
        _state.update {
            it.copy(
                phase = StreamPhase.Idle,
                recording = stillRecording,
                recordStartedAt = if (stillRecording) it.recordStartedAt else null,
                throttled = false,
            )
        }
        _stats.value = StreamStats()
        if (!stillRecording) {
            recordFile?.let { f -> scope.launch { publishRecording(f) } }
            recordFile = null
        }
        afterSessionChange()
    }

    /** Dipanggil setiap live/rekam berhenti: matikan service & compositor bila sudah tidak dipakai. */
    private fun afterSessionChange() {
        updateService()
        if (!isBusy()) {
            if (previewView == null) {
                compositor.stop()
                camera.onClosed()
            }
            // Pengaturan yang diubah selama live/rekam diterapkan sekarang.
            reprepareIfNeeded(_state.value.settings)
        }
    }

    private var serviceRunning = false

    private fun updateService() {
        val needed = _state.value.isActive || stream?.isRecording == true
        if (needed && !serviceRunning) {
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
            serviceRunning = true
        } else if (!needed && serviceRunning) {
            context.stopService(Intent(context, StreamService::class.java))
            serviceRunning = false
        }
    }

    /** Ubah bitrate saat live (slider di Studio). */
    fun setLiveBitrate(kbps: Int) {
        val k = kbps.coerceIn(StreamSettings.MinVideoBitrate, StreamSettings.MaxVideoBitrate)
        scope.launch { settingsRepo.update { it.copy(videoBitrateKbps = k) } }
        if (_state.value.isActive) {
            stream?.setVideoBitrateOnFly(k * 1000)
            bitrateAdapter.setMaxBitrate(k * 1000)
            _stats.update { it.copy(targetBitrateKbps = k) }
        }
    }

    /** Dari notifikasi: akhiri live dan rekaman sekaligus. */
    fun stopAll() {
        endLive()
        if (stream?.isRecording == true) toggleRecording()
    }

    /** Rekam otomatis setiap GO LIVE (pengaturan). */
    fun setRecordWithLive(on: Boolean) {
        scope.launch { settingsRepo.update { it.copy(recordLocally = on) } }
    }

    /** Tombol REC: mulai/berhenti merekam, terlepas dari live (seperti OBS). */
    fun toggleRecording() {
        val s = ensureStream()
        if (s.isRecording) {
            runCatching { s.stopRecord() }
            _state.update { it.copy(recording = false, recordStartedAt = null) }
            recordFile?.let { f -> scope.launch { publishRecording(f) } }
            recordFile = null
            afterSessionChange()
            return
        }
        if (preparedWith == null && !prepare(_state.value.settings)) return
        ensureOverlayFilter(s)
        compositor.start()
        startRecording(s)
        updateService()
    }

    // ---------------------------------------------------------------- Sumber & scene

    fun switchSource(kind: SourceKind, announce: Boolean = true, allowFade: Boolean = true) {
        val current = _state.value.source
        if (kind == current || _state.value.switching) return
        if (kind == SourceKind.Usb && !uvc.isReady) {
            if (announce) message(if (uvc.hasOtg()) R.string.msg_usb_not_ready else R.string.msg_no_otg, UiMessage.Level.Warning)
            return
        }
        val s = stream
        if (s == null || preparedWith == null) {
            _state.update { it.copy(source = kind) }
            stream?.let { st -> runCatching { st.changeVideoSource(sourceFor(kind)); applyOrientation(st, kind) } }
            return
        }
        val fade = allowFade && _state.value.settings.transition == TransitionType.Fade && (s.isOnPreview || s.isStreaming)
        _state.update { it.copy(switching = true) }
        scope.launch {
            if (fade) {
                compositor.fade(1f, FADE_MS)
                delay(FADE_MS)
            }
            val ok = runCatching {
                if (current == SourceKind.Camera) camera.onClosed()
                s.changeVideoSource(sourceFor(kind))
                applyOrientation(s, kind)
            }.isSuccess
            if (ok) {
                _state.update { it.copy(source = kind) }
                addPvw()
                if (kind == SourceKind.Camera) {
                    delay(500)
                    camera.applySelectedLens()
                }
            } else {
                message(R.string.msg_switch_failed, UiMessage.Level.Error)
            }
            if (fade) compositor.fade(0f, FADE_MS)
            _state.update { it.copy(switching = false) }
        }
    }

    fun applyScene(scene: Scene) = transitionToScene(scene.id)

    /**
     * Pindahkan scene ke PROGRAM (yang tayang). Fade menutupi pergantian sumber video
     * sekaligus overlay, seperti tombol Transition di OBS.
     */
    fun transitionToScene(sceneId: String) {
        val doc = overlayRepo.document.value
        val target = doc.scenes.firstOrNull { it.id == sceneId } ?: return
        val studio = _studioMode.value
        // Studio Mode: scene yang sama tetap boleh ditransisi (menayangkan hasil edit Preview).
        if ((!studio && doc.activeScene.id == sceneId) || _state.value.switching) return
        val fade = _state.value.settings.transition == TransitionType.Fade && _state.value.previewing
        if (!fade) {
            sceneSwitchInProgress = true
            if (studio) programSnapshot.value = target
            overlayRepo.setActiveScene(sceneId)
            if (target.source != _state.value.source) switchSource(target.source, allowFade = false)
            sceneSwitchInProgress = false
            return
        }
        scope.launch {
            compositor.fade(1f, FADE_MS)
            delay(FADE_MS)
            sceneSwitchInProgress = true
            if (studio) programSnapshot.value = overlayRepo.document.value.scenes.firstOrNull { it.id == sceneId } ?: target
            overlayRepo.setActiveScene(sceneId)
            if (target.source != _state.value.source) {
                switchSource(target.source, allowFade = false)
                delay(150)
            }
            sceneSwitchInProgress = false
            compositor.fade(0f, FADE_MS)
        }
    }

    private var sceneSwitchInProgress = false

    fun setOverlayEnabled(enabled: Boolean) {
        compositor.overlayEnabled = enabled
        compositor.invalidate()
    }

    fun setMuted(muted: Boolean) = mixer.setMasterMuted(muted)

    private fun sourceFor(kind: SourceKind): VideoSource = when (kind) {
        SourceKind.Camera -> camera.source
        SourceKind.Usb -> uvc
        SourceKind.Wifi -> network
        SourceKind.ColorBar -> colorBar
    }

    // ---------------------------------------------------------------- Persiapan encoder

    private fun isBusy(): Boolean = stream?.let { it.isStreaming || it.isRecording } == true

    private fun ensureStream(): GenericStream {
        stream?.let { return it }
        val s = GenericStream(context, this, sourceFor(_state.value.source), mixer)
        s.getGlInterface().setAspectRatioMode(AspectRatioMode.Adjust)
        s.setFpsListener { fps -> _stats.update { it.copy(fps = fps) } }
        stream = s
        return s
    }

    /**
     * RootEncoder menghapus semua filter saat pipeline GL berhenti (stopPreview tanpa live/rekam).
     * Jadi filter overlay dipasang ulang setiap kali GL akan dinyalakan lagi.
     */
    private fun ensureOverlayFilter(s: GenericStream) {
        val gl = s.getGlInterface()
        if (gl.isRunning()) return
        gl.clearFilters()
        gl.addFilter(compositor.filter)
        compositor.invalidate()
    }

    private fun reprepareIfNeeded(settings: StreamSettings) {
        val s = stream ?: return
        if (preparedWith == settings || isBusy()) return
        val view = previewView
        val wasPreview = s.isOnPreview
        if (wasPreview) runCatching { s.stopPreview(true) }
        if (prepare(settings) && wasPreview && view != null) {
            runCatching {
                ensureOverlayFilter(s)
                s.startPreview(view, true)
            }
            if (_state.value.source == SourceKind.Camera) scope.launch {
                delay(600)
                camera.applySelectedLens()
            }
        }
    }

    private fun prepare(requested: StreamSettings): Boolean {
        val s = ensureStream()
        var settings = requested
        val ok = runCatching { prepareWith(s, settings) }.getOrElse { false }
        if (!ok && settings.resolution != com.yuklive.app.core.settings.Resolution.HD720) {
            // Fallback: resolusi/fps tidak didukung sumber / encoder.
            settings = settings.copy(
                resolution = if (settings.resolution == com.yuklive.app.core.settings.Resolution.UHD4K)
                    com.yuklive.app.core.settings.Resolution.FHD1080
                else com.yuklive.app.core.settings.Resolution.HD720,
                fps = 30,
            )
            val ok2 = runCatching { prepareWith(s, settings) }.getOrElse { false }
            if (ok2) {
                message(UiText.res(R.string.msg_resolution_fallback, settings.resolution.label), UiMessage.Level.Warning)
                preparedWith = requested
                return true
            }
            message(R.string.msg_prepare_failed, UiMessage.Level.Error)
            preparedWith = null
            return false
        }
        if (!ok) {
            message(R.string.msg_prepare_failed, UiMessage.Level.Error)
            preparedWith = null
            return false
        }
        preparedWith = requested
        return true
    }

    private var preparedSettings: StreamSettings? = null

    private fun prepareWith(s: GenericStream, st: StreamSettings): Boolean {
        preparedSettings = st
        s.setVideoCodec(if (st.codec == VideoCodecOption.H265) VideoCodec.H265 else VideoCodec.H264)
        val rotation = if (st.orientation == StreamOrientation.Portrait) 90 else 0
        val video = s.prepareVideo(
            width = st.resolution.width,
            height = st.resolution.height,
            bitrate = st.videoBitrateKbps * 1000,
            fps = st.fps,
            iFrameInterval = st.keyframeSec,
            rotation = rotation,
        )
        val audio = s.prepareAudio(st.sampleRate, st.stereo, st.audioBitrateKbps * 1000)
        compositor.setOutputSize(st.outputWidth, st.outputHeight)
        if (video) applyOrientation(s, _state.value.source, st)
        return video && audio
    }

    /**
     * RootEncoder menebak orientasi kamera dari rotasi layar SAAT prepare / ganti sumber.
     * Kalau layar belum selesai berputar (Studio baru dibuka), hasilnya miring atau gepeng.
     * Jadi orientasi dikunci eksplisit sesuai orientasi stream, bukan sesuai layar.
     *  - Kamera HP: buffer kamera sudah dikoreksi sensor → 270 (landscape) / 0 (portrait).
     *  - USB / Wi-Fi: frame sudah tegak → tanpa rotasi.
     */
    private fun applyOrientation(
        s: GenericStream,
        kind: SourceKind,
        st: StreamSettings = preparedSettings ?: _state.value.settings,
    ) {
        val portrait = st.orientation == StreamOrientation.Portrait
        // USB / Wi-Fi / Color bar memakai konfigurasi orientasinya sendiri (landscape paksa).
        if (kind != SourceKind.Camera) return
        val cameraOrientation = if (portrait) 0 else 270
        runCatching {
            s.getGlInterface().setOrientationConfig(
                OrientationConfig(cameraOrientation = cameraOrientation, isPortrait = portrait),
            )
        }
    }

    // ---------------------------------------------------------------- Rekam lokal

    private fun startRecording(s: GenericStream) {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "YukLive").apply { mkdirs() }
        val name = "YukLive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val file = File(dir, name)
        runCatching {
            s.startRecord(file.absolutePath) { status ->
                if (status == RecordController.Status.STARTED || status == RecordController.Status.RECORDING) {
                    _state.update { it.copy(recording = true) }
                }
            }
            recordFile = file
            _state.update { it.copy(recording = true, recordStartedAt = SystemClock.elapsedRealtime()) }
        }.onFailure { message(R.string.msg_record_failed, UiMessage.Level.Error) }
    }

    /** Pindahkan rekaman ke galeri (Movies/YukLive). */
    private suspend fun publishRecording(file: File) = withContext(Dispatchers.IO) {
        delay(800) // tunggu muxer selesai menulis
        if (!file.exists() || file.length() == 0L) return@withContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/YukLive")
            }
            val ok = runCatching {
                val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
                file.delete()
            }.isSuccess
            message(if (ok) R.string.msg_record_saved_gallery else R.string.msg_record_saved_app,
                UiMessage.Level.Success)
        } else {
            message(R.string.msg_record_saved_app, UiMessage.Level.Success)
        }
    }

    // ---------------------------------------------------------------- Kesehatan HP

    private fun onDeviceStatus(d: DeviceStatus) {
        _state.update { it.copy(device = d) }
        val st = _state.value
        if (!st.isActive) return
        val s = stream ?: return
        if (d.isCritical && !st.throttled) {
            val reduced = (st.settings.videoBitrateKbps * 0.6f).toInt()
            s.setVideoBitrateOnFly(reduced * 1000)
            bitrateAdapter.setMaxBitrate(reduced * 1000)
            runCatching { s.getGlInterface().forceFpsLimit(min(st.settings.fps, 24)) }
            _state.update { it.copy(throttled = true) }
            _stats.update { it.copy(targetBitrateKbps = reduced) }
            message(R.string.msg_device_hot, UiMessage.Level.Warning)
        } else if (st.throttled && d.isCool) {
            s.setVideoBitrateOnFly(st.settings.videoBitrateKbps * 1000)
            bitrateAdapter.setMaxBitrate(st.settings.videoBitrateKbps * 1000)
            runCatching { s.getGlInterface().forceFpsLimit(st.settings.fps) }
            _state.update { it.copy(throttled = false) }
            _stats.update { it.copy(targetBitrateKbps = st.settings.videoBitrateKbps) }
            message(R.string.msg_device_cooled, UiMessage.Level.Success)
        }
        if (d.isBatteryLow && !batteryWarned) {
            batteryWarned = true
            message(R.string.msg_battery_low, UiMessage.Level.Warning)
        }
    }

    // ---------------------------------------------------------------- ConnectChecker

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        scope.launch {
            countdownJob?.cancel()
            val wasReconnect = _state.value.phase is StreamPhase.Reconnecting
            reconnectAttempt = 0
            _state.update { it.copy(phase = StreamPhase.Live) }
            if (!wasReconnect) {
                liveSession.start()
                compositor.liveStartedAt = liveSession.state.value.startedAtElapsed ?: SystemClock.elapsedRealtime()
                startStatsLoop()
                message(R.string.msg_live_started, UiMessage.Level.Success)
            } else {
                message(R.string.msg_reconnected, UiMessage.Level.Success)
            }
        }
    }

    override fun onConnectionFailed(reason: String) {
        scope.launch {
            if (userEnding || !_state.value.isActive) return@launch
            val s = stream ?: return@launch
            reconnectAttempt++
            val canRetry = runCatching { s.getStreamClient().reTry(RETRY_DELAY_MS, reason) }.getOrDefault(false)
            if (canRetry) {
                startCountdown(reconnectAttempt)
            } else {
                runCatching { s.stopStream() }
                cleanupAfterEnd()
                message(UiText.res(R.string.msg_connection_failed, friendlyReason(reason)), UiMessage.Level.Error)
            }
        }
    }

    override fun onDisconnect() {}

    override fun onAuthError() {
        scope.launch {
            val s = stream ?: return@launch
            userEnding = true
            runCatching { s.stopStream() }
            cleanupAfterEnd()
            message(R.string.msg_auth_error, UiMessage.Level.Error)
        }
    }

    override fun onAuthSuccess() {}

    override fun onNewBitrate(bitrate: Long) {
        val s = stream ?: return
        _stats.update { it.copy(bitrateKbps = (bitrate / 1000).toInt()) }
        val st = _state.value
        if (st.settings.adaptiveBitrate && !st.throttled && st.phase == StreamPhase.Live) {
            val congested = runCatching { s.getStreamClient().hasCongestion() }.getOrDefault(false)
            bitrateAdapter.adaptBitrate(bitrate, congested)
        }
    }

    private fun startCountdown(attempt: Int) {
        countdownJob?.cancel()
        countdownJob = scope.launch {
            var left = (RETRY_DELAY_MS / 1000).toInt()
            while (left >= 0 && isActive) {
                _state.update { it.copy(phase = StreamPhase.Reconnecting(left, attempt)) }
                delay(1000)
                left--
            }
        }
    }

    private fun startStatsLoop() {
        statsJob?.cancel()
        statsJob = scope.launch {
            while (isActive) {
                val s = stream ?: break
                val dropped = runCatching { s.getStreamClient().getDroppedVideoFrames() }.getOrDefault(0L)
                val congested = runCatching { s.getStreamClient().hasCongestion() }.getOrDefault(false)
                _stats.update { st ->
                    val target = max(1, st.targetBitrateKbps)
                    val quality = when {
                        _state.value.phase !is StreamPhase.Live -> NetworkQuality.Unknown
                        congested -> NetworkQuality.Poor
                        st.bitrateKbps < target * 0.6f -> NetworkQuality.Fair
                        else -> NetworkQuality.Good
                    }
                    st.copy(droppedFrames = dropped, quality = quality)
                }
                delay(1000)
            }
        }
    }

    private fun friendlyReason(reason: String): String {
        val r = reason.lowercase()
        return when {
            "timeout" in r || "timed out" in r -> context.getString(R.string.reason_timeout)
            "refused" in r -> context.getString(R.string.reason_refused)
            "unknownhost" in r || "unable to resolve" in r || "no address" in r -> context.getString(R.string.reason_host)
            "ssl" in r || "tls" in r || "certificate" in r -> context.getString(R.string.reason_tls)
            "broken pipe" in r || "reset" in r || "closed" in r -> context.getString(R.string.reason_closed)
            else -> reason
        }
    }

    private fun message(id: Int, level: UiMessage.Level) = message(UiText.res(id), level)

    private fun message(text: UiText, level: UiMessage.Level) {
        _messages.tryEmit(UiMessage(text, level))
    }

    private companion object {
        const val MAX_RETRIES = 30
        const val RETRY_DELAY_MS = 5000L
        const val FADE_MS = 250L
    }
}
