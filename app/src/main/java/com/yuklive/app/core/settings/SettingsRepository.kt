package com.yuklive.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "stream_settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val Resolution = stringPreferencesKey("resolution")
        val Orientation = stringPreferencesKey("orientation")
        val Fps = intPreferencesKey("fps")
        val VideoBitrate = intPreferencesKey("video_bitrate")
        val Adaptive = booleanPreferencesKey("adaptive")
        val AudioBitrate = intPreferencesKey("audio_bitrate")
        val SampleRate = intPreferencesKey("sample_rate")
        val Stereo = booleanPreferencesKey("stereo")
        val Keyframe = intPreferencesKey("keyframe")
        val Codec = stringPreferencesKey("codec")
        val Record = booleanPreferencesKey("record")
        val Transition = stringPreferencesKey("transition")
    }

    val settings: Flow<StreamSettings> = context.settingsStore.data.map { it.toSettings() }

    suspend fun current(): StreamSettings = settings.first()

    suspend fun update(transform: (StreamSettings) -> StreamSettings) {
        context.settingsStore.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.Resolution] = next.resolution.name
            prefs[Keys.Orientation] = next.orientation.name
            prefs[Keys.Fps] = next.fps
            prefs[Keys.VideoBitrate] = next.videoBitrateKbps
            prefs[Keys.Adaptive] = next.adaptiveBitrate
            prefs[Keys.AudioBitrate] = next.audioBitrateKbps
            prefs[Keys.SampleRate] = next.sampleRate
            prefs[Keys.Stereo] = next.stereo
            prefs[Keys.Keyframe] = next.keyframeSec
            prefs[Keys.Codec] = next.codec.name
            prefs[Keys.Record] = next.recordLocally
            prefs[Keys.Transition] = next.transition.name
        }
    }

    private fun Preferences.toSettings(): StreamSettings {
        val d = StreamSettings()
        return StreamSettings(
            resolution = enumOr(this[Keys.Resolution], d.resolution),
            orientation = enumOr(this[Keys.Orientation], d.orientation),
            fps = this[Keys.Fps] ?: d.fps,
            videoBitrateKbps = this[Keys.VideoBitrate] ?: d.videoBitrateKbps,
            adaptiveBitrate = this[Keys.Adaptive] ?: d.adaptiveBitrate,
            audioBitrateKbps = this[Keys.AudioBitrate] ?: d.audioBitrateKbps,
            sampleRate = this[Keys.SampleRate] ?: d.sampleRate,
            stereo = this[Keys.Stereo] ?: d.stereo,
            keyframeSec = this[Keys.Keyframe] ?: d.keyframeSec,
            codec = enumOr(this[Keys.Codec], d.codec),
            recordLocally = this[Keys.Record] ?: d.recordLocally,
            transition = enumOr(this[Keys.Transition], d.transition),
        )
    }
}

inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default
