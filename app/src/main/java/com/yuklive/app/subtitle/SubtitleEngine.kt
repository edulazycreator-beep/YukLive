package com.yuklive.app.subtitle

import android.content.Context
import android.os.SystemClock
import com.yuklive.app.audio.AudioMixer
import com.yuklive.app.audio.PcmListener
import com.yuklive.app.audio.PcmUtils
import com.yuklive.app.subtitle.engine.AndroidSpeechEngine
import com.yuklive.app.subtitle.engine.TranscriptionEngine
import com.yuklive.app.subtitle.engine.TranscriptionListener
import com.yuklive.app.subtitle.engine.VoskEngine
import com.yuklive.app.subtitle.engine.VoskModelManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Otak subtitle live:
 * - menyalakan mesin STT (Android / Vosk) dengan audio stream dari mixer,
 * - menerapkan kamus koreksi "salah → benar",
 * - memotong teks jadi potongan N kata × M baris (penuh = potongan baru),
 * - menandai kata baru (untuk animasi), auto-clear setelah diam X detik,
 * - mencatat transkrip ber-timestamp untuk ekspor .srt / .txt.
 */
@Singleton
class SubtitleEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: SubtitleRepository,
    private val mixer: AudioMixer,
    private val voskModels: VoskModelManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var config = SubtitleConfig()
    /** Posisi sementara saat subtitle sedang digeser di Studio (sebelum disimpan). */
    @Volatile private var offsetOverride: Pair<Float, Float>? = null
    private val lock = Any()

    // ---- Tampilan
    private val display = ArrayList<DisplayWord>()
    private var utteranceStartIndex = 0 // posisi kata pertama ucapan aktif di [display]
    private var utteranceSkip = 0       // jumlah kata awal ucapan yang sudah "tergulung"
    private var skippedWords: List<String> = emptyList() // isi kata yang tergulung (untuk deteksi revisi)
    private var utteranceWords: List<String> = emptyList() // kata ucapan aktif terakhir
    private var lastSpeechAt = 0L
    private var version = 0L

    // ---- Transkrip
    private val transcript = ArrayList<TranscriptEntry>()
    private var sessionStart = SystemClock.elapsedRealtime()
    private var utteranceStartedAt: Long? = null

    private val _recognizer = MutableStateFlow(RecognizerState())
    val recognizer: StateFlow<RecognizerState> = _recognizer.asStateFlow()

    private val _transcriptCount = MutableStateFlow(0)
    val transcriptCount: StateFlow<Int> = _transcriptCount.asStateFlow()

    private var engine: TranscriptionEngine? = null
    private var monitoring = false
    private var testJob: kotlinx.coroutines.Job? = null

    private val pcmListener = PcmListener { samples, length, rate, channels ->
        val e = engine ?: return@PcmListener
        if (!e.usesFeed) return@PcmListener
        val pcm = PcmUtils.convert(samples, length, rate, channels, 16_000, 1)
        e.feed(pcm, pcm.size)
    }

    init {
        scope.launch {
            var lastKey: Any? = null
            repository.config.collect { c ->
                config = c
                offsetOverride?.let { o -> if (o.first == c.style.offsetX && o.second == c.style.offsetY) offsetOverride = null }
                synchronized(lock) { version++ }
                val key = listOf(c.enabled, c.engine, c.language, c.voskModel)
                if (key != lastKey) {
                    lastKey = key
                    if (c.enabled) startRecognition() else stopRecognition()
                }
            }
        }
        // Auto-clear setelah diam.
        scope.launch {
            while (isActive) {
                delay(100)
                synchronized(lock) {
                    val clearMs = (config.style.clearAfterSec * 1000).toLong()
                    if (display.isNotEmpty() && SystemClock.elapsedRealtime() - lastSpeechAt > clearMs) {
                        clearDisplayLocked()
                    }
                }
            }
        }
    }

    /** Snapshot untuk compositor (dipanggil dari thread lain). */
    fun frame(): SubtitleFrame = synchronized(lock) {
        SubtitleFrame(
            words = display.toList(),
            style = effectiveStyle(),
            visible = (config.enabled || testJob?.isActive == true) && display.isNotEmpty(),
            version = version,
        )
    }

    fun effectiveStyle(): SubtitleStyle =
        offsetOverride?.let { config.style.copy(offsetX = it.first, offsetY = it.second) } ?: config.style

    /** Geser subtitle (fraksi layar); tampil langsung, disimpan lewat [commitOffset]. */
    fun moveBy(dx: Float, dy: Float) {
        val st = effectiveStyle()
        offsetOverride = (st.offsetX + dx).coerceIn(-0.9f, 0.9f) to (st.offsetY + dy).coerceIn(-0.9f, 0.9f)
        synchronized(lock) { version++ }
    }

    fun commitOffset() {
        val o = offsetOverride ?: return
        scope.launch { repository.update { it.copy(style = it.style.copy(offsetX = o.first, offsetY = o.second)) } }
    }

    fun resetOffset() {
        offsetOverride = 0f to 0f
        synchronized(lock) { version++ }
        commitOffset()
    }

    /** Snapshot untuk preview di layar Subtitle (selalu terlihat). */
    fun previewFrame(): SubtitleFrame = frame().copy(visible = true)

    fun setEnabled(enabled: Boolean) {
        scope.launch { repository.update { it.copy(enabled = enabled) } }
    }

    /** Mulai sesi transkrip baru (dipanggil saat GO LIVE). */
    fun resetTranscript() = synchronized(lock) {
        transcript.clear()
        sessionStart = SystemClock.elapsedRealtime()
        _transcriptCount.value = 0
    }

    fun transcriptSnapshot(): List<TranscriptEntry> = synchronized(lock) { transcript.toList() }

    /** "Test Teks": tampilkan teks kata demi kata memakai gaya saat ini. */
    fun testText(text: String) {
        testJob?.cancel()
        testJob = scope.launch {
            val words = applyCorrections(text).split(Regex("\\s+")).filter { it.isNotBlank() }
            synchronized(lock) { clearDisplayLocked() }
            for (i in words.indices) {
                onUpdate(words.subList(0, i + 1).joinToString(" "), final = false, record = false)
                delay(280)
            }
            onUpdate(words.joinToString(" "), final = true, record = false)
            delay((config.style.clearAfterSec * 1000).toLong() + 300)
        }
    }

    // ---------------------------------------------------------------- Mesin STT

    private fun startRecognition() {
        stopRecognition()
        val c = config
        val listener = object : TranscriptionListener {
            override fun onPartial(text: String) = onUpdate(text, final = false, record = true)
            override fun onFinal(text: String) = onUpdate(text, final = true, record = true)
            override fun onStatus(listening: Boolean) {
                _recognizer.update { it.copy(status = if (listening) RecognizerStatus.Listening else it.status) }
            }
            override fun onError(code: String) {
                _recognizer.update { it.copy(status = RecognizerStatus.Error, errorCode = code) }
            }
        }
        val e: TranscriptionEngine = when (c.engine) {
            SpeechEngineType.Android -> AndroidSpeechEngine(context, listener)
            SpeechEngineType.Vosk -> {
                val name = c.voskModel
                if (name == null || !voskModels.isInstalled(name)) {
                    _recognizer.value = RecognizerState(RecognizerStatus.Error, errorCode = "model")
                    return
                }
                VoskEngine(voskModels.modelDir(name), listener)
            }
        }
        engine = e
        _recognizer.value = RecognizerState(RecognizerStatus.Starting)
        if (e.usesFeed) {
            mixer.addPcmListener(pcmListener)
            mixer.acquireMonitor()
            monitoring = true
        }
        e.start(c.language)
    }

    private fun stopRecognition() {
        engine?.stop()
        engine = null
        mixer.removePcmListener(pcmListener)
        if (monitoring) {
            mixer.releaseMonitor()
            monitoring = false
        }
        _recognizer.value = RecognizerState(RecognizerStatus.Off)
    }

    // ---------------------------------------------------------------- Potongan & animasi

    private fun applyCorrections(text: String): String {
        var out = text
        for (c in config.corrections) {
            if (c.wrong.isBlank()) continue
            out = out.replace(Regex("(?i)\\b${Regex.escape(c.wrong.trim())}\\b"), c.right)
        }
        return out
    }

    private fun onUpdate(rawText: String, final: Boolean, record: Boolean) {
        val text = applyCorrections(rawText).trim()
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (utteranceStartedAt == null && words.isNotEmpty()) utteranceStartedAt = now
            val capacity = config.style.capacity.coerceAtLeast(1)
            var changed = false

            // Recognizer Android sering MEREVISI kata yang sudah lewat (mis. "satu dua tiga" → "123"),
            // sehingga jumlah kata bisa menyusut di bawah batas yang sudah tergulung dan subtitle
            // tidak muncul lagi sampai ucapan selesai. Kalau bagian tergulung berubah, potong batasnya
            // ke awalan yang masih sama supaya teks revisi tetap tampil.
            if (utteranceSkip > 0) {
                var common = 0
                while (common < words.size && common < skippedWords.size && words[common] == skippedWords[common]) common++
                if (common < utteranceSkip) {
                    utteranceSkip = common
                    skippedWords = skippedWords.take(common)
                    while (display.size > utteranceStartIndex) display.removeAt(display.lastIndex)
                    changed = true
                }
            }
            utteranceWords = words

            for (i in words.indices) {
                if (i < utteranceSkip) continue
                var idx = utteranceStartIndex + (i - utteranceSkip)
                if (idx >= capacity) {
                    // Potongan penuh: mulai potongan baru dari kata ini.
                    display.clear()
                    utteranceStartIndex = 0
                    utteranceSkip = i
                    skippedWords = words.take(i)
                    idx = 0
                }
                if (idx < display.size) {
                    if (display[idx].text != words[i]) {
                        display[idx] = display[idx].copy(text = words[i]) // revisi: tanpa animasi ulang
                        changed = true
                    }
                } else {
                    display += DisplayWord(words[i], now)
                    changed = true
                }
            }
            // Recognizer merevisi jadi lebih pendek.
            val expected = utteranceStartIndex + (words.size - utteranceSkip).coerceAtLeast(0)
            while (display.size > expected && display.size > utteranceStartIndex) {
                display.removeAt(display.lastIndex)
                changed = true
            }
            if (words.isNotEmpty()) lastSpeechAt = now
            if (changed) version++
            _recognizer.update { it.copy(partial = if (final) "" else text) }

            if (final) {
                if (record && text.isNotEmpty()) {
                    val start = (utteranceStartedAt ?: now) - sessionStart
                    transcript += TranscriptEntry(start.coerceAtLeast(0), now - sessionStart, text)
                    _transcriptCount.value = transcript.size
                }
                utteranceStartedAt = null
                utteranceStartIndex = display.size
                utteranceSkip = 0
                skippedWords = emptyList()
                utteranceWords = emptyList()
            }
        }
    }

    private fun clearDisplayLocked() {
        if (display.isEmpty()) return
        // Kalau ucapan masih berjalan, kata yang sudah tampil jangan muncul lagi.
        utteranceSkip += (display.size - utteranceStartIndex).coerceAtLeast(0)
        skippedWords = utteranceWords.take(utteranceSkip)
        utteranceStartIndex = 0
        display.clear()
        version++
    }

    // ---------------------------------------------------------------- Ekspor

    fun exportSrt(): String = buildString {
        transcriptSnapshot().forEachIndexed { i, e ->
            append(i + 1).append('\n')
            append(srtTime(e.startMs)).append(" --> ").append(srtTime(maxOf(e.endMs, e.startMs + 500))).append('\n')
            append(e.text).append("\n\n")
        }
    }

    fun exportTxt(): String = buildString {
        transcriptSnapshot().forEach { e -> append('[').append(hms(e.startMs)).append("] ").append(e.text).append('\n') }
    }

    private fun srtTime(ms: Long): String =
        String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)

    private fun hms(ms: Long): String =
        String.format(Locale.US, "%02d:%02d:%02d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60)
}
