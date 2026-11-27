package com.yuklive.app.core.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

data class DeviceStatus(
    val batteryPercent: Int? = null,
    val temperatureC: Float? = null,
    val isCharging: Boolean = false,
    /** Status termal sistem (PowerManager.THERMAL_STATUS_*), 0 = normal. */
    val thermalStatus: Int = 0,
) {
    val isBatteryLow: Boolean get() = !isCharging && (batteryPercent ?: 100) <= 15
    val isHot: Boolean get() = (temperatureC ?: 0f) >= 42f || thermalStatus >= THERMAL_MODERATE
    val isCritical: Boolean get() = (temperatureC ?: 0f) >= 45f || thermalStatus >= THERMAL_SEVERE
    val isCool: Boolean get() = (temperatureC ?: 0f) < 40f && thermalStatus < THERMAL_MODERATE

    companion object {
        const val THERMAL_MODERATE = 2 // PowerManager.THERMAL_STATUS_MODERATE
        const val THERMAL_SEVERE = 3   // PowerManager.THERMAL_STATUS_SEVERE
    }
}

/** Memantau baterai, suhu, status termal, dan koneksi internet HP. */
@Singleton
class DeviceStatusMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private data class Battery(val percent: Int?, val temp: Float?, val charging: Boolean)

    private val battery: Flow<Battery> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.toBattery())
            }
        }
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let { trySend(it.toBattery()) }
        awaitClose { context.unregisterReceiver(receiver) }
    }

    private val thermal: Flow<Int> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            callbackFlow {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                trySend(pm.currentThermalStatus)
                val listener = PowerManager.OnThermalStatusChangedListener { trySend(it) }
                pm.addThermalStatusListener(context.mainExecutor, listener)
                awaitClose { pm.removeThermalStatusListener(listener) }
            }
        } else {
            flowOf(0)
        }

    val status: Flow<DeviceStatus> = combine(battery, thermal) { b, t ->
        DeviceStatus(batteryPercent = b.percent, temperatureC = b.temp, isCharging = b.charging, thermalStatus = t)
    }.distinctUntilChanged()

    /** true = ada internet. */
    val online: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun current(): Boolean = cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(current())
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(true) }
            override fun onLost(network: Network) { trySend(current()) }
        }
        cm.registerDefaultNetworkCallback(cb)
        awaitClose { cm.unregisterNetworkCallback(cb) }
    }.distinctUntilChanged()

    private fun Intent.toBattery(): Battery {
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else null
        val rawTemp = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val temp = if (rawTemp == Int.MIN_VALUE) null else rawTemp / 10f
        val plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Battery(percent, temp, plugged)
    }
}
