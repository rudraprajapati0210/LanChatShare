package dev.lanchatshare.connectivity

import dev.lanchatshare.data.Profile
import dev.lanchatshare.data.validatedName
import java.io.*
import java.nio.ByteBuffer
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
private val random = SecureRandom()
private val encoder = Base64.getEncoder()
private val decoder = Base64.getDecoder()

internal fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
private fun fresh() = ByteArray(32).also(random::nextBytes)

@Serializable
internal data class Hello(val version: Int, val id: String, val name: String, val colour: Int, val key: String, val nonce: String,
    val endpoints: List<WifiEndpoint> = emptyList(), val spkiSha256: String? = null,
    val certificate: String? = null, val certificateSha256: String? = null)

internal class Frames(input: InputStream, output: OutputStream) {
    private val reader = DataInputStream(input)
    private val writer = DataOutputStream(output)
    fun send(value: ByteArray) {
        require(value.size in 1..4096)
        writer.writeInt(value.size)
        writer.write(value)
        writer.flush()
    }
    fun receive(): ByteArray {
        val length = reader.readInt()
        require(length in 1..4096)
        return ByteArray(length).also(reader::readFully)
    }
    fun exchange(bytes: ByteArray): ByteArray { send(bytes); return receive() }
}

internal data class Verified(val peer: TrustedPeer, val token: ByteArray, val endpoints: List<WifiEndpoint>)
internal data class Comparison(val name: String, val id: String, val digits: String, val changed: Boolean)

internal fun checkCertificate(encoded: String?, fingerprint: String?, key: PublicKey) {
    if (encoded == null) { require(fingerprint == null); return }
    val der = decoder.decode(encoded)
    require(encoder.encodeToString(digest(der)) == fingerprint)
    val certificate = java.security.cert.CertificateFactory.getInstance("X.509")
        .generateCertificate(der.inputStream())
    require(MessageDigest.isEqual(certificate.publicKey.encoded, key.encoded))
}

private fun signed(key: PrivateKey, bytes: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
    initSign(key); update(bytes); sign()
}
private fun verify(key: PublicKey, bytes: ByteArray, signature: ByteArray) {
    require(Signature.getInstance("SHA256withECDSA").run { initVerify(key); update(bytes); verify(signature) })
}

// All records travel inside Android's authenticated, encrypted LE CoC.
// Commit before reveal so neither endpoint can adapt its nonce to the other's hello.
internal suspend fun pair(
    frames: Frames, initiator: Boolean, profile: Profile, keys: KeyPair,
    lookupPin: suspend (String) -> String?, endpoints: List<WifiEndpoint> = emptyList(), certificate: ByteArray? = null,
    confirm: suspend (Comparison) -> Boolean
): Verified {
    val role = if (initiator) 0.toByte() else 1.toByte()
    val otherRole = (1 - role).toByte()
    val mine = Hello(1, profile.id, profile.name, profile.colour, encoder.encodeToString(keys.public.encoded), encoder.encodeToString(fresh()),
        endpoints, encoder.encodeToString(digest(keys.public.encoded)), certificate?.let(encoder::encodeToString),
        certificate?.let { encoder.encodeToString(digest(it)) })
    val local = json.encodeToString(mine).encodeToByteArray()
    val commitment = frames.exchange(digest(byteArrayOf(role) + local))
    val remote = frames.exchange(local)
    require(MessageDigest.isEqual(commitment, digest(byteArrayOf(otherRole) + remote)))
    val hello = json.decodeFromString<Hello>(remote.decodeToString(throwOnInvalidSequence = true))
    require(hello.version == 1 && UUID.fromString(hello.id).toString() == hello.id && hello.id != profile.id)
    require(validatedName(hello.name) == hello.name && hello.colour in 0..3 && decoder.decode(hello.nonce).size == 32)
    val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decoder.decode(hello.key)))
    val pin = encoder.encodeToString(digest(publicKey.encoded))
    require(hello.spkiSha256 == null || hello.spkiSha256 == pin)
    checkCertificate(hello.certificate, hello.certificateSha256, publicKey)
    require(hello.endpoints.size <= 8)
    hello.endpoints.forEach { it.address() }
    val transcript = digest("LanChatShare pairing v1".encodeToByteArray() +
        if (initiator) digest(local) + digest(remote) else digest(remote) + digest(local))
    val signature = frames.exchange(signed(keys.private, transcript + role))
    verify(publicKey, transcript + otherRole, signature)
    val saved = lookupPin(hello.id)
    val known = if (saved == pin) 1.toByte() else 0.toByte()
    val status = frames.exchange(byteArrayOf(known))
    require(status.size == 1 && status[0] in 0..1)
    if (known.toInt() == 0 || status[0].toInt() == 0) {
        val number = (ByteBuffer.wrap(transcript).int.toLong() and 0xffffffffL) % 1_000_000
        require(confirm(Comparison(hello.name, hello.id, number.toString().padStart(6, '0'), saved != null && saved != pin)))
    }
    val approval = "confirmed".encodeToByteArray() + transcript
    verify(publicKey, approval + otherRole, frames.exchange(signed(keys.private, approval + role)))
    val token = if (initiator) fresh().also(frames::send) else frames.receive()
    require(token.size == 32)
    return Verified(TrustedPeer(hello.id, hello.name, hello.colour, pin, hello.certificateSha256), token, hello.endpoints)
}

