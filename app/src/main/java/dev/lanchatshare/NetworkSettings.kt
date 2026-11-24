package dev.lanchatshare

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanchatshare.connectivity.LanChat

private object LocalHotspot {
    var reservation by mutableStateOf<WifiManager.LocalOnlyHotspotReservation?>(null)
    var starting by mutableStateOf(false)
    var message by mutableStateOf("")
    private var generation = 0
    @SuppressLint("MissingPermission")
    fun start(context: Context) {
        if (starting || reservation != null) return
        val current = ++generation
        starting = true
        message = "Starting hotspot…"
        try {
            context.applicationContext.getSystemService(WifiManager::class.java).startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) {
                        if (generation != current) { value.close(); return }
                        reservation = value; starting = false; message = ""
                    }
                    override fun onFailed(reason: Int) {
                        if (generation != current) return
                        starting = false; message = "Android couldn’t create the network ($reason). Try your phone’s hotspot settings."
                    }
                    override fun onStopped() {
                        if (generation != current) return
                        starting = false; reservation = null; message = "The hotspot stopped."
                    }
                }, Handler(Looper.getMainLooper()))
        } catch (_: RuntimeException) { starting = false; message = "Check hotspot availability and nearby-device permissions." }
    }
    fun stop() { generation++; reservation?.close(); reservation = null; starting = false; message = "" }
}

@Composable internal fun NetworkSettings(chat: LanChat) {
    val context = LocalContext.current
    val status by chat.networkStatus.collectAsStateWithLifecycle()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) LocalHotspot.start(context) else LocalHotspot.message = "Permission is needed to create a local Wi-Fi network."
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium)
        Text("Join the same router or phone hotspot. If a guest network keeps devices apart, try a phone hotspot.")
        val network = LocalHotspot.reservation
        if (network == null) {
            Button(enabled = !LocalHotspot.starting, onClick = {
                request.launch(if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.NEARBY_WIFI_DEVICES
                    else android.Manifest.permission.ACCESS_FINE_LOCATION)
            }) { Text("Create LanChatShare network") }
        } else {
            @Suppress("DEPRECATION") val name = network.softApConfiguration.ssid
            Text("Wi-Fi network: " + name.orEmpty())
            Text("Password: " + network.softApConfiguration.passphrase.orEmpty())
            OutlinedButton(onClick = LocalHotspot::stop) { Text("Stop network") }
        }
        if (LocalHotspot.message.isNotEmpty()) Text(LocalHotspot.message)
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }) { Text("Open phone network settings") }
    }
}
