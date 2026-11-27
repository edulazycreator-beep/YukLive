package com.yuklive.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import com.pedro.common.TimeUtils
import com.pedro.encoder.Frame
import com.pedro.encoder.input.audio.GetMicrophoneData
import com.pedro.encoder.input.sources.audio.AudioSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

enum class AudioChannel { Mic, Usb, Network }

data class ChannelState(
    val gain: Float = 1f,
    val muted: Boolean = false,
    val level: Float = 0f,
    val active: Boolean = false,
)

data class InputDevice(val id: Int, val name: String, val type: Int) {
    val isUsb: Boolean get() = type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
}

data class MixerState(
    val channels: Map<AudioChannel, ChannelState> = AudioChannel.entries.associateWith { ChannelState() },
    val masterMuted: Boolean = false,
    val micDeviceId: Int = 0,
    val usbAudioEnabled: Boolean = true,
    val inputDevices: List<InputDevice> = emptyList(),
    val micError: Boolean = false,
)

/** Penerima PCM hasil mix (untuk speech-to-text). */
fun interface PcmListener {
    fun onPcm(samples: ShortArray, length: Int, sampleRate: Int, channels: Int)
}

/**
 * Mixer audio sederhana sekaligus AudioSource untuk RootEncoder.
 *
 * - Mic (internal / headset / USB) dibaca langsung dan menjadi "jam" (20 ms per frame).
 * - Audio capture card USB dibaca di thread terpisah ke ring buffer.
 * - Audio stream jaringan didorong dari ExoPlayer lewat [pushNetworkPcm].
 * Setiap kanal punya gain, mute, dan meter level. Hasil mix dikirim ke encoder
 * dan ke [PcmListener] (subtitle live memakai audio yang benar-benar distream).
 */
@Singleton
class AudioMixer @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioSource() {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _state = MutableStateFlow(MixerState())
    val state: StateFlow<MixerState> = _state.asStateFlow()

    private val pcmListeners = CopyOnWriteArrayList<PcmListener>()

    @Volatile private var encoderCallback: GetMicrophoneData? = null
    @Volatile private var monitors = 0
    @Volatile private var loopRunning = false
    private var loopThread: Thread? = null
    private var usbThread: Thread? = null

    // Format kerja mixer (diset lewat create()).
    private var workRate = 48_000
    private var workChannels = 2

    private val usbRing = PcmRing(48_000 * 2)
    private val netRing = PcmRing(48_000 * 2)

    init {
        refreshDevices()
    }

    // ---------------------------------------------------------------- AudioSource

    override fun create(sampleRate: Int, isStereo: Boolean, echoCanceler: Boolean, noiseSuppressor: Boolean): Boolean {
        val wasRunning = loopRunning
        if (wasRunning) stopLoop()
        workRate = sampleRate
        workChannels = if (isStereo) 2 else 1
        if (wasRunning) startLoop()
        return true
    }

    override fun start(getMicrophoneData: GetMicrophoneData) {
        this.getMicrophoneData = getMicrophoneData
        encoderCallback = getMicrophoneData
        ensureLoop()
    }

    override fun stop() {
        encoderCallback = null
        this.getMicrophoneData = null
        ensureLoop()
    }

    override fun isRunning(): Boolean = encoderCallback != null && loopRunning

    override fun release() {
        // Mixer dipakai ulang sepanjang umur aplikasi.
    }

    // ---------------------------------------------------------------- Kontrol

    /** Menyalakan mixer tanpa stream (untuk meter VU & subtitle). Pasangkan dengan [releaseMonitor]. */
    @Synchronized
    fun acquireMonitor() {
        monitors++
        ensureLoop()
    }

    @Synchronized
    fun releaseMonitor() {
        monitors = max(0, monitors - 1)
        ensureLoop()
    }

    fun addPcmListener(listener: PcmListener) = pcmListeners.add(listener)
    fun removePcmListener(listener: PcmListener) = pcmListeners.remove(listener)

    fun setGain(channel: AudioChannel, gain: Float) = updateChannel(channel) { it.copy(gain = gain.coerceIn(0f, 2f)) }
    fun setMuted(channel: AudioChannel, muted: Boolean) = updateChannel(channel) { it.copy(muted = muted) }
    fun setMasterMuted(muted: Boolean) = _state.update { it.copy(masterMuted = muted) }

    fun setUsbAudioEnabled(enabled: Boolean) {
        _state.update { it.copy(usbAudioEnabled = enabled) }
        restartIfRunning()
    }

    fun selectMicDevice(id: Int) {
        _state.update { it.copy(micDeviceId = id) }
        restartIfRunning()
    }

    fun refreshDevices() {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter {
                it.type in setOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                )
            }
            .distinctBy { it.id }
            .map { InputDevice(it.id, it.productName?.toString().orEmpty().ifBlank { typeName(it.type) }, it.type) }
        _state.update { s ->
            val micStillThere = s.micDeviceId == 0 || devices.any { it.id == s.micDeviceId }
            s.copy(inputDevices = devices, micDeviceId = if (micStillThere) s.micDeviceId else 0)
        }
    }

    /** Dipanggil dari ExoPlayer (thread audio) — PCM 16-bit interleaved. */
    fun pushNetworkPcm(samples: ShortArray, length: Int, sampleRate: Int, channels: Int) {
        if (!loopRunning) return
        val converted = PcmUtils.convert(samples, length, sampleRate, channels, workRate, workChannels)
        netRing.write(converted)
        markActive(AudioChannel.Network, true)
    }

    fun clearNetworkAudio() {
        netRing.clear()
        markActive(AudioChannel.Network, false)
    }

    // ---------------------------------------------------------------- Loop

    @Synchronized
    private fun ensureLoop() {
        val shouldRun = encoderCallback != null || monitors > 0
        if (shouldRun && !loopRunning) startLoop()
        if (!shouldRun && loopRunning) stopLoop()
    }

    @Synchronized
    private fun restartIfRunning() {
        if (loopRunning) {
            stopLoop()
            startLoop()
        }
    }

    private fun startLoop() {
        loopRunning = true
        usbRing.clear()
        netRing.clear()
        loopThread = Thread({ mixLoop() }, "YukLive-AudioMixer").apply { start() }
        startUsbReader()
    }

    private fun stopLoop() {
        loopRunning = false
        loopThread?.join(500)
        loopThread = null
        usbThread?.join(500)
        usbThread = null
        AudioChannel.entries.forEach { markActive(it, false) }
    }

    private fun usbInputDevice(): AudioDeviceInfo? {
        val s = _state.value
        if (!s.usbAudioEnabled) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            (it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET) &&
                it.id != s.micDeviceId
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecord(device: AudioDeviceInfo?): AudioRecord? {
        val channelMask = if (workChannels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(workRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return null
        return runCatching {
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                workRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf * 4, frameSamples() * 2 * 4),
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release(); null
            } else {
                device?.let { record.setPreferredDevice(it) }
                record
            }
        }.getOrNull()
    }

    private fun frameSamples(): Int = workRate / 50 * workChannels // 20 ms

    private fun startUsbReader() {
        val device = usbInputDevice() ?: return
        usbThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val record = createRecord(device) ?: return@Thread
            val buf = ShortArray(frameSamples())
            runCatching { record.startRecording() }
            markActive(AudioChannel.Usb, true)
            while (loopRunning) {
                val n = record.read(buf, 0, buf.size)
                if (n > 0) usbRing.write(buf, n) else if (n < 0) break
            }
            runCatching { record.stop() }
            record.release()
            markActive(AudioChannel.Usb, false)
        }, "YukLive-UsbAudio").apply { start() }
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val samples = frameSamples()
        val micBuf = ShortArray(samples)
        val usbBuf = ShortArray(samples)
        val netBuf = ShortArray(samples)
        val out = ShortArray(samples)

        val micDevice = _state.value.micDeviceId.takeIf { it != 0 }?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == id }
        }
        val mic = createRecord(micDevice)
        if (mic != null) runCatching { mic.startRecording() }
        _state.update { it.copy(micError = mic == null) }
        markActive(AudioChannel.Mic, mic != null)

        var levelCounter = 0
        val peaks = FloatArray(AudioChannel.entries.size)
        var nextTick = SystemClock.elapsedRealtime()

        while (loopRunning) {
            // 1. Mic menjadi jam. Kalau mic gagal, pakai timer 20 ms.
            if (mic != null) {
                var read = 0
                while (read < samples && loopRunning) {
                    val n = mic.read(micBuf, read, samples - read)
                    if (n <= 0) break
                    read += n
                }
                for (i in read until samples) micBuf[i] = 0
            } else {
                nextTick += 20
                val sleep = nextTick - SystemClock.elapsedRealtime()
                if (sleep > 0) Thread.sleep(sleep)
                micBuf.fill(0)
            }
            usbRing.read(usbBuf, samples)
            netRing.read(netBuf, samples)

            // 2. Mix
            val s = _state.value
            val gMic = s.gainOf(AudioChannel.Mic)
            val gUsb = s.gainOf(AudioChannel.Usb)
            val gNet = s.gainOf(AudioChannel.Network)
            for (i in 0 until samples) {
                val v = micBuf[i] * gMic + usbBuf[i] * gUsb + netBuf[i] * gNet
                out[i] = PcmUtils.clamp(v.toInt())
            }
            if (s.masterMuted) out.fill(0)

            peaks[0] = max(peaks[0], PcmUtils.peak(micBuf) * gMic)
            peaks[1] = max(peaks[1], PcmUtils.peak(usbBuf) * gUsb)
            peaks[2] = max(peaks[2], PcmUtils.peak(netBuf) * gNet)

            // 3. Kirim ke encoder & pendengar
            encoderCallback?.inputPCMData(
                Frame(PcmUtils.toBytes(out), 0, samples * 2, TimeUtils.getCurrentTimeMicro()),
            )
            for (l in pcmListeners) l.onPcm(out, samples, workRate, workChannels)

            // 4. Meter VU ~10x per detik
            if (++levelCounter >= 5) {
                levelCounter = 0
                val p = peaks.copyOf()
                peaks.fill(0f)
                _state.update { st ->
                    st.copy(channels = st.channels.mapValues { (ch, cs) -> cs.copy(level = p[ch.ordinal].coerceIn(0f, 1f)) })
                }
            }
        }

        mic?.let {
            runCatching { it.stop() }
            it.release()
        }
        _state.update { st -> st.copy(channels = st.channels.mapValues { it.value.copy(level = 0f) }) }
    }

    // ---------------------------------------------------------------- Helper

    private fun MixerState.gainOf(ch: AudioChannel): Float {
        val c = channels[ch] ?: return 0f
        return if (c.muted) 0f else c.gain
    }

    private fun updateChannel(channel: AudioChannel, transform: (ChannelState) -> ChannelState) {
        _state.update { s -> s.copy(channels = s.channels + (channel to transform(s.channels.getValue(channel)))) }
    }

    private fun markActive(channel: AudioChannel, active: Boolean) {
        if (_state.value.channels[channel]?.active == active) return
        updateChannel(channel) { it.copy(active = active) }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Mic internal"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Headset"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        else -> "Mic"
    }
}
