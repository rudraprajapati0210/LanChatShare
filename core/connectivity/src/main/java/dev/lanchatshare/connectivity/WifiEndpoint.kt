package dev.lanchatshare.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import java.util.Base64
import kotlinx.serialization.Serializable

// Milestone C's TLS service port. Pairing exchanges route hints even before a listener is running.
internal const val LAN_PORT = 47831

@Serializable
internal data class WifiEndpoint(val ipBytes: String, val port: Int) {
    fun address(): InetAddress {
        require(port in 1..65535)
        val bytes = Base64.getDecoder().decode(ipBytes)
        require(bytes.size == 4 || bytes.size == 16)
        val ip = InetAddress.getByAddress(bytes)
        require(!ip.isAnyLocalAddress && !ip.isLoopbackAddress && !ip.isMulticastAddress)
        if (bytes.size == 4) require((bytes[0].toInt() and 255) in 1..223)
        return ip
    }
    fun display(): String {
        val ip = requireNotNull(address().hostAddress)
        return if (':' in ip) "[$ip]:$port" else "$ip:$port"
    }
}
@Suppress("DEPRECATION")
internal fun wifiEndpoints(context: Context): List<WifiEndpoint> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    return manager.allNetworks.filter { network ->
        val caps = manager.getNetworkCapabilities(network)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.flatMap { manager.getLinkProperties(it)?.linkAddresses.orEmpty() }
        .map { WifiEndpoint(Base64.getEncoder().encodeToString(it.address.address), LAN_PORT) }
        .filter { runCatching { it.address() }.isSuccess }.distinct().take(8)
}
