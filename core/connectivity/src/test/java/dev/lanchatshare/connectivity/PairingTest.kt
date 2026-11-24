package dev.lanchatshare.connectivity

import dev.lanchatshare.data.Profile
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val a = Profile("Rudra 🐚", UUID.randomUUID().toString(), 1)
    private val b = Profile("রুদ্র", UUID.randomUUID().toString(), 2)
    private fun key() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private val aKey = key()
    private val bKey = key()

    private fun exchange(
        pinA: String? = null, pinB: String? = null, responderKeys: java.security.KeyPair = bKey,
        approveA: (Comparison) -> Boolean, approveB: (Comparison) -> Boolean
    ) = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            suspend fun run(socket: Socket, initiator: Boolean): Result<Verified> = socket.use {
                it.soTimeout = 5000
                runCatching {
                    pair(Frames(it.getInputStream(), it.getOutputStream()), initiator,
                        if (initiator) a else b, if (initiator) aKey else responderKeys,
                        { if (initiator) pinA else pinB }, listOf(WifiEndpoint("wKgBBQ==", LAN_PORT)), { if (initiator) approveA(it) else approveB(it) })
                }
            }
            val receiver = async(Dispatchers.IO) { run(server.accept(), false) }
            val sender = withContext(Dispatchers.IO) { run(Socket(server.inetAddress, server.localPort), true) }
            sender to receiver.await()
        }
    }

    @Test fun bothScreensMatchAndOnlyMutualPinsSkipVerification() {
        var left = ""
        var right = ""
        val (sender, receiver) = exchange(approveA = { left = it.digits; true }, approveB = { right = it.digits; true })
        val atA = sender.getOrThrow()
        val atB = receiver.getOrThrow()
        assertTrue(left.matches(Regex("[0-9]{6}")))
        assertEquals(left, right)
        assertEquals("192.168.1.5:47831", atA.endpoints.single().display())
        assertEquals(b.name, atA.peer.name)
        assertEquals(a.id, atB.peer.id)
        assertArrayEquals(atA.token, atB.token)
        val again = exchange(atA.peer.pin, atB.peer.pin, approveA = { error("unexpected prompt") }, approveB = { error("unexpected prompt") })
        assertFalse(again.first.getOrThrow().token.contentEquals(atA.token))
        assertEquals(atB.peer, again.second.getOrThrow().peer)
        val changed = exchange("different", atB.peer.pin, approveA = { it.changed }, approveB = { !it.changed })
        assertTrue(changed.first.isSuccess && changed.second.isSuccess)
    }

    @Test fun mismatchedSigningKeyCannotProveTheAnnouncedIdentity() {
        val wrong = java.security.KeyPair(bKey.public, key().private)
        var askedSender = false
        val result = exchange(responderKeys = wrong, approveA = { askedSender = true; true }, approveB = { true })
        assertTrue(result.first.isFailure && result.second.isFailure)
        assertFalse(askedSender)
    }

    @Test fun routeHintsRejectInvalidAddressesAndPorts() {
        for (host in listOf("AAAAAA==", "fwAAAQ==", "4AAAAQ==", "bad:length")) {
            assertThrows(IllegalArgumentException::class.java) { WifiEndpoint(host, LAN_PORT).address() }
        }
        assertThrows(IllegalArgumentException::class.java) { WifiEndpoint("wKgBBQ==", 65536).address() }
    }

    @Test fun decliningOnEitherDevicePreventsVerification() {
        listOf(true, false).forEach { declineSender ->
            val (sender, receiver) = exchange(approveA = { !declineSender }, approveB = { declineSender })
            assertTrue(sender.isFailure && receiver.isFailure)
        }
    }

    @Test fun commitmentMismatchStopsBeforeConfirmation() = runBlocking {
        val wire = ByteArrayOutputStream()
        Frames(InputStream.nullInputStream(), wire).apply { send(ByteArray(32)); send("altered".encodeToByteArray()) }
        var asked = false
        val result = runCatching { pair(Frames(wire.toByteArray().inputStream(), ByteArrayOutputStream()), true,
            a, aKey, { null }, emptyList(), { asked = true; true }) }
        assertTrue(result.isFailure)
        assertFalse(asked)
    }

    @Test fun invalidFrameSizesRejectedBeforeAllocation() {
        for (length in listOf(0, -1, 4097, Int.MAX_VALUE)) {
            val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(length) }
            assertThrows(IllegalArgumentException::class.java) {
                Frames(bytes.toByteArray().inputStream(), ByteArrayOutputStream()).receive()
            }
        }
    }

    @Test fun advertisementKeepsUtf8BoundariesAndFitsLegacyScanResponse() {
        val bytes = beacon(a.copy(name = "🌼🌼🌼🐚"), 255)
        assertTrue(bytes.size <= 13)
        assertEquals(255, bytes[1].toInt() and 255)
        assertEquals("🌼🌼", bytes.copyOfRange(4, bytes.size).decodeToString(throwOnInvalidSequence = true))
    }
}

