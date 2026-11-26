package dev.lanchatshare.connectivity

import android.net.Network
import java.io.*
import java.net.*

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import javax.net.ssl.*
import java.security.MessageDigest
import java.security.SecureRandom

private const val PORT = 47839
private const val BUFFER = 128 * 1024
internal data class ProbeRoute(val peer: TrustedPeer, val credentials: Credentials, val remote: InetAddress, val local: InetAddress, val network: Network?, val files: Transfers? = null)
private val routes = MutableStateFlow<List<ProbeRoute>>(emptyList())

internal fun diagnosticRoute(files: Transfers, peer: TrustedPeer, credentials: Credentials, remote: InetAddress, local: InetAddress, network: Network?): Closeable {
    val route = ProbeRoute(peer,credentials,remote,local,network,files)
    synchronized(routes) { routes.value = routes.value + route }
    return Closeable { synchronized(routes) { routes.value = routes.value.filterNot { it === route } } }
}

data class CeilingRate(val mode: String,val bytes: Long,val nanos: Long) {
    val mbps: Double get() = bytes * 1000.0 / nanos.coerceAtLeast(1)
}

// The lifetime guard interrupts blocking accept, read and write on cancellation or route loss.
internal class ProbeSockets : Closeable {
    private val handles = mutableListOf<Closeable>()
    private var closed = false
    @Volatile private var activity = System.nanoTime()
    fun progressed() { activity = System.nanoTime() }
    fun idleSeconds() = (System.nanoTime()-activity)/1_000_000_000
    @Synchronized fun <T : Closeable> own(resource: T): T {
        if (closed) { resource.close(); throw IOException("Probe closed") }
        handles += resource
        return resource
    }
    @Synchronized override fun close() {
        closed = true
        handles.asReversed().forEach { runCatching { it.close() } }
    }
}

suspend fun runCeiling(peer: TrustedPeer, send: Boolean, mb: Int, started: () -> Unit = {}, measured: (CeilingRate) -> Unit) {
    require(mb in listOf(10,100,1000))
    val route = routes.value.lastOrNull { it.peer.id == peer.id && it.peer.pin == peer.pin } ?: error("Connect to the paired phone first")
    withTimeout(120_000L + mb * 16_000L) {
        coroutineScope {
            val sockets = ProbeSockets()
            val guard = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try { routes.first { list -> list.none { it === route } } } finally { sockets.close() }
            }
            val watchdog = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(5000)
                    if (sockets.idleSeconds() > 65) { sockets.close(); break }
                }
            }
            try { withContext(Dispatchers.IO) { requireNotNull(route.files).duringTransfer { ceilingPair(route,send,mb,sockets,started = started, measured = measured) } } }
            finally { sockets.close(); guard.cancel(); watchdog.cancel() }
        }
    }
}

internal fun ceilingPair(route: ProbeRoute, send: Boolean, mb: Int, sockets: ProbeSockets, controlPort: Int = PORT, listening: (Int) -> Unit = {}, started: () -> Unit = {}, measured: (CeilingRate) -> Unit) {
    require(mb in listOf(10,100,1000))
    val pins = { mapOf(route.peer.id to route.peer.pin) }
    val tls = deviceTls(route.credentials,pins,!send).first
    fun listen(port: Int): ServerSocket = sockets.own(ServerSocket()).apply {
        reuseAddress = true
        bind(InetSocketAddress(route.local,port),1)
        soTimeout = 60_000
    }
    fun connect(port: Int) = sockets.own(route.network?.socketFactory?.createSocket() ?: Socket()).apply {
        if (!isBound) bind(InetSocketAddress(route.local,0))
        connect(InetSocketAddress(route.remote,port),8000)
    }
    fun accept(listener: ServerSocket) = sockets.own(listener.accept()).also {
        require(it.inetAddress == route.remote) { "Connection came from a different phone" }
        it.soTimeout = 15_000
    }
    val wire = if (send) listen(controlPort).use { listening(it.localPort); accept(it) } else connect(controlPort)
    val control = sockets.own(tls.socketFactory.createSocket(wire,deviceHost(route.peer.id),wire.port,true) as SSLSocket)
    control.useClientMode = !send
    if (send) control.needClientAuth = true else control.sslParameters = control.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    control.soTimeout = 30_000
    control.startHandshake()
    val input = DataInputStream(control.inputStream)
    val output = DataOutputStream(control.outputStream)
    val nonce = ByteArray(32)
    val buffer = ByteArray(BUFFER)
    val size = mb * 1_000_000L
    if (send) {
        require(input.readInt() == mb) { "Choose the same size on both phones" }
        SecureRandom().nextBytes(nonce)
        java.util.Random(147).nextBytes(buffer)
    } else { output.writeInt(mb); output.flush() }
    // Send generated memory through a one-use raw socket, then through the authenticated socket.
    for (mode in listOf("TCP","TLS")) {
        val data = if (mode == "TLS") control else if (send) {
            listen(0).use { listener ->
                output.writeInt(listener.localPort); output.write(nonce); output.flush()
                accept(listener).also { client ->
                    val proof = ByteArray(nonce.size)
                    DataInputStream(client.inputStream).readFully(proof)
                    require(MessageDigest.isEqual(nonce,proof))
                }
            }
        } else {
            val port = input.readInt()
            input.readFully(nonce)
            connect(port).also { it.getOutputStream().write(nonce) }
        }
        data.soTimeout = 30_000
        started()
        val start = System.nanoTime()
        var remaining = size
        if (send) {
            val stream = data.getOutputStream()
            while (remaining > 0) {
                val n = minOf(remaining,buffer.size.toLong()).toInt()
                stream.write(buffer,0,n)
                remaining -= n
                sockets.progressed()
            }
            stream.flush()
            check(input.readLong() == size)
        } else {
            val stream = data.getInputStream()
            while (remaining > 0) {
                val n = stream.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt())
                if (n < 0) throw EOFException("Probe interrupted")
                remaining -= n
                sockets.progressed()
            }
            output.writeLong(size); output.flush()
        }
        measured(CeilingRate(mode,size,System.nanoTime()-start))
        if (data !== control) data.close()
    }
}
