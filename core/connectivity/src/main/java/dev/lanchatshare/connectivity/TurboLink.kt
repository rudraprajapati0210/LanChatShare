@file:Suppress("MissingPermission")
package dev.lanchatshare.connectivity

import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.*
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class TurboMode { Off, Ask, Auto }

@Serializable internal data class RadioOffer(val medium: String, val ssid: String, val secret: String, val ip: String, val port: Int = LAN_PORT)
internal data class TurboRoute(val peer: TrustedPeer, val remote: InetAddress, val local: InetAddress, val network: Network?, val medium: String)
private data class HostRadio(val offer: RadioOffer, val lease: Closeable)

object TurboLink {
    fun mode(context: Context) = TurboMode.valueOf(context.getSharedPreferences("transfer-radio",0).getString("mode","Off") ?: "Off")
    fun setMode(context: Context, mode: TurboMode) {
        context.getSharedPreferences("transfer-radio",0).edit().putString("mode",mode.name).apply()
        if (mode == TurboMode.Off) stop()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val current = MutableStateFlow<TurboRoute?>(null)
    internal val route = current.asStateFlow()
    private val feedback = MutableStateFlow("")
    val status = feedback.asStateFlow()
    private var lease: Closeable? = null
    private var monitor: Job? = null
    @Synchronized fun stop() {
        current.value = null
        monitor?.cancel()
        monitor = null
        lease?.close()
        lease = null
    }

    @Synchronized private fun activate(path: TurboRoute, reservation: Closeable, trust: TrustStore) {
        stop()
        lease = reservation
        current.value = path
        feedback.value = "Connected over " + path.medium
        monitor = scope.launch {
            var started = false
            val expiry = android.os.SystemClock.elapsedRealtime() + 120000
            while (isActive) {
                val active = trust.transfers.forPeer(path.peer).first().any { it.state in listOf("active","retrying") }
                if (active) started = true
                if (trust.dao.pin(path.peer.id) != path.peer.pin || !active && (started || android.os.SystemClock.elapsedRealtime() >= expiry)) {
                    release(path)
                    break
                }
                delay(1000)
            }
        }
    }

    @Synchronized internal fun release(path: TurboRoute) {
        if (current.value === path) {
            stop()
            feedback.value = "Direct link closed. Using the available local network."
        }
    }

    @Synchronized fun compareSpeed(peer: String, currentMBps: Double, lanMBps: Double) {
        val path = current.value ?: return
        if (path.peer.id == peer && lanMBps > 0 && currentMBps < lanMBps * 1.05) {
            release(path)
            feedback.value = "The direct link did not improve speed. Resuming over the local network."
        }
    }

    internal suspend fun negotiate(context: Context, verified: Verified, frames: Frames, host: Boolean, trust: TrustStore) {
        val channel = verified.upgrade ?: return
        var owned: Closeable? = null
        try {
            val path = if (host) {
                val radio = try { withTimeout(12000) { directGroup(context) } }
                    catch (e: Exception) { currentCoroutineContext().ensureActive(); withTimeout(12000) { localHotspot(context) } }
                owned = radio.lease
                frames.send(channel.seal(Json.encodeToString(radio.offer).encodeToByteArray()))
                val local = ipv4(radio.offer.ip)
                val remote = ipv4(channel.open(frames.receive()).decodeToString())
                require(lanLinks(context).any { it.address == local && it.contains(remote) })
                TurboRoute(verified.peer,remote,local,null,radio.offer.medium)
            } else {
                val offer = Json.decodeFromString<RadioOffer>(channel.open(frames.receive()).decodeToString())
                require(offer.medium in listOf("Wi-Fi Direct","Local hotspot") && offer.port == LAN_PORT)
                val remote = ipv4(offer.ip)
                val (network, membership) = join(context,offer)
                owned = membership
                val link = lanLinks(context).first { it.network == network && it.address is Inet4Address && it.contains(remote) }
                frames.send(channel.seal(requireNotNull(link.address.hostAddress).encodeToByteArray()))
                TurboRoute(verified.peer,remote,link.address,network,offer.medium)
            }
            require(trust.dao.pin(path.peer.id) == path.peer.pin)
            activate(path,requireNotNull(owned),trust)
            owned = null
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            feedback.value = "Direct link unavailable. Reconnecting on the local network."
        } finally { owned?.close() }
    }

    private fun ipv4(text: String): InetAddress {
        require(text.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")))
        val bytes = text.split('.').map { it.toInt().also { part -> require(part in 0..255) }.toByte() }.toByteArray()
        return InetAddress.getByAddress(bytes).also { require(!it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isMulticastAddress) }
    }

    @SuppressLint("MissingPermission")
    private suspend fun directGroup(c: Context): HostRadio {
        val manager = requireNotNull(c.getSystemService(WifiP2pManager::class.java))
        val p2p = requireNotNull(manager.initialize(c, Looper.getMainLooper(), null))
        val ssid = "DIRECT-LC-" + UUID.randomUUID().toString().take(8)
        val secret = UUID.randomUUID().toString()
        val config = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(secret)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ).build()
        val removed = AtomicBoolean()
        val release = Closeable {
            if (removed.compareAndSet(false,true)) manager.requestGroupInfo(p2p) { group ->
                if (group?.networkName == ssid) manager.removeGroup(p2p, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() = p2p.close()
                    override fun onFailure(reason: Int) = p2p.close()
                }) else p2p.close()
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                manager.createGroup(p2p, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (cont.isActive) cont.resume(Unit) }
                    override fun onFailure(reason: Int) { if (cont.isActive) cont.resumeWithException(IllegalStateException("Group unavailable ($reason)")) }
                })
            }
            while (true) {
                val group = suspendCancellableCoroutine<WifiP2pGroup?> { cont -> manager.requestGroupInfo(p2p) { if (cont.isActive) cont.resume(it) } }
                if (group?.isGroupOwner == true && group.networkName == ssid) {
                    val address = NetworkInterface.getByName(group.getInterface())?.inetAddresses?.toList()?.filterIsInstance<Inet4Address>()?.singleOrNull()
                    if (address != null) return HostRadio(RadioOffer("Wi-Fi Direct",ssid,secret,requireNotNull(address.hostAddress)),release)
                }
                delay(250)
            }
        } catch (t: Throwable) { release.close(); throw t }
    }

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private suspend fun localHotspot(c: Context): HostRadio {
        val before = lanLinks(c).map { it.address }.toSet()
        val result = CompletableDeferred<WifiManager.LocalOnlyHotspotReservation>()
        val manager = c.getSystemService(WifiManager::class.java)
        manager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(value: WifiManager.LocalOnlyHotspotReservation) { if (!result.complete(value)) value.close() }
            override fun onFailed(reason: Int) { result.completeExceptionally(IllegalStateException("Hotspot unavailable ($reason)")) }
        },Handler(Looper.getMainLooper()))
        val reservation = try { result.await() } catch (t: Throwable) {
            if (!result.completeExceptionally(t)) runCatching { result.await().close() }
            throw t
        }
        try {
            val config = reservation.softApConfiguration
            while (true) {
                val ip = lanLinks(c).filter { it.network == null && it.address is Inet4Address && it.address !in before }.singleOrNull()?.address
                if (ip != null) return HostRadio(RadioOffer("Local hotspot",requireNotNull(config.ssid),requireNotNull(config.passphrase),requireNotNull(ip.hostAddress)),Closeable { reservation.close() })
                delay(250)
            }
        } catch (t: Throwable) { reservation.close(); throw t }
    }

    @SuppressLint("MissingPermission")
    private suspend fun join(c: Context, offer: RadioOffer): Pair<Network,Closeable> {
        val manager = c.getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(WifiNetworkSpecifier.Builder().setSsid(offer.ssid).setWpa2Passphrase(offer.secret).build()).build()
        val ready = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, props: LinkProperties) {
                if (props.linkAddresses.any { it.address is Inet4Address }) ready.complete(network)
            }
            override fun onUnavailable() { ready.completeExceptionally(IllegalStateException("Network request declined")) }
            override fun onLost(network: Network) { current.value?.takeIf { it.network == network }?.let(::release) }
        }
        manager.requestNetwork(request,callback,45000)
        val membership = Closeable { runCatching { manager.unregisterNetworkCallback(callback) } }
        try { return withTimeout(47000) { ready.await() } to membership }
        catch (t: Throwable) { membership.close(); throw t }
    }
}
