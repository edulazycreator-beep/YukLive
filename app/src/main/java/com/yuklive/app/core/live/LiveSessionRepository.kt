package com.yuklive.app.core.live

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class LiveSession(
    val isLive: Boolean = false,
    /** Waktu mulai (SystemClock.elapsedRealtime) supaya durasi tidak terpengaruh ganti jam HP. */
    val startedAtElapsed: Long? = null,
)

/**
 * Sumber kebenaran tunggal status siaran untuk seluruh aplikasi.
 *
 * Tahap 1: hanya status (simulasi). Mulai Tahap 2, start/stop di sini
 * akan menjalankan foreground service + encoder RootEncoder.
 */
@Singleton
class LiveSessionRepository @Inject constructor() {
    private val _state = MutableStateFlow(LiveSession())
    val state: StateFlow<LiveSession> = _state.asStateFlow()

    fun start() {
        if (_state.value.isLive) return
        _state.value = LiveSession(isLive = true, startedAtElapsed = SystemClock.elapsedRealtime())
    }

    fun stop() {
        _state.value = LiveSession()
    }
}
