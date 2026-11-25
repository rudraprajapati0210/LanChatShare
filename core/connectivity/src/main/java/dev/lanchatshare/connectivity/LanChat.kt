package dev.lanchatshare.connectivity

import android.content.Context
import android.os.SystemClock
import dev.lanchatshare.data.Profile
import dev.lanchatshare.data.ProfileRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.WebSocketSession
import io.netty.handler.ssl.SslHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.Inet6Address
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.*
import kotlin.time.Duration.Companion.seconds

internal fun chatClient(pin: String) = HttpClient(CIO) {
    followRedirects = false
    engine {
        proxy = null
        endpoint { connectTimeout = 4500; connectAttempts = 1 }
        https {
            trustManager = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { throw CertificateException() }
                override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
                    verifyServerChain(chain, pin)
                }
            }
        }
    }
    install(io.ktor.client.plugins.websocket.WebSockets) { maxFrameSize = 32768; pingIntervalMillis = 10000 }
}

internal fun chatServer(hosts: List<String>, port: Int, tls: SSLContext, session: suspend WebSocketSession.() -> Unit) =
    embeddedServer(Netty, configure = {
        hosts.forEach { address -> connector { host = address; this.port = port } }
        connectionGroupSize = 1; workerGroupSize = 2; callGroupSize = 2
        enableHttp2 = false
        channelPipelineConfig = {
            // Let Android's TLS provider sign with the non-exportable pairing key.
            val engine = tls.createSSLEngine().apply {
                useClientMode = false
                enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            }
            addFirst("deviceTls", SslHandler(engine))
        }
    }) {
        install(io.ktor.server.websocket.WebSockets) { maxFrameSize = 32768; pingPeriod = 10.seconds; timeout = 20.seconds }
        routing { webSocket("/v1/chat") { session() } }
    }

class LanChat(private val context: Context, private val trust: TrustStore, profiles: ProfileRepository) {
    private val profile = profiles.profile.filterNotNull()
    private val status = MutableStateFlow("Waiting for a local network")
    val networkStatus = status.asStateFlow()
    private val sessions = mutableMapOf<String, Int>()
    private val connected = MutableStateFlow(emptySet<String>())
    val online = connected.asStateFlow()
    private val attempts = MutableStateFlow<Map<String, String>>(emptyMap())
    val diagnostics = attempts.asStateFlow()
    private data class Seen(val host: InetAddress, val port: Int, val until: Long)

    suspend fun run(): Unit = withContext(Dispatchers.IO) {
        val local = profile.first()
        val tls = serverTls()
        flow {
            while (currentCoroutineContext().isActive) { emit(lanLinks(context)); delay(3000) }
        }.distinctUntilChanged().collectLatest { links ->
            if (links.isEmpty()) { status.value = "Join Wi-Fi or turn on a phone hotspot."; return@collectLatest }
            while (currentCoroutineContext().isActive) {
                try { network(local, links, tls) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    android.util.Log.w("LanChatShare", "LAN listener unavailable", e)
                    status.value = "Couldn’t open the LAN listener. Retrying…"
                    delay(5000)
                }
            }
        }
    }

    private suspend fun network(local: Profile, links: List<LanLink>, tls: SSLContext): Unit = coroutineScope {
        val seen = ConcurrentHashMap<String, Seen>()
        val peers = trust.peers.stateIn(this, SharingStarted.Eagerly, emptyList())
        val pending = ConcurrentHashMap.newKeySet<String>()
        val challenges = AtomicInteger()
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = links.map { it.network }.distinct().singleOrNull()
        val previousNetwork = manager.boundNetworkForProcess
        val server = chatServer(links.map { requireNotNull(it.address.hostAddress) }.distinct(), LAN_PORT, tls) {
            if (challenges.incrementAndGet() > 12) { challenges.decrementAndGet(); return@chatServer }
            val peer = try { withTimeout(10000) { authenticateIncoming(local.id, trust) } }
                finally { challenges.decrementAndGet() }
            exchange(this, peer)
        }
        try {
            // Keep an internet-less Wi-Fi network selected even with mobile data enabled.
            if (wifi != null) check(manager.bindProcessToNetwork(wifi))
            server.start(wait = false)
            status.value = "On the local network"
            launch { LanDiscovery(context, local, links) { id, ip, port ->
                if (peers.value.any { it.id == id }) seen[id] = Seen(ip, port, SystemClock.elapsedRealtime() + 45000)
            }.run() }
            while (isActive) {
                peers.value.forEach { peer ->
                    val routes = trust.hints.value[peer.id].orEmpty().map { it.address() to it.port }.toMutableList()
                    seen[peer.id]?.takeIf { it.until > SystemClock.elapsedRealtime() }?.let { routes.add(0, it.host to it.port) }
                    if (peer.id !in online.value && routes.isNotEmpty() && pending.add(peer.id)) launch {
                        try {
                            for ((ip, port) in routes.distinct()) {
                                val link = links.firstOrNull { it.contains(ip) } ?: continue
                                val host = if (ip is Inet6Address && ip.isLinkLocalAddress)
                                    Inet6Address.getByAddress(null, ip.address, java.net.NetworkInterface.getByName(link.name)).hostAddress
                                else ip.hostAddress
                                val literal = if (ip is Inet6Address) "[" + host?.replace("%", "%25") + "]" else host
                                try {
                                    chatClient(peer.pin).use { client ->
                                        client.webSocket(urlString = "wss://$literal:$port/v1/chat") {
                                            withTimeout(10000) {
                                                val token = trust.pendingToken(peer.id)
                                                authenticateOutgoing(local.id, peer, identityKeys(), token)
                                                trust.completeOutgoing(peer.id, token)
                                            }
                                            exchange(this, peer)
                                        }
                                    }
                                    break
                                } catch (e: CancellationException) { if (!isActive) throw e }
                                catch (e: Exception) {
                                    val badPin = generateSequence<Throwable>(e) { it.cause }.any { it is CertificateException }
                                    attempts.update { it + (peer.id to if (badPin) "Device key changed. Verify this phone again in Nearby."
                                        else "No reply over Wi-Fi. Open LanChatShare on both phones. If this router isolates devices, try a phone hotspot.") }
                                }
                            }
                        } finally { pending.remove(peer.id) }
                    }
                }
                delay(3500)
            }
        } finally {
            try { server.stop(0, 1000) } finally {
                if (wifi != null && manager.boundNetworkForProcess == wifi) manager.bindProcessToNetwork(previousNetwork)
            }
        }
    }

    private suspend fun exchange(socket: WebSocketSession, peer: TrustedPeer) {
        fun change(delta: Int) = synchronized(sessions) {
            val count = (sessions[peer.id] ?: 0) + delta
            if (count > 0) sessions[peer.id] = count else sessions.remove(peer.id)
            connected.value = sessions.keys.toSet()
        }
        change(1)
        attempts.update { it - peer.id }
        try { socket.chat(peer, trust, profile) } finally { change(-1) }
    }
}


