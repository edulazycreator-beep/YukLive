package com.yuklive.app.video

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Range
import android.view.MotionEvent
import android.view.View
import com.pedro.encoder.input.sources.video.Camera2Source
import com.pedro.encoder.input.video.CameraCallbacks
import com.pedro.encoder.input.video.CameraHelper
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

enum class LensKind { Back, UltraWide, Tele, Front }

data class CameraLens(
    val cameraId: String,
    val physicalId: String?,
    val kind: LensKind,
    /** Perbandingan focal length terhadap kamera utama (mis. 0.6x, 1x, 3x). */
    val zoomFactor: Float,
)

enum class WhiteBalance(val awbMode: Int?) {
    Auto(CaptureRequest.CONTROL_AWB_MODE_AUTO),
    Daylight(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    Cloudy(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    Indoor(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    Fluorescent(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    Locked(null),
}

data class CameraUiState(
    val lenses: List<CameraLens> = emptyList(),
    val selected: CameraLens? = null,
    val zoom: Float = 1f,
    val zoomRange: ClosedFloatingPointRange<Float> = 1f..1f,
    val exposure: Int = 0,
    val exposureRange: IntRange = 0..0,
    val torch: Boolean = false,
    val torchAvailable: Boolean = false,
    val focusLocked: Boolean = false,
    val whiteBalance: WhiteBalance = WhiteBalance.Auto,
    val stabilization: Boolean = false,
    val isOpen: Boolean = false,
)

sealed interface CameraEvent {
    data object InUseByOtherApp : CameraEvent
    data class Error(val detail: String) : CameraEvent
}

/**
 * Membungkus Camera2Source milik RootEncoder: pemilihan lensa (depan/belakang/ultrawide/tele)
 * dan kontrol manual (zoom, fokus, exposure, white balance, stabilisasi, torch).
 */
@Singleton
class CameraController @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val source = Camera2Source(context)

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(CameraUiState())
    val state: StateFlow<CameraUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CameraEvent> = _events.asSharedFlow()

    init {
        val lenses = runCatching { discoverLenses() }.getOrDefault(emptyList())
        _state.update { it.copy(lenses = lenses, selected = lenses.firstOrNull { l -> l.kind == LensKind.Back } ?: lenses.firstOrNull()) }
        source.setCameraCallback(object : CameraCallbacks {
            override fun onCameraChanged(facing: CameraHelper.Facing) = refreshCapabilities()
            override fun onCameraOpened() {
                main.post {
                    _state.update { it.copy(isOpen = true) }
                    refreshCapabilities()
                }
            }
            override fun onCameraDisconnected() {
                _state.update { it.copy(isOpen = false) }
            }
            override fun onCameraError(error: String) {
                val inUse = error.contains("in use", true) || error.contains("ERROR_CAMERA_IN_USE", true) ||
                    error.contains("max cameras", true) || error.contains("disabled", true)
                _events.tryEmit(if (inUse) CameraEvent.InUseByOtherApp else CameraEvent.Error(error))
            }
        })
    }

    /** Dipanggil setelah kamera mulai jalan supaya lensa pilihan pengguna dipakai. */
    fun applySelectedLens() {
        val lens = _state.value.selected ?: return
        if (!source.isRunning()) return
        runCatching {
            if (source.getCurrentCameraId() != lens.cameraId) {
                source.openCameraId(lens.cameraId)
                appliedPhysical = null
            }
            if (lens.physicalId != appliedPhysical && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                source.openPhysicalCamera(lens.physicalId)
                appliedPhysical = lens.physicalId
            }
        }
    }

    /** Kamera ditutup (ganti sumber / preview berhenti): pemilihan lensa harus diterapkan ulang. */
    fun onClosed() {
        appliedPhysical = null
        _state.update { it.copy(isOpen = false, torch = false) }
    }

    private var appliedPhysical: String? = null

    fun selectLens(lens: CameraLens) {
        _state.update { it.copy(selected = lens, torch = false, focusLocked = false, whiteBalance = WhiteBalance.Auto) }
        applySelectedLens()
    }

    fun setZoom(level: Float) = runIfOpen {
        source.setZoom(level)
        _state.update { it.copy(zoom = source.getZoom()) }
    }

    /** Pinch-to-zoom dari preview. */
    fun onPinch(event: MotionEvent) = runIfOpen {
        source.setZoom(event)
        _state.update { it.copy(zoom = source.getZoom()) }
    }

    /** Zoom relatif (dari gestur cubit Compose). */
    fun zoomBy(factor: Float) = runIfOpen {
        val st = _state.value
        val next = (source.getZoom() * factor).coerceIn(st.zoomRange.start, st.zoomRange.endInclusive)
        source.setZoom(next)
        _state.update { it.copy(zoom = source.getZoom()) }
    }

    /** Fokus di titik (x, y) pada [view] preview. */
    fun focusAt(view: View, x: Float, y: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0)
        tapToFocus(view, ev)
        ev.recycle()
    }

    fun tapToFocus(view: View, event: MotionEvent) = runIfOpen {
        source.tapToFocus(view, event)
        source.tapToMeterExposure(view, event)
        _state.update { it.copy(focusLocked = false) }
    }

    fun setFocusLocked(locked: Boolean) = runIfOpen {
        if (locked) source.disableAutoFocus() else source.enableAutoFocus()
        _state.update { it.copy(focusLocked = locked) }
    }

    fun setExposure(level: Int) = runIfOpen {
        source.setExposure(level)
        _state.update { it.copy(exposure = source.getExposure()) }
    }

    fun setTorch(on: Boolean) = runIfOpen {
        if (on) source.enableLantern() else source.disableLantern()
        _state.update { it.copy(torch = source.isLanternEnabled()) }
    }

    fun setWhiteBalance(wb: WhiteBalance) = runIfOpen {
        if (wb == WhiteBalance.Locked) {
            source.enableWhiteBalanceLock()
        } else {
            source.disableWhiteBalanceLock()
            wb.awbMode?.let { source.enableAutoWhiteBalance(it) }
        }
        _state.update { it.copy(whiteBalance = wb) }
    }

    fun setStabilization(on: Boolean) = runIfOpen {
        val ok = if (on) {
            source.enableVideoStabilization() || source.enableOpticalVideoStabilization()
        } else {
            source.disableVideoStabilization()
            source.disableOpticalVideoStabilization()
            false
        }
        _state.update { it.copy(stabilization = on && ok) }
    }

    private fun refreshCapabilities() {
        runCatching {
            val range: Range<Float> = source.getZoomRange()
            val id = source.getCurrentCameraId()
            val chars = manager.getCameraCharacteristics(id)
            val ae = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            val flash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            _state.update {
                it.copy(
                    zoom = source.getZoom(),
                    zoomRange = range.lower..range.upper,
                    exposure = source.getExposure(),
                    exposureRange = if (ae != null) ae.lower..ae.upper else 0..0,
                    torchAvailable = flash,
                )
            }
        }
    }

    private inline fun runIfOpen(block: () -> Unit) {
        if (source.isRunning()) runCatching(block)
    }

    // ---------------------------------------------------------------- Deteksi lensa

    private fun discoverLenses(): List<CameraLens> {
        data class Raw(val id: String, val physical: String?, val facing: Int?, val focal: Float)

        val raws = mutableListOf<Raw>()
        for (id in manager.cameraIdList) {
            val chars = manager.getCameraCharacteristics(id)
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: 0f
            raws += Raw(id, null, facing, focal)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && facing == CameraCharacteristics.LENS_FACING_BACK) {
                for (pid in chars.physicalCameraIds) {
                    val pf = runCatching {
                        manager.getCameraCharacteristics(pid)
                            .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()
                    }.getOrNull() ?: continue
                    raws += Raw(id, pid, facing, pf)
                }
            }
        }

        val mainBack = raws.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_BACK && it.physical == null }
        val result = mutableListOf<CameraLens>()
        mainBack?.let { result += CameraLens(it.id, null, LensKind.Back, 1f) }

        val mainFocal = mainBack?.focal?.takeIf { it > 0f } ?: 1f
        raws.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK && it !== mainBack && it.focal > 0f }
            .forEach { r ->
                val ratio = r.focal / mainFocal
                val kind = when {
                    ratio < 0.8f -> LensKind.UltraWide
                    ratio > 1.6f -> LensKind.Tele
                    else -> null
                }
                if (kind != null && result.none { it.kind == kind }) {
                    result += CameraLens(r.id, r.physical, kind, ratio)
                }
            }

        raws.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }?.let {
            result += CameraLens(it.id, null, LensKind.Front, 1f)
        }
        return result.sortedBy { it.kind.ordinal }
    }
}
