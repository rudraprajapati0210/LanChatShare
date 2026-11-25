package dev.lanchatshare

import android.app.Service
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
internal suspend fun Context.holdTransferPower(operations: StateFlow<Int>) {
    val cpu = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"LanChatShare:transfer")
    val wifi = applicationContext.getSystemService(WifiManager::class.java).createWifiLock(
        if (Build.VERSION.SDK_INT >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF,
        "LanChatShare:transfer")
    cpu.setReferenceCounted(false); wifi.setReferenceCounted(false)
    operations.map { it > 0 }.distinctUntilChanged().transformLatest { active ->
        // Adjacent range responses are part of one transfer.
        if (!active) delay(250)
        emit(active)
    }.distinctUntilChanged().collectLatest { active ->
        if (active) try {
            try { wifi.acquire() } catch (e: SecurityException) { android.util.Log.w("LanChatShare","Wi-Fi lock unavailable",e) }
            while (currentCoroutineContext().isActive) {
                cpu.acquire(60_000)
                delay(40_000)
            }
        } finally {
            if (cpu.isHeld) cpu.release()
            if (wifi.isHeld) wifi.release()
        }
    }
}
