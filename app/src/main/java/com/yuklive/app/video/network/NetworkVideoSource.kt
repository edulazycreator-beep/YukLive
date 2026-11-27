@file:OptIn(UnstableApi::class)

package com.yuklive.app.video.network

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.rtmp.RtmpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.yuklive.app.audio.AudioMixer
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

enum class NetworkInputType { Rtsp, SrtCaller, SrtListener, Rtmp, HttpFlv }

data class NetworkInputConfig(
    val type: NetworkInputType = NetworkInputType.SrtListener,
    val url: String = "",
    val port: Int = 9000,
    val latencyMs: Int = 120,
    val passphrase: String = "",
    val rtspOverTcp: Boolean = true,
) {
    fun playbackUri(): Uri = when (type) {
        NetworkInputType.SrtListener -> Uri.parse(
            buildString {
                append("srt://0.0.0.0:$port?mode=listener&latency=$latencyMs")
                if (passphrase.isNotBlank()) append("&passphrase=${Uri.encode(passphrase)}")
            },
        )
        NetworkInputType.SrtCaller -> {
            val base = Uri.parse(url.trim())
            val b = base.buildUpon().appendQueryParameter("mode", "caller")
            if (base.getQueryParameter("latency") == null) b.appendQueryParameter("latency", latencyMs.toString())
            if (passphrase.isNotBlank()) b.appendQueryParameter("passphrase", passphrase)
            b.build()
        }
        else -> Uri.parse(url.trim())
    }

    fun isValid(): Boolean = when (type) {
        NetworkInputType.SrtListener -> port in 1024..65535
        NetworkInputType.Rtsp -> url.startsWith("rtsp://", true) || url.startsWith("rtsps://", true)
        NetworkInputType.SrtCaller -> url.startsWith("srt://", true)
        NetworkInputType.Rtmp -> url.startsWith("rtmp://", true)
        NetworkInputType.HttpFlv -> url.startsWith("http://", true) || url.startsWith("https://", true)
    }
}

enum class NetworkStatus { Idle, Connecting, WaitingSender, Playing, Reconnecting, Error }

data class NetworkInputState(
    val config: NetworkInputConfig = NetworkInputConfig(),
    val status: NetworkStatus = NetworkStatus.Idle,
    val bitrateKbps: Int = 0,
    val bufferMs: Long = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val attempt: Int = 0,
    val error: String? = null,
)

/**
 * Sumber video dari jaringan: RTSP (kamera IP / HP lain), SRT caller/listener,
 * RTMP pull, dan HTTP-FLV. Video dirender ExoPlayer langsung ke SurfaceTexture
 * pipeline OpenGL; audio diambil lewat TeeAudioProcessor lalu dikirim ke mixer.
 * Auto-reconnect dengan jeda bertahap.
 */
@Singleton
class NetworkVideoSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mixer: AudioMixer,
) : VideoSource() {

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var streamSurface: Surface? = null
    private var testSurface: Surface? = null
    private var running = false
    private var wanted = false

    private val _state = MutableStateFlow(NetworkInputState())
    val state: StateFlow<NetworkInputState> = _state.asStateFlow()

    fun setConfig(config: NetworkInputConfig) {
        val changed = config != _state.value.config
        _state.update { it.copy(config = config) }
        if (changed && wanted) main.post { restartPlayer(resetAttempts = true) }
    }

    // ---------------------------------------------------------------- Tes tanpa stream

    /** Menjalankan input untuk tes di layar Sumber (tanpa ikut stream). */
    fun startTest(surface: Surface?) {
        testSurface = surface
        wanted = true
        main.post {
            if (player == null) restartPlayer(resetAttempts = true) else player?.setVideoSurface(currentSurface())
        }
    }

    fun stopTest() {
        testSurface = null
        if (!running) {
            wanted = false
            main.post { releasePlayer() }
        } else {
            main.post { player?.setVideoSurface(currentSurface()) }
        }
    }

    // ---------------------------------------------------------------- VideoSource

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        surfaceTexture.setDefaultBufferSize(width, height)
        streamSurface = Surface(surfaceTexture)
        running = true
        wanted = true
        main.post {
            if (player == null) restartPlayer(resetAttempts = true) else player?.setVideoSurface(currentSurface())
        }
    }

    override fun stop() {
        running = false
        val s = streamSurface
        streamSurface = null
        main.post {
            if (testSurface == null) {
                wanted = false
                releasePlayer()
            } else {
                player?.setVideoSurface(currentSurface())
            }
            s?.release()
        }
    }

    override fun release() {}

    override fun isRunning(): Boolean = running

    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

    // ---------------------------------------------------------------- Player

    private fun currentSurface(): Surface? = streamSurface ?: testSurface

    private val reconnectRunnable = Runnable { restartPlayer(resetAttempts = false) }
    private val statsRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return
            _state.update { it.copy(bufferMs = p.totalBufferedDuration) }
            main.postDelayed(this, 1000)
        }
    }

    private fun restartPlayer(resetAttempts: Boolean) {
        main.removeCallbacks(reconnectRunnable)
        releasePlayer()
        if (!wanted) return
        val config = _state.value.config
        if (!config.isValid()) {
            _state.update { it.copy(status = NetworkStatus.Error, error = "config") }
            return
        }
        if (resetAttempts) _state.update { it.copy(attempt = 0) }
        _state.update {
            it.copy(
                status = if (config.type == NetworkInputType.SrtListener) NetworkStatus.WaitingSender else NetworkStatus.Connecting,
                error = null,
            )
        }

        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean,
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(TeeAudioProcessor(audioTap)))
                .build()
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(300, 2000, 150, 300)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val p = ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .build()
        p.volume = 0f // audio tidak diputar ke speaker; tetap masuk mixer lewat tee
        p.setVideoSurface(currentSurface())
        p.addListener(playerListener)
        p.addAnalyticsListener(analytics)
        p.setMediaSource(buildMediaSource(config))
        p.playWhenReady = true
        p.prepare()
        player = p
        main.postDelayed(statsRunnable, 1000)
    }

    private fun buildMediaSource(config: NetworkInputConfig): MediaSource {
        val item = MediaItem.fromUri(config.playbackUri())
        return when (config.type) {
            NetworkInputType.Rtsp -> RtspMediaSource.Factory()
                .setForceUseRtpTcp(config.rtspOverTcp)
                .setTimeoutMs(8000)
                .createMediaSource(item)
            NetworkInputType.Rtmp -> ProgressiveMediaSource.Factory(RtmpDataSource.Factory())
                .createMediaSource(item)
            NetworkInputType.HttpFlv -> ProgressiveMediaSource.Factory(
                DefaultHttpDataSource.Factory().setConnectTimeoutMs(8000).setReadTimeoutMs(8000),
            ).createMediaSource(item)
            NetworkInputType.SrtCaller, NetworkInputType.SrtListener ->
                ProgressiveMediaSource.Factory(SrtDataSource.Factory(), DefaultExtractorsFactory())
                    .createMediaSource(item)
        }
    }

    private fun releasePlayer() {
        main.removeCallbacks(statsRunnable)
        player?.let {
            it.removeListener(playerListener)
            it.release()
        }
        player = null
        mixer.clearNetworkAudio()
        if (!wanted) _state.update { it.copy(status = NetworkStatus.Idle, bitrateKbps = 0, bufferMs = 0) }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(status = NetworkStatus.Playing, attempt = 0, error = null) }
            } else if (playbackState == Player.STATE_ENDED) {
                scheduleReconnect("ended")
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleReconnect(error.errorCodeName)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _state.update { it.copy(videoWidth = videoSize.width, videoHeight = videoSize.height) }
        }
    }

    private val analytics = object : AnalyticsListener {
        override fun onBandwidthEstimate(
            eventTime: AnalyticsListener.EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long,
        ) {
            if (totalLoadTimeMs > 0) {
                val kbps = (totalBytesLoaded * 8 / totalLoadTimeMs).toInt()
                _state.update { it.copy(bitrateKbps = kbps) }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!wanted) return
        val attempt = _state.value.attempt + 1
        val delay = min(10_000L, 1000L * (1 shl min(attempt - 1, 4)))
        _state.update { it.copy(status = NetworkStatus.Reconnecting, attempt = attempt, error = reason, bitrateKbps = 0) }
        mixer.clearNetworkAudio()
        main.removeCallbacks(reconnectRunnable)
        main.postDelayed(reconnectRunnable, delay)
    }

    // ---------------------------------------------------------------- Audio tap

    private val audioTap = object : TeeAudioProcessor.AudioBufferSink {
        private var rate = 48_000
        private var channels = 2
        private var encoding = C.ENCODING_PCM_16BIT
        private var shorts = ShortArray(0)

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            rate = sampleRateHz
            channels = channelCount
            this.encoding = encoding
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            val b = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
            val count: Int
            when (encoding) {
                C.ENCODING_PCM_16BIT -> {
                    count = b.remaining() / 2
                    if (shorts.size < count) shorts = ShortArray(count)
                    b.asShortBuffer().get(shorts, 0, count)
                }
                C.ENCODING_PCM_FLOAT -> {
                    count = b.remaining() / 4
                    if (shorts.size < count) shorts = ShortArray(count)
                    val fb = b.asFloatBuffer()
                    for (i in 0 until count) shorts[i] = (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt().toShort()
                }
                else -> return
            }
            if (count > 0) mixer.pushNetworkPcm(shorts, count, rate, channels)
        }
    }
}
