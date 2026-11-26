package dev.lanchatshare.connectivity

import java.net.InetAddress
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CeilingTest {
    @Test fun refusesWrongPinBeforePayloadAndCountsBothAuthenticatedProbes() = runBlocking {
        val id = UUID.randomUUID().toString()
        val credentials = deviceCredentials(id)
        val pin = android.util.Base64.encodeToString(digest(identityKeys().public.encoded),android.util.Base64.NO_WRAP)
        val ip = InetAddress.getLoopbackAddress()
        val peer = TrustedPeer(id,"Fixture",0,pin)
        val route = ProbeRoute(peer,credentials,ip,ip,null)
        for (wrongPin in listOf(true,false)) {
            val port = CompletableDeferred<Int>()
            val server = async(Dispatchers.IO) {
                runCatching { ProbeSockets().use { sockets ->
                    ceilingPair(route,true,10,sockets,0,{ port.complete(it) }) {}
                } }
            }
            val results = mutableListOf<CeilingRate>()
            val target = if (wrongPin) route.copy(peer=peer.copy(pin="different")) else route
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ProbeSockets().use { sockets ->
                    ceilingPair(target,false,10,sockets,withTimeout(10000){port.await()},measured=results::add)
                } }
            }
            assertEquals(!wrongPin,outcome.isSuccess)
            assertEquals(!wrongPin,server.await().isSuccess)
            if (wrongPin) assertTrue(results.isEmpty()) else {
                assertEquals(listOf("TCP","TLS"),results.map { it.mode })
                assertTrue(results.all { it.bytes == 10_000_000L && it.mbps > 0 })
            }
        }
    }
}
