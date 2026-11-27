package com.yuklive.app.subtitle.engine

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Speech-to-text offline dengan Vosk. Model diunduh lewat [VoskModelManager].
 * Audio = audio stream (hasil mixer), 16 kHz mono.
 */
class VoskEngine(
    private val modelDir: File,
    private val listener: TranscriptionListener,
) : TranscriptionEngine {

    override val usesFeed: Boolean = true

    private val queue = LinkedBlockingQueue<ShortArray>(200)
    @Volatile private var worker: Thread? = null

    override fun start(language: String) {
        if (worker != null) return
        worker = Thread({ run() }, "YukLive-Vosk").apply { start() }
    }

    override fun stop() {
        worker?.interrupt()
        worker = null
        queue.clear()
        listener.onStatus(false)
    }

    override fun feed(pcm16kMono: ShortArray, length: Int) {
        if (worker == null) return
        queue.offer(pcm16kMono.copyOf(length))
    }

    private fun run() {
        val model = runCatching { Model(modelDir.absolutePath) }.getOrElse {
            listener.onError("model")
            worker = null
            return
        }
        val recognizer = runCatching { Recognizer(model, 16_000f) }.getOrElse {
            model.close()
            listener.onError("model")
            worker = null
            return
        }
        listener.onStatus(true)
        var lastPartial = ""
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = queue.take()
                val bytes = com.yuklive.app.audio.PcmUtils.toBytes(chunk)
                if (recognizer.acceptWaveForm(bytes, bytes.size)) {
                    val text = JSONObject(recognizer.result).optString("text")
                    if (text.isNotBlank()) listener.onFinal(text)
                    lastPartial = ""
                } else {
                    val partial = JSONObject(recognizer.partialResult).optString("partial")
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        listener.onPartial(partial)
                    }
                }
            }
        } catch (_: InterruptedException) {
        } finally {
            recognizer.close()
            model.close()
        }
    }
}
