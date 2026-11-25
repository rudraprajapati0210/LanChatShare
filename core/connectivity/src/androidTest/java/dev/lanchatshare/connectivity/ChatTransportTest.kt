package dev.lanchatshare.connectivity

import androidx.test.platform.app.InstrumentationRegistry
import dev.lanchatshare.data.Profile
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID

class ChatTransportTest {
    @Test fun pinnedTlsDeliversQueuedMessageAndReply() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val a = Profile("Mira", UUID.randomUUID().toString(), 1)
        val b = Profile("Rudra", UUID.randomUUID().toString(), 2)
        val sender = TrustStore(context, a.id)
        val recipient = TrustStore(context, b.id)
        val keysA = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fun pin(key: java.security.PublicKey) = Base64.getEncoder().encodeToString(digest(key.encoded))
        val peerB = TrustedPeer(b.id, b.name, 2, pin(identityKeys().public))
        val server = chatServer(listOf("127.0.0.1"), 0, serverTls()) {
            chat(authenticateIncoming(b.id, recipient), recipient, flowOf(b))
        }
        try {
            sender.dao.save(peerB)
            recipient.dao.save(TrustedPeer(a.id, a.name, 1, pin(keysA.public)))
            sender.enqueue(b.id, "Hey from the hotspot")
            assertFalse(sender.conversation(b.id).first().single().delivered)
            server.start(false)
            val port = server.engine.resolvedConnectors().first().port
            val url = "wss://127.0.0.1:$port/v1/chat"
            val wrongPin = chatClient(pin(keysA.public))
            try {
                val attempt = runCatching { withTimeout(10000) { wrongPin.webSocket(urlString = url) { error("reached endpoint") } } }
                assertTrue(attempt.isFailure)
                assertNotEquals("reached endpoint", attempt.exceptionOrNull()?.message)
            } finally { wrongPin.close() }
            chatClient(peerB.pin).use { client ->
                val session = launch(Dispatchers.IO) {
                    client.webSocket(urlString = url) {
                        authenticateOutgoing(a.id, peerB, keysA, null)
                        chat(peerB, sender, flowOf(a))
                    }
                }
                try {
                    withTimeout(30000) {
                        assertEquals("Hey from the hotspot", recipient.conversation(a.id).first { it.isNotEmpty() }.single().text)
                        sender.conversation(b.id).first { it.first().delivered }
                        recipient.enqueue(a.id, "Got it")
                        sender.conversation(b.id).first { it.any { msg -> !msg.outgoing && msg.text == "Got it" } }
                        recipient.conversation(a.id).first { it.any { msg -> msg.outgoing && msg.delivered } }
                    }
                } finally { session.cancelAndJoin() }
            }
        } finally {
            server.stop(0, 1000); sender.close(); recipient.close()
            context.deleteDatabase(a.id); context.deleteDatabase(b.id)
        }
        Unit
    }
}
