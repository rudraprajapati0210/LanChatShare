package dev.lanchatshare

import android.Manifest.permission.*
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lanchatshare.connectivity.*
import dev.lanchatshare.data.Profile

@Composable internal fun NearbyScreen(profile: Profile, trust: TrustStore) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permissions = remember {
        if (Build.VERSION.SDK_INT >= 31) arrayOf(BLUETOOTH_SCAN, BLUETOOTH_ADVERTISE, BLUETOOTH_CONNECT)
        else arrayOf(ACCESS_FINE_LOCATION)
    }
    val allowed = remember(refresh, foreground) { permissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    val ready = allowed && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
    val locationReady = Build.VERSION.SDK_INT >= 31 || context.getSystemService(LocationManager::class.java).isLocationEnabled
    var attempt by remember { mutableIntStateOf(0) }
    val radio = remember(profile, allowed, ready, locationReady, foreground, attempt) {
        if (ready && locationReady && foreground) NearbyRadio(context.applicationContext, profile, trust) else null
    }
    DisposableEffect(radio) {
        radio?.start()
        onDispose { radio?.close() }
    }
    val state = radio?.state?.collectAsStateWithLifecycle()?.value ?: NearbyState()
    val trusted by trust.peers.collectAsStateWithLifecycle(emptyList())
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            when {
                adapter == null -> Text("Bluetooth LE is unavailable on this device.")
                !allowed -> Column {
                    Text(if (Build.VERSION.SDK_INT >= 31) "Allow Nearby devices to find and verify phones over Bluetooth."
                        else "Android 11 requires location permission to scan for nearby Bluetooth phones.")
                    Button(onClick = { request.launch(permissions) }) { Text("Allow discovery") }
                    TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) }) { Text("App settings") }
                }
                !ready -> Button(onClick = { enable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) { Text("Turn on Bluetooth") }
                !locationReady -> Button(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Turn on location for scanning") }
                else -> Column {
                    Text(state.status)
                    if (!state.busy) TextButton(onClick = { attempt++ }) { Text(if (state.failed) "Try again" else "Refresh") }
                    else TextButton(onClick = { attempt++ }) { Text("Cancel pairing") }
                }
            }
        }
        items(state.devices.values.sortedBy { it.name }, key = { it.address }) { peer ->
            OutlinedButton(enabled = !state.busy && !state.failed, onClick = { radio?.connect(peer) }) {
                Text(peer.name.ifBlank { "Nearby phone" } + " · " + peer.suffix)
            }
        }
        if (trusted.isNotEmpty()) item { Text("Trusted devices", style = MaterialTheme.typography.titleMedium) }
        items(trusted, key = { it.id }) { peer ->
            Text(if (trusted.count { it.name == peer.name } > 1) peer.name + " · " + peer.id.takeLast(4).uppercase() else peer.name)
        }
    }
    state.prompt?.let { prompt ->
        AlertDialog(onDismissRequest = { radio?.answer(false) },
            title = { Text(if (prompt.changed) "Device key changed" else "Compare both phones") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(prompt.name + " · " + prompt.suffix)
                Text(prompt.code.chunked(3).joinToString(" "), style = MaterialTheme.typography.displaySmall)
                Text("Confirm only if both screens show these digits.")
            } }, confirmButton = { Button(onClick = { radio?.answer(true) }) { Text("Codes match") } },
            dismissButton = { TextButton(onClick = { radio?.answer(false) }) { Text("Cancel") } })
    }
}
