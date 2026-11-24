package dev.lanchatshare.connectivity

import android.content.Context
import android.net.*
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.SystemClock
import dev.lanchatshare.data.Profile
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*

internal data class LanLink(val address: InetAddress, val prefix: Int, val name: String, val broadcast: InetAddress?) {
    fun contains(other: InetAddress) = IpPrefix(address, prefix).contains(other)
}
@Suppress("DEPRECATION")
internal fun lanLinks(context: Context): List<LanLink> {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val wifi = manager.allNetworks.filter {
        val caps = manager.getNetworkCapabilities(it)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }.mapNotNull { manager.getLinkProperties(it)?.interfaceName }.toSet()
    val hotspot = Regex("(ap|wlan|swlan|softap|sap|wifi|p2p)[a-z0-9_.-]*", RegexOption.IGNORE_CASE)
    return NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { device ->
        device.isUp && !device.isLoopback && (device.name in wifi || hotspot.matches(device.name))
    }.flatMap { device -> device.interfaceAddresses.mapNotNull { info ->
        val ip = info.address ?: return@mapNotNull null
        if (ip.isLoopbackAddress || ip.isMulticastAddress || ip.isAnyLocalAddress) null
        else LanLink(ip, info.networkPrefixLength.toInt(), device.name, info.broadcast)
    } }.sortedBy { it.address.hostAddress }
}

internal class LanDiscovery(private val context: Context, private val profile: Profile, private val links: List<LanLink>,
    private val found: (String, InetAddress, Int) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val type = "_lanchatshare._tcp."
    @Volatile private var active = true
    private val resolving = ConcurrentHashMap.newKeySet<String>()
    private fun offer(id: String?, address: InetAddress?, port: Int) {
        if (active && id != null && id != profile.id && id.length == 36 && port in 1..65535 &&
            address != null && links.any { it.contains(address) }) found(id, address, port)
    }
    private val registration = object : NsdManager.RegistrationListener {
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        override fun onServiceRegistered(info: NsdServiceInfo) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
    }
    @Suppress("DEPRECATION")
    private val browsing = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!active || info.serviceName == profile.id || !resolving.add(info.serviceName)) return
            runCatching { nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(value: NsdServiceInfo, code: Int) { resolving.remove(info.serviceName) }
                override fun onServiceResolved(value: NsdServiceInfo) {
                    resolving.remove(info.serviceName)
                    offer(value.attributes["id"]?.decodeToString(), value.host, value.port)
                }
            }) }.onFailure { resolving.remove(info.serviceName) }
        }
        override fun onServiceLost(info: NsdServiceInfo) {}
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) {}
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    suspend fun run(): Unit = coroutineScope {
        val multicast = context.getSystemService(WifiManager::class.java).createMulticastLock("LanChatShare")
        runCatching { multicast.acquire() }
        val service = NsdServiceInfo().apply {
            serviceName = profile.id; serviceType = type; port = LAN_PORT
            setAttribute("id", profile.id); setAttribute("name", profile.name); setAttribute("colour", profile.colour.toString())
        }
        try {
            runCatching { nsd.registerService(service, NsdManager.PROTOCOL_DNS_SD, registration) }
            runCatching { nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, browsing) }
            launch { runCatching { broadcastLoop() } }
            awaitCancellation()
        } finally {
            active = false
            runCatching { nsd.stopServiceDiscovery(browsing) }
            runCatching { nsd.unregisterService(registration) }
            runCatching { if (multicast.isHeld) multicast.release() }
        }
    }

    private suspend fun broadcastLoop() {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true; socket.broadcast = true; socket.soTimeout = 900
            socket.bind(InetSocketAddress(LAN_PORT + 1))
            var next = 0L
            val hello = ("LCS1 " + profile.id).encodeToByteArray()
            val bytes = ByteArray(64)
            while (currentCoroutineContext().isActive) {
                if (SystemClock.elapsedRealtime() >= next) {
                    links.mapNotNull { it.broadcast }.distinct().forEach {
                        runCatching { socket.send(DatagramPacket(hello, hello.size, it, LAN_PORT + 1)) }
                    }
                    next = SystemClock.elapsedRealtime() + 5000
                }
                val packet = DatagramPacket(bytes, bytes.size)
                try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
                val text = bytes.decodeToString(0, packet.length)
                if (text.startsWith("LCS1 ")) offer(text.substring(5), packet.address, LAN_PORT)
            }
        }
    }
}
