package com.yuklive.app.feature.sources

import android.view.Surface
import androidx.lifecycle.ViewModel
import com.yuklive.app.audio.AudioChannel
import com.yuklive.app.audio.AudioMixer
import com.yuklive.app.audio.MixerState
import com.yuklive.app.core.device.NetworkUtils
import com.yuklive.app.video.CameraController
import com.yuklive.app.video.CameraLens
import com.yuklive.app.video.CameraUiState
import com.yuklive.app.video.UvcFormat
import com.yuklive.app.video.UvcState
import com.yuklive.app.video.UvcVideoSource
import com.yuklive.app.video.network.NetworkInputConfig
import com.yuklive.app.video.network.NetworkInputState
import com.yuklive.app.video.network.NetworkVideoSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val camera: CameraController,
    private val uvc: UvcVideoSource,
    private val network: NetworkVideoSource,
    private val mixer: AudioMixer,
) : ViewModel() {

    val cameraState: StateFlow<CameraUiState> = camera.state
    val uvcState: StateFlow<UvcState> = uvc.state
    val networkState: StateFlow<NetworkInputState> = network.state
    val mixerState: StateFlow<MixerState> = mixer.state

    val localIp: String? = NetworkUtils.localIpv4()

    init {
        uvc.startMonitoring()
        mixer.refreshDevices()
        mixer.acquireMonitor()
    }

    override fun onCleared() {
        mixer.releaseMonitor()
        network.stopTest()
    }

    fun selectLens(lens: CameraLens) = camera.selectLens(lens)
    fun selectUvcFormat(format: UvcFormat) = uvc.selectFormat(format)
    fun setUsbAudio(enabled: Boolean) = mixer.setUsbAudioEnabled(enabled)

    fun saveNetworkConfig(config: NetworkInputConfig) = network.setConfig(config)
    fun startNetworkTest(surface: Surface?) = network.startTest(surface)
    fun stopNetworkTest() = network.stopTest()

    fun selectMic(id: Int) = mixer.selectMicDevice(id)
    fun setGain(ch: AudioChannel, g: Float) = mixer.setGain(ch, g)
    fun setMuted(ch: AudioChannel, m: Boolean) = mixer.setMuted(ch, m)
    fun refreshDevices() = mixer.refreshDevices()
}
