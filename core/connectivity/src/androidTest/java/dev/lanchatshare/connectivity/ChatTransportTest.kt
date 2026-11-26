package dev.lanchatshare.connectivity

import androidx.test.platform.app.InstrumentationRegistry
import dev.lanchatshare.data.Profile
import io.ktor.client.plugins.websocket.*
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.*
import io.ktor.http.*
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
        val bTls = deviceCredentials(b.id)
        val name = org.bouncycastle.asn1.x500.X500Name("CN=older-pairing-key")
        val now = System.currentTimeMillis()
        val rootA = certificate(name, name, keysA.public, keysA.private, now - 60000, now + TLS_PERIOD, null, true)
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val aTls = transportCredentials(a.id,keysA.private,rootA,store,a.id + ".",now)
        val trusted = java.util.concurrent.atomic.AtomicReference(mapOf(a.id to pin(keysA.public)))
        val upgrades = java.util.concurrent.atomic.AtomicInteger()
        val server = chatServer(listOf("127.0.0.1"), 0, serverTls(bTls, trusted::get), recipient.transfers) {
            upgrades.incrementAndGet()
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
            val loopback = java.net.InetAddress.getByName("127.0.0.1")
            val path = TurboRoute(peerB,loopback,loopback,null,"direct socket test")
            routedClient(path,aTls,false).use { direct ->
                assertEquals(HttpStatusCode.Forbidden,
                    direct.get("https://${deviceHost(b.id)}:$port/v1/files/no-grant").status)
            }
            routedClient(path.copy(peer = peerB.copy(pin = pin(keysA.public))),aTls,false).use { direct ->
                assertTrue(runCatching { direct.get("https://${deviceHost(b.id)}:$port/v1/files/no-grant") }.isFailure)
            }
            val wrongPin = chatClient(peerB.copy(pin = pin(keysA.public)), aTls)
            try {
                val attempt = runCatching { withTimeout(10000) { wrongPin.webSocket(urlString = url) { error("reached endpoint") } } }
                assertTrue(attempt.isFailure)
                assertNotEquals("reached endpoint", attempt.exceptionOrNull()?.message)
            } finally { wrongPin.close() }
            chatClient(peerB, aTls).use { client ->
                val session = launch(Dispatchers.IO) {
                    client.webSocket(urlString = url) {
                        authenticateOutgoing(a.id, peerB, keysA, null)
                        chat(peerB, sender, flowOf(a)) { row, token -> sender.transfers.receive(row, client, "https://127.0.0.1:$port", token) }
                    }
                }
                try {
                    withTimeout(30000) {
                        assertEquals("Hey from the hotspot", recipient.conversation(a.id).first { it.isNotEmpty() }.single().text)
                        sender.conversation(b.id).first { it.first().delivered }
                        recipient.enqueue(a.id, "Got it")
                        sender.conversation(b.id).first { it.any { msg -> !msg.outgoing && msg.text == "Got it" } }
                        recipient.conversation(a.id).first { it.any { msg -> msg.outgoing && msg.delivered } }
                        val bytes = ByteArray(RANGE_BLOCK * 3 + 177) { (it * 11).toByte() }
                        recipient.transfers.stage(listOf(recipient.dao.find(a.id)!!), "Test photo.jpg", "image/jpeg") { bytes.inputStream() }
                        val offer = sender.transfers.all.first { it.isNotEmpty() }.single()
                        val source = recipient.transfers.all.first().single()
                        val denied = client.get("https://127.0.0.1:$port/v1/files/${offer.id}") {
                            header(HttpHeaders.Range, "bytes=0-31"); header(HttpHeaders.Authorization, "Bearer ${source.token}")
                        }
                        assertEquals(HttpStatusCode.Forbidden, denied.status)
                        val target = sender.transfers.file(offer)
                        target.parentFile!!.mkdirs()
                        val partial = java.io.File(target.path + ".partial")
                        partial.writeBytes(ByteArray(RANGE_BLOCK))
                        sender.transfers.dao.checkpoint(offer.peer, offer.id, RANGE_BLOCK.toLong())
                        sender.transfers.action(offer, "active")
                        val invalid = sender.transfers.all.first { it.single().state == "invalid" }.single()
                        assertFalse(target.exists())
                        assertFalse(recipient.conversation(a.id).first().last().delivered)
                        partial.writeBytes(bytes.copyOf(RANGE_BLOCK))
                        sender.transfers.dao.checkpoint(offer.peer, offer.id, RANGE_BLOCK.toLong())
                        sender.transfers.action(invalid, "active")
                        recipient.transfers.all.first { it.single().state == "done" }
                        assertArrayEquals(bytes, target.readBytes())
                        assertTrue(recipient.conversation(a.id).first().last().delivered)
                        target.delete(); recipient.transfers.file(source).delete()
                    }
                } finally { session.cancelAndJoin() }
            }
            assertEquals(1, upgrades.get())
            recipient.enqueue(a.id, "Still queued")
            recipient.forget(recipient.dao.find(a.id)!!)
            trusted.set(recipient.peers.first().associate { it.id to it.pin })
            assertNull(recipient.dao.pin(a.id))
            assertEquals(3, recipient.conversation(a.id).first().size)
            chatClient(peerB, aTls).use { client ->
                val failure = runCatching { withTimeout(10000) { client.webSocket(urlString = url) {} } }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure is TimeoutCancellationException)
            }
            assertEquals(1, upgrades.get())
            // Reauthorize the test peer for an independent transfer after the revocation test.
            recipient.dao.save(TrustedPeer(a.id, a.name, 1, pin(keysA.public)))
            trusted.set(mapOf(a.id to pin(keysA.public)))
            val source = Transfer(a.id, true, UUID.randomUUID().toString(), "recovery.bin",
                "application/octet-stream", 12L * 1024 * 1024, "", pin(keysA.public),
                UUID.randomUUID().toString(), "active", 0, now)
            val payload = recipient.transfers.file(source)
            payload.parentFile!!.mkdirs()
            java.io.RandomAccessFile(payload, "rw").use { it.setLength(source.size) }
            val expected = source.copy(hash = fileHash(payload.inputStream()))
            val download = expected.copy(peer = b.id, outgoing = false, pin = peerB.pin)
            val destination = sender.transfers.file(download)
            try {
                recipient.transfers.dao.insert(expected)
                sender.transfers.dao.insert(download)
                chatClient(peerB, aTls).use { http ->
                    withTimeout(90000) {
                        val first = async(Dispatchers.IO) {
                            sender.transfers.receive(download, http, "https://127.0.0.1:$port", source.token)
                        }
                        sender.transfers.forPeer(peerB).first { rows -> rows.any { it.id == download.id && it.offset > 0 } }
                        server.stop(0, 0)
                        assertFalse(first.await())
                        val interrupted = requireNotNull(sender.transfers.dao.get(b.id, false, download.id))
                        assertEquals("retrying", interrupted.state)
                        assertTrue(interrupted.offset in RANGE_BLOCK.toLong() until source.size)
                        assertFalse(destination.exists())
                        val replacement = chatServer(listOf("127.0.0.1"), 0, serverTls(bTls, trusted::get), recipient.transfers) {}
                        try {
                            replacement.start(false)
                            val newPort = replacement.engine.resolvedConnectors().first().port
                            val firstRange = java.util.concurrent.atomic.AtomicReference<String>()
                            http.plugin(HttpSend).intercept { request ->
                                firstRange.compareAndSet(null, request.headers[HttpHeaders.Range])
                                execute(request)
                            }
                            assertTrue(sender.transfers.receive(interrupted, http, "https://127.0.0.1:$newPort", source.token))
                            assertTrue(firstRange.get().startsWith("bytes=${interrupted.offset}-"))
                            assertEquals(expected.hash, fileHash(destination.inputStream()))
                            assertEquals("done", sender.transfers.dao.get(b.id, false, download.id)?.state)
                        } finally { replacement.stop(0, 1000) }
                    }
                }
            } finally {
                destination.delete()
                java.io.File(destination.path + ".partial").delete()
                payload.delete()
            }
        } finally {
            server.stop(0, 1000); sender.close(); recipient.close()
            store.deleteEntry(a.id + ".issuer"); store.deleteEntry(a.id + ".tls"); store.deleteEntry(a.id + ".ecdsa")
            context.deleteDatabase(a.id); context.deleteDatabase(b.id)
        }
        Unit
    }
}

