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
import android.speech.SpeechRecognizer
import com.yuklive.app.audio.PcmUtils
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue

/**
 * Speech-to-text Android (on-device kalau tersedia).
 *
 * Android 13+: audio diambil dari audio yang sedang distream lewat
 * RecognizerIntent.EXTRA_AUDIO_SOURCE (pipe), bukan dari mic langsung.
 * Android 12 ke bawah: API belum mendukung sumber audio kustom, jadi recognizer
 * memakai mic HP.
 *
 * Recognizer otomatis di-restart setiap kali berhenti (keepalive).
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
    @Volatile private var pipeOut: OutputStream? = null
    private var pipeIn: ParcelFileDescriptor? = null
    private val queue = LinkedBlockingQueue<ByteArray>(100)
    private var writer: Thread? = null

    override fun start(language: String) {
        this.language = language
        active = true
        if (usesFeed && writer == null) {
            writer = Thread({ writeLoop() }, "YukLive-SttPipe").apply { start() }
        }
        main.post { startSession() }
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
        queue.offer(PcmUtils.toBytes(pcm16kMono, length))
    }

    private fun writeLoop() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = queue.take()
                val out = pipeOut ?: continue
                try {
                    out.write(chunk)
                } catch (_: IOException) {
                    // Sesi recognizer selesai & pipe ditutup; sesi berikutnya membuat pipe baru.
                }
            }
        } catch (_: InterruptedException) {
        }
    }

    private fun createRecognizer(): SpeechRecognizer? {
        if (!SpeechRecognizer.isRecognitionAvailable(context) &&
            !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context))
        ) return null
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
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
            val pipe = ParcelFileDescriptor.createPipe()
            pipeIn = pipe[0]
            pipeOut = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
        }
        runCatching { r.startListening(intent) }
            .onFailure { restart(800) }
    }

    private fun closePipe() {
        runCatching { pipeOut?.close() }
        runCatching { pipeIn?.close() }
        pipeOut = null
        pipeIn = null
    }

    /** Keepalive: mulai sesi baru setelah jeda singkat. */
    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!active) return
        main.postDelayed({
            if (recreate) {
                runCatching { recognizer?.destroy() }
                recognizer = null
            }
            startSession()
        }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onStatus(true)
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            best(partialResults)?.let { if (it.isNotBlank()) listener.onPartial(it) }
        }

        override fun onResults(results: Bundle?) {
            best(results)?.let { if (it.isNotBlank()) listener.onFinal(it) }
            restart(50)
        }

        override fun onError(error: Int) {
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
                        listener.onError("language")
                        restart(5000, recreate = true)
                    } else {
                        restart(1000, recreate = true)
                    }
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
}
