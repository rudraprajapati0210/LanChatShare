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
    val key: String = "", val proof: String = "", val token: String? = null, val colour: Int = 0,
    val files: Boolean = false, val size: Long = 0, val hash: String = "", val mime: String = "")

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

internal suspend fun WebSocketSession.authenticateIncoming(local: String, trust: TrustStore, supportsFiles: (Boolean) -> Unit = {}): TrustedPeer {
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
    supportsFiles(hello.files)
    write(ChatFrame("ready", id = local, files = true))
    return peer
}

internal suspend fun WebSocketSession.authenticateOutgoing(local: String, peer: TrustedPeer, keys: KeyPair, token: ByteArray?, supportsFiles: (Boolean) -> Unit = {}) {
    val challenge = read()
    require(challenge.type == "challenge" && challenge.id == peer.id && decode.decode(challenge.text).size == 32)
    val signature = Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private); update(proofBytes(peer.id, local, challenge.text)); sign()
    }
    write(ChatFrame("identity", id = local, files = true, key = b64.encodeToString(keys.public.encoded),
        proof = b64.encodeToString(signature), token = token?.let(b64::encodeToString)))
    val ready = read()
    require(ready.type == "ready" && ready.id == peer.id)
    supportsFiles(ready.files)
}

internal suspend fun WebSocketSession.chat(peer: TrustedPeer, trust: TrustStore, profile: Flow<Profile>,
    supportsFiles: Boolean = true, fetch: (suspend (Transfer, String) -> Unit)? = null) = coroutineScope {
    val ws = this@chat
    val offers = launch { if (supportsFiles) ws.announceTransfers(peer, trust.transfers) }
    val downloads = mutableMapOf<String, Job>()
    val sender = launch {
        while (isActive) {
            val batch = trust.messages.pending(peer.id).first { it.isNotEmpty() }
            batch.forEach { ws.write(ChatFrame("text", id = it.id, text = it.text)) }
            val ids = batch.mapTo(mutableSetOf()) { it.id }
            val acknowledged = withTimeoutOrNull(20000) {
                trust.messages.pending(peer.id).first { rows -> rows.none { it.id in ids } }
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
                "file.offer" -> trust.transfers.offer(peer, event)
                "file.want", "file.decline" -> {
                    val row = trust.transfers.dao.get(peer.id, event.type != "file.decline" || event.key != "cancel-sent", event.id)
                    if (row?.pin == peer.pin) {
                        if (event.type == "file.decline" && event.key.startsWith("cancel-")) trust.transfers.action(row,"cancelled")
                        else if (event.type == "file.decline") trust.transfers.dao.change(peer.id, true, row.id, "offered", "declined")
                        else if (row.state == "offered") trust.transfers.dao.change(peer.id, true, row.id, "offered", "active")
                        else if (row.state == "active") ws.write(ChatFrame("file.grant", id = row.id, token = row.token))
                    }
                }
                "file.saved" -> trust.transfers.receipt(peer, event.id)
                "file.grant" -> {
                    val row = trust.transfers.dao.get(peer.id, false, event.id)
                    val token = event.token
                    if (row?.pin == peer.pin && row.state in listOf("active","retrying") && token != null && fetch != null && downloads[row.id]?.isActive != true) {
                        downloads.entries.removeAll { it.value.isCompleted }
                        downloads[row.id] = launch { fetch(row, token) }
                    }
                }
                "text" -> {
                    require(UUID.fromString(event.id).toString() == event.id && event.text.isNotBlank() && event.text.length <= 4096)
                    trust.saveIncoming(peer, ChatMessage(peer.id, false, event.id, event.text, System.currentTimeMillis(), true))
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
    } finally { offers.cancel(); downloads.values.forEach { it.cancel() }; sender.cancel(); updates.cancel(); pins.cancel() }
}
