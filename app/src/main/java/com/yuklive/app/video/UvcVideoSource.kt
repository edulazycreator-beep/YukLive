package com.yuklive.app.video

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.herohan.uvcapp.CameraHelper
import com.herohan.uvcapp.ICameraHelper
import com.pedro.encoder.input.sources.OrientationConfig
import com.pedro.encoder.input.sources.OrientationForced
import com.pedro.encoder.input.sources.video.VideoSource
import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class UvcFormat(val width: Int, val height: Int, val fps: Int, val mjpeg: Boolean) {
    val label: String get() = "${width}×$height · ${if (mjpeg) "MJPEG" else "YUYV"} · ${fps}fps"
}

enum class UvcStatus { NoOtg, Idle, WaitingPermission, Opening, Ready, Error }

data class UvcState(
    val status: UvcStatus = UvcStatus.Idle,
    val deviceName: String? = null,
    val formats: List<UvcFormat> = emptyList(),
    val selected: UvcFormat? = null,
)

sealed interface UvcEvent {
    data object Detached : UvcEvent
    data object Attached : UvcEvent
    data class Error(val detail: String) : UvcEvent
}

/**
 * Sumber video capture card USB (UVC) lewat USB OTG, memakai UVCAndroid (libuvc).
 * - Deteksi otomatis saat dicolok + minta izin USB.
 * - Pilih resolusi/format (MJPEG / YUYV).
 * - Memberi tahu engine saat dicabut supaya bisa fallback ke kamera HP.
 */
@Singleton
class UvcVideoSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : VideoSource() {

    private val main = Handler(Looper.getMainLooper())
    private var helper: ICameraHelper? = null
    private var surface: Surface? = null
    private var running = false
    private var cameraOpen = false
    private var rawSizes: List<Size> = emptyList()

    private val _state = MutableStateFlow(
        UvcState(status = if (hasOtg()) UvcStatus.Idle else UvcStatus.NoOtg),
    )
    val state: StateFlow<UvcState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<UvcEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<UvcEvent> = _events.asSharedFlow()

    fun hasOtg(): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)

    /** Mulai memantau colokan USB. Aman dipanggil berkali-kali. Harus dari main thread. */
    fun startMonitoring() {
        if (!hasOtg() || helper != null) return
        helper = runCatching { CameraHelper().also { it.setStateCallback(callback) } }
            .onFailure { _state.update { s -> s.copy(status = UvcStatus.Error) } }
            .getOrNull()
        // Perangkat yang sudah tercolok sebelum aplikasi dibuka.
        helper?.deviceList?.firstOrNull()?.let { callback.onAttach(it) }
    }

    fun selectFormat(format: UvcFormat) {
        val size = rawSizes.firstOrNull { it.width == format.width && it.height == format.height && isMjpeg(it) == format.mjpeg }
            ?: return
        _state.update { it.copy(selected = format) }
        val h = helper ?: return
        if (!cameraOpen) return
        runCatching {
            h.stopPreview()
            h.setPreviewSize(size)
            h.startPreview()
        }.onFailure { _events.tryEmit(UvcEvent.Error(it.message.orEmpty())) }
    }

    val isReady: Boolean get() = _state.value.status == UvcStatus.Ready

    // ---------------------------------------------------------------- VideoSource

    override fun create(width: Int, height: Int, fps: Int, rotation: Int): Boolean = true

    override fun start(surfaceTexture: SurfaceTexture) {
        this.surfaceTexture = surfaceTexture
        if (running) return
        surfaceTexture.setDefaultBufferSize(width, height)
        surface = Surface(surfaceTexture)
        running = true
        main.post { attachSurfaceIfReady() }
    }

    override fun stop() {
        running = false
        val s = surface ?: return
        main.post {
            runCatching { helper?.removeSurface(s) }
            s.release()
        }
        surface = null
    }

    override fun release() {
        // Sumber dipakai ulang; tidak dilepas saat ganti sumber.
    }

    override fun isRunning(): Boolean = running

    override fun getOrientationConfig() = OrientationConfig(forced = OrientationForced.LANDSCAPE)

    // ---------------------------------------------------------------- Callback UVC

    private fun attachSurfaceIfReady() {
        val s = surface ?: return
        if (cameraOpen) runCatching { helper?.addSurface(s, false) }
    }

    private val callback = object : ICameraHelper.StateCallback {
        override fun onAttach(device: UsbDevice) {
            _state.update { it.copy(status = UvcStatus.WaitingPermission, deviceName = device.productName ?: device.deviceName) }
            _events.tryEmit(UvcEvent.Attached)
            helper?.selectDevice(device) // memicu dialog izin USB
        }

        override fun onDeviceOpen(device: UsbDevice, isFirstOpen: Boolean) {
            _state.update { it.copy(status = UvcStatus.Opening) }
            runCatching { helper?.openCamera() }
                .onFailure { _state.update { s -> s.copy(status = UvcStatus.Error) } }
        }

        override fun onCameraOpen(device: UsbDevice) {
            cameraOpen = true
            val h = helper ?: return
            rawSizes = runCatching { h.supportedSizeList }.getOrNull().orEmpty()
            val formats = rawSizes
                .map { UvcFormat(it.width, it.height, it.fps.coerceAtLeast(1), isMjpeg(it)) }
                .distinct()
                .sortedWith(compareByDescending<UvcFormat> { it.width * it.height }.thenByDescending { it.mjpeg })
            val preferred = _state.value.selected?.takeIf { it in formats }
                ?: formats.firstOrNull { it.width == 1920 && it.height == 1080 && it.mjpeg }
                ?: formats.firstOrNull { it.mjpeg }
                ?: formats.firstOrNull()
            preferred?.let { p ->
                rawSizes.firstOrNull { it.width == p.width && it.height == p.height && isMjpeg(it) == p.mjpeg }
                    ?.let { runCatching { h.setPreviewSize(it) } }
            }
            runCatching { h.startPreview() }
            _state.update { it.copy(status = UvcStatus.Ready, formats = formats, selected = preferred) }
            attachSurfaceIfReady()
        }

        override fun onCameraClose(device: UsbDevice) {
            cameraOpen = false
        }

        override fun onDeviceClose(device: UsbDevice) {
            cameraOpen = false
        }

        override fun onDetach(device: UsbDevice) {
            cameraOpen = false
            rawSizes = emptyList()
            _state.update { UvcState(status = UvcStatus.Idle) }
            _events.tryEmit(UvcEvent.Detached)
        }

        override fun onCancel(device: UsbDevice) {
            _state.update { it.copy(status = UvcStatus.Error) }
            _events.tryEmit(UvcEvent.Error("permission"))
        }
    }

    private fun isMjpeg(size: Size): Boolean =
        size.type == UVCCamera.UVC_VS_FRAME_MJPEG || size.type == UVCCamera.UVC_VS_FORMAT_MJPEG
}
