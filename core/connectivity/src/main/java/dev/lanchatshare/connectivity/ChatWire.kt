package dev.lanchatshare.connectivity

import dev.lanchatshare.data.Profile
import dev.lanchatshare.data.validatedName
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

@Serializable
internal data class ChatFrame(val type: String, val v: Int = 1, val id: String = "", val text: String = "",
    val key: String = "", val proof: String = "", val token: String? = null, val colour: Int = 0)

private val wire = Json { ignoreUnknownKeys = true }
private val b64 = Base64.getEncoder()
private val decode = Base64.getDecoder()
internal suspend fun WebSocketSession.write(frame: ChatFrame) = send(Frame.Text(wire.encodeToString(frame)))
internal suspend fun WebSocketSession.read(): ChatFrame {
    val frame = incoming.receive() as? Frame.Text ?: error("Expected a text frame")
    require(frame.data.size <= 32768)
    return wire.decodeFromString<ChatFrame>(frame.readText()).also { require(it.v == 1) }
}

private fun proofBytes(server: String, client: String, challenge: String) =
    "LanChatShare chat v1/$server/$client/$challenge".encodeToByteArray()

internal suspend fun WebSocketSession.authenticateIncoming(local: String, trust: TrustStore): TrustedPeer {
    val challenge = b64.encodeToString(ByteArray(32).also(SecureRandom()::nextBytes))
    write(ChatFrame("challenge", id = local, text = challenge))
    val hello = read()
    require(hello.type == "identity" && hello.id != local)
    val peer = requireNotNull(trust.dao.find(hello.id))
    val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode.decode(hello.key)))
    require(b64.encodeToString(digest(key.encoded)) == peer.pin)
    require(Signature.getInstance("SHA256withECDSA").run {
        initVerify(key); update(proofBytes(local, peer.id, challenge)); verify(decode.decode(hello.proof))
    })
    require(trust.checkHandoff(peer.id, hello.token?.let(decode::decode)))
    write(ChatFrame("ready", id = local))
    return peer
}

internal suspend fun WebSocketSession.authenticateOutgoing(local: String, peer: TrustedPeer, keys: KeyPair, token: ByteArray?) {
    val challenge = read()
    require(challenge.type == "challenge" && challenge.id == peer.id && decode.decode(challenge.text).size == 32)
    val signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private); update(proofBytes(peer.id, local, challenge.text)); sign()
    }
    write(ChatFrame("identity", id = local, key = b64.encodeToString(keys.public.encoded),
        proof = b64.encodeToString(signature), token = token?.let(b64::encodeToString)))
    val ready = read()
    require(ready.type == "ready" && ready.id == peer.id)
}

internal suspend fun WebSocketSession.chat(peer: TrustedPeer, trust: TrustStore, profile: Flow<Profile>) = coroutineScope {
    val ws = this@chat
    val sender = launch {
        while (isActive) {
            val next = trust.messages.next(peer.id).filterNotNull().first()
            ws.write(ChatFrame("text", id = next.id, text = next.text))
            val acknowledged = withTimeoutOrNull(20000) {
                trust.messages.next(peer.id).first { it?.id != next.id }
                true
            } ?: false
            if (!acknowledged) {
                ws.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Delivery timed out"))
                break
            }
        }
    }
    val updates = launch { profile.distinctUntilChanged().collect { ws.write(ChatFrame("profile", text = it.name, colour = it.colour)) } }
    val pins = launch {
        trust.peers.first { list -> list.none { it.id == peer.id && it.pin == peer.pin } }
        ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Pairing changed"))
    }
    try {
        while (isActive) {
            val event = ws.read()
            when (event.type) {
                "text" -> {
                    require(UUID.fromString(event.id).toString() == event.id && event.text.isNotBlank() && event.text.length <= 4096)
                    trust.messages.insert(ChatMessage(peer.id, false, event.id, event.text, System.currentTimeMillis(), true))
                    ws.write(ChatFrame("ack", id = event.id))
                }
                "ack" -> trust.messages.acknowledge(peer.id, event.id)
                "profile" -> {
                    require(validatedName(event.text) == event.text && event.colour in 0..3)
                    trust.dao.updateProfile(peer.id, peer.pin, event.text, event.colour)
                }
                else -> error("Unsupported chat event")
            }
        }
    } finally { sender.cancel(); updates.cancel(); pins.cancel() }
}
