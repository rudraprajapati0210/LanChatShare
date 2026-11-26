package dev.lanchatshare.connectivity

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import java.net.*
import javax.net.SocketFactory

internal fun routedClient(path: TurboRoute, credentials: Credentials, chat: Boolean): HttpClient {
    val (tls, verifier) = deviceTls(credentials, { mapOf(path.peer.id to path.peer.pin) }, true)
    val sockets = object : SocketFactory() {
        override fun createSocket(): Socket = (path.network?.socketFactory?.createSocket() ?: Socket()).apply {
            if (!isBound) bind(InetSocketAddress(path.local, 0))
            tcpNoDelay = chat
            if (!chat) { sendBufferSize = 512 * 1024; receiveBufferSize = 512 * 1024 }
        }
        override fun createSocket(host: String, port: Int) = createSocket(InetAddress.getByName(host),port)
        override fun createSocket(ip: InetAddress, port: Int) = createSocket().apply { connect(InetSocketAddress(ip,port)) }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int) = createSocket(host,port)
        override fun createSocket(ip: InetAddress, port: Int, local: InetAddress, localPort: Int) = createSocket(ip,port)
    }
    return HttpClient(OkHttp) {
        followRedirects = false
        engine { config {
            proxy(Proxy.NO_PROXY)
            socketFactory(sockets)
            sslSocketFactory(tls.socketFactory, verifier)
            dns { host -> if (host == deviceHost(path.peer.id)) listOf(path.remote) else throw UnknownHostException(host) }
            connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            if (chat) pingInterval(10, java.util.concurrent.TimeUnit.SECONDS)
        } }
        if (chat) install(WebSockets) { maxFrameSize = 32768 }
    }
}
