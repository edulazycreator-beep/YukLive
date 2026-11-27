package com.yuklive.app.subtitle.engine

/** Mesin speech-to-text. Audio masuk lewat [feed] (PCM 16-bit mono 16 kHz). */
interface TranscriptionEngine {
    /** true = mesin mendengar audio dari [feed]; false = mesin memakai mic sendiri. */
    val usesFeed: Boolean

    fun start(language: String)
    fun stop()
    fun feed(pcm16kMono: ShortArray, length: Int)
}

interface TranscriptionListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onStatus(listening: Boolean)
    fun onError(code: String)
}
