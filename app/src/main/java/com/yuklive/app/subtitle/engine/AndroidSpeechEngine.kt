package com.yuklive.app.subtitle.engine

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.yuklive.app.audio.PcmUtils
import java.util.concurrent.LinkedBlockingQueue

/**
 * Speech-to-text Android (on-device kalau tersedia).
 *
 * Android 13+: audio diambil dari audio yang sedang distream lewat
 * RecognizerIntent.EXTRA_AUDIO_SOURCE (pipe), bukan dari mic langsung.
 * Android 12 ke bawah: API belum mendukung sumber audio kustom, jadi recognizer
 * memakai mic HP.
 *
 * Recognizer otomatis di-restart setiap kali berhenti (keepalive), dan ada
 * watchdog yang membuat ulang recognizer kalau macet tanpa kabar.
 *
 * Pipe ditulis non-blocking: kalau recognizer sedang tidak membaca (di sela
 * sesi), audio dibuang alih-alih membuat thread penulis tersangkut selamanya.
 */
class AndroidSpeechEngine(
    private val context: Context,
    private val listener: TranscriptionListener,
) : TranscriptionEngine {

    override val usesFeed: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var language = "id-ID"
    @Volatile private var active = false

    // Pipe audio (Android 13+)
    @Volatile private var pipeOut: ParcelFileDescriptor? = null
    private var pipeIn: ParcelFileDescriptor? = null
    private val queue = LinkedBlockingQueue<ByteArray>(50) // ±1 detik
    private var writer: Thread? = null

    // Keepalive
    private var preferOnDevice = true
    @Volatile private var lastEventAt = 0L
    private var failures = 0

    override fun start(language: String) {
        this.language = language
        active = true
        if (usesFeed && writer == null) {
            writer = Thread({ writeLoop() }, "YukLive-SttPipe").apply { start() }
        }
        main.post { startSession() }
        main.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        main.post {
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
            closePipe()
            listener.onStatus(false)
        }
        writer?.interrupt()
        writer = null
        queue.clear()
    }

    override fun feed(pcm16kMono: ShortArray, length: Int) {
        if (!usesFeed || !active || pipeOut == null) return
        val chunk = PcmUtils.toBytes(pcm16kMono, length)
        if (!queue.offer(chunk)) {
            queue.poll() // buang audio paling lama, simpan yang terbaru
            queue.offer(chunk)
        }
    }

    private fun writeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = queue.take()
                val out = pipeOut ?: continue
                var off = 0
                while (off < chunk.size) {
                    val n = try {
                        Os.write(out.fileDescriptor, chunk, off, chunk.size - off)
                    } catch (e: ErrnoException) {
                        // EAGAIN: recognizer belum membaca → buang. EPIPE/EBADF: sesi selesai.
                        -1
                    } catch (_: Exception) {
                        -1
                    }
                    if (n <= 0) break
                    off += n
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    /** Watchdog: kalau tidak ada kabar dari recognizer terlalu lama, buat ulang. */
    private val watchdog: Runnable = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - lastEventAt > STALL_MS) {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
                recognizer = null
                startSession()
            }
            main.postDelayed(this, WATCHDOG_MS)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        ) return null
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }
        r.setRecognitionListener(recognitionListener)
        return r
    }

    private fun startSession() {
        if (!active) return
        lastEventAt = SystemClock.elapsedRealtime()
        val r = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (r == null) {
            listener.onError("unavailable")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }
        if (usesFeed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            closePipe()
            queue.clear() // jangan kirim audio basi ke sesi baru
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching {
                val fd = pipe[1].fileDescriptor
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags or OsConstants.O_NONBLOCK)
            }
            pipeIn = pipe[0]
            pipeOut = pipe[1]
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        runCatching { r.startListening(intent) }
            .onFailure { restart(800, recreate = true) }
    }

    private fun closePipe() {
        val out = pipeOut
        pipeOut = null
        runCatching { out?.close() }
        runCatching { pipeIn?.close() }
        pipeIn = null
    }

    /** Keepalive: mulai sesi baru setelah jeda singkat. */
    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!active) return
        lastEventAt = SystemClock.elapsedRealtime()
        main.postDelayed({
            if (recreate) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            startSession()
        }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            touch()
            failures = 0
            listener.onStatus(true)
        }
        override fun onBeginningOfSpeech() = touch()
        override fun onRmsChanged(rmsdB: Float) = touch()
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            touch()
            best(partialResults)?.let { if (it.isNotBlank()) listener.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            best(results)?.let { if (it.isNotBlank()) listener.onFinal(it) }
            restart(50)
        }

        override fun onError(error: Int) {
            touch()
            failures++
            // Terus gagal: buat ulang recognizer & beri jeda makin panjang.
            if (failures >= 5 && error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                restart((failures * 400L).coerceAtMost(4000), recreate = true)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(50)
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> restart(600, recreate = true)
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> listener.onError("permission")
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    listener.onError("network")
                    restart(2000, recreate = true)
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
                    ) {
                        if (preferOnDevice) {
                            // Mesin on-device belum punya bahasa ini → coba recognizer biasa.
                            preferOnDevice = false
                            restart(300, recreate = true)
                        } else {
                            listener.onError("language")
                            restart(5000, recreate = true)
                        }
                    } else {
                        restart(1000, recreate = true)
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun touch() {
        lastEventAt = SystemClock.elapsedRealtime()
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val WATCHDOG_MS = 3_000L
        const val STALL_MS = 12_000L
    }
}
