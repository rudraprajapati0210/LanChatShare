package dev.lanchatshare

import android.Manifest
import android.app.*
import android.bluetooth.BluetoothManager
import android.content.*
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.*
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.*
import androidx.lifecycle.compose.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable private fun Choice(label: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(on,role=Role.Switch,onValueChange=change).heightIn(min=52.dp),
        verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f)); Switch(on,null)
    }
}
@Composable internal fun NotificationAccess() {
    val c = LocalContext.current
    val nm = c.getSystemService(NotificationManager::class.java)
    var enabled by remember { mutableStateOf(nm.areNotificationsEnabled()) }
    val prefs by c.alertStore.data.collectAsStateWithLifecycle(emptyPreferences())
    val asked = prefs[booleanPreferencesKey("asked")] == true
    val scope = rememberCoroutineScope()
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        enabled = nm.areNotificationsEnabled(); alertPermission.value++
    }
    LifecycleResumeEffect(Unit) { enabled = nm.areNotificationsEnabled(); alertPermission.value++; onPauseOrDispose {} }
    if (!enabled) Column(Modifier.padding(horizontal=12.dp)) {
        Text("Allow alerts to see messages and file requests while another app is open.")
        TextButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !asked) {
                scope.launch { c.flag("asked",true); request.launch(Manifest.permission.POST_NOTIFICATIONS) }
            } else c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName))
        }) { Text(if (asked || Build.VERSION.SDK_INT < 33) "Open notification settings" else "Allow notifications") }
    }
}

@Composable internal fun NotificationSettings() {
    val c = LocalContext.current
    val opts by c.alerts.collectAsStateWithLifecycle(AlertOptions())
    val scope = rememberCoroutineScope()
    fun update(key: String,value: Boolean) { scope.launch { c.flag(key,value) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Choice("Stay reachable",opts.reachable) { update("reachable",it) }
        Text("Keep listening on the LAN when you leave the app. If Android stops the service, queued messages arrive after reconnection.")
        NotificationAccess()
        Choice("Messages",opts.messages) { update("messages",it) }
        Choice("File requests",opts.files) { update("files",it) }
        Choice("Transfer progress",opts.transfers) { update("transfers",it) }
        Choice("Show message previews",opts.preview) { update("preview",it) }
        Choice("Hide content on lock screen",opts.hideLocked) { update("hideLocked",it) }
        Choice("Quiet hours",opts.quiet) { update("quiet",it) }
        if (opts.quiet) listOf("from" to opts.from,"until" to opts.until).forEach { (key,hour) ->
            TextButton(onClick = {
                val hours = Array(24) { "%02d:00".format(it) }
                android.app.AlertDialog.Builder(c).setTitle("Quiet hours " + key).setSingleChoiceItems(hours,hour) { dialog, index ->
                    scope.launch { c.alertStore.edit { it[intPreferencesKey(key)] = index } }; dialog.dismiss()
                }.show()
            }) { Text("Quiet $key %02d:00".format(hour)) }
        }
        TextButton(onClick = { c.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE,c.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,"local_chat")) }) { Text("Connection status notification") }
    }
}

@Composable internal fun FirstTransferBatteryHelp(files: dev.lanchatshare.connectivity.Transfers) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val opts by c.alerts.collectAsStateWithLifecycle(AlertOptions(batterySeen=true))
    if (opts.batterySeen || c.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(c.packageName)) return
    val active by remember(files) { files.activeOperations.map { it > 0 }.distinctUntilChanged() }.collectAsStateWithLifecycle(false)
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(active) { if (active) show = true }
    if (show) {
        fun dismiss() { show = false; scope.launch { c.flag("batterySeen",true) } }
        androidx.compose.material3.AlertDialog(onDismissRequest=::dismiss,title={Text("Keep sharing with the screen off")},
            text={Column { Text("Some phones put local connections to sleep. If transfers pause while locked, allow background battery use for LanChatShare."); BatteryHelp() }},
            confirmButton={TextButton(onClick=::dismiss) { Text("Got it") }})
    }
}

@Composable internal fun Diagnostics(benchmark: () -> Unit) {
    val c = LocalContext.current
    val pm = c.getSystemService(PowerManager::class.java)
    val running by ChatService.running.collectAsStateWithLifecycle()
    var revision by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { revision++; onPauseOrDispose {} }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        val exempt = remember(revision) { pm.isIgnoringBatteryOptimizations(c.packageName) }
        Text(if (running) "The LAN listener service is running." else "The LAN listener service is stopped.")
        Text(if (c.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) "Notifications are allowed." else "Android is blocking notifications.")
        Text(if (c.applicationContext.getSystemService(WifiManager::class.java).isWifiEnabled) "Wi-Fi is on." else "Wi-Fi is off. A phone hotspot can also carry transfers.")
        val bt = if (Build.VERSION.SDK_INT >= 31 && c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            "Allow Nearby devices to check Bluetooth." else if (c.getSystemService(BluetoothManager::class.java).adapter?.isEnabled == true)
            "Bluetooth is on for pairing." else "Bluetooth is off or unavailable."
        Text(bt)
        Text(if (exempt) "Battery optimization is disabled for this app." else "Android may defer background network access during deep sleep.")
        BatteryHelp()
        TextButton(onClick=benchmark) { Text("Transfer benchmark") }
        TextButton(onClick = { revision++ }) { Text("Refresh checks") }
    }
}

@Composable internal fun BatteryHelp() {
    val c = LocalContext.current
    val tip = when (Build.MANUFACTURER.lowercase()) {
        "samsung" -> "On Samsung, check Background usage limits and Never auto sleeping apps."
        "xiaomi","redmi","poco" -> "On Xiaomi, check App battery saver and Background autostart."
        "oppo","realme" -> "On Oppo, check App battery management and Allow background activity."
        "vivo","iqoo" -> "On Vivo, check Background power consumption management and Autostart."
        "oneplus" -> "On OnePlus, check App battery usage and Allow background activity."
        else -> "Look for LanChatShare under battery optimization or app battery usage."
    }
    Text(tip)
    TextButton(onClick = {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        try { c.startActivity(intent) } catch (_: ActivityNotFoundException) {
            c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.fromParts("package",c.packageName,null)))
        }
    }) { Text("Review battery settings") }
}
