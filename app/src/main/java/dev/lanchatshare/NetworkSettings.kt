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
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.isNotEmpty() && grants.values.all { it }) LocalHotspot.start(context) else LocalHotspot.message = "Permission is needed to create a local Wi-Fi network."
    }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium)
        var turbo by remember { mutableStateOf(dev.lanchatshare.connectivity.TurboLink.mode(context)) }
        Text("Turbo transfer", style = MaterialTheme.typography.titleMedium)
        Row {
            dev.lanchatshare.connectivity.TurboMode.entries.forEach { mode ->
                FilterChip(selected = turbo == mode, onClick = {
                    turbo = mode
                    dev.lanchatshare.connectivity.TurboLink.setMode(context,mode)
                }, label = { Text(if (mode.name == "Ask") "Always ask" else mode.name) })
            }
        }
        Text("Start a direct link from Nearby on two verified phones. Auto accepts a verified phone’s request while Nearby is open.")
        Text("Some guest networks keep phones apart even when both show Wi-Fi. Open Nearby on both phones to try a direct link, or create a local hotspot below. Queued messages and accepted files resume when the phones reconnect.")
        val network = LocalHotspot.reservation
        if (network == null) {
            Button(enabled = !LocalHotspot.starting, onClick = {
                request.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.NEARBY_WIFI_DEVICES)
                    else arrayOf(android.Manifest.permission.ACCESS_COARSE_LOCATION, android.Manifest.permission.ACCESS_FINE_LOCATION))
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
