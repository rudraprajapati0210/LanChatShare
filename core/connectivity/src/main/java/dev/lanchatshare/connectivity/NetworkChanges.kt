package dev.lanchatshare.connectivity

import android.content.Context
import android.net.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

internal fun networkChanges(context: Context) = callbackFlow {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { trySend(Unit) }
        override fun onLost(network: Network) { trySend(Unit) }
        override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) { trySend(Unit) }
    }
    manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),callback)
    trySend(Unit)
    // Ordinary apps do not receive callbacks for every OEM downstream tethering interface.
    val downstream = launch { while (true) { delay(3000); trySend(Unit) } }
    awaitClose { downstream.cancel(); manager.unregisterNetworkCallback(callback) }
}.conflate()

internal class ReconnectDelay {
    private var failures = 0
    var next = 0L
        private set
    fun failed(now: Long) {
        next = now + (1000L shl failures.coerceAtMost(5))
        failures = (failures + 1).coerceAtMost(5)
    }
    fun connected() { failures = 0; next = 0 }
}
