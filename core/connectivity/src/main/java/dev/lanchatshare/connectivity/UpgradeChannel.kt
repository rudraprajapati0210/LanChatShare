package dev.lanchatshare.connectivity

import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Session-bound, ordered control records. Both ephemeral keys are covered by the signed BLE transcript. */
internal class UpgradeChannel(local: KeyPair, remote: ByteArray, transcript: ByteArray, private val role: Byte) {
    private val key: SecretKeySpec
    private var sent = 0L
    private var received = 0L

    init {
        val peer = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remote))
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(local.private)
            doPhase(peer, true)
            generateSecret()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(transcript, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        secret.fill(0)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        key = SecretKeySpec(mac.doFinal("LanChatShare Turbo v1".encodeToByteArray() + 1.toByte()), "AES")
        prk.fill(0)
    }

    private fun cipher(mode: Int, direction: Int, sequence: Long) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        val iv = ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array()
        init(mode, key, GCMParameterSpec(128, iv))
    }

    fun seal(bytes: ByteArray) = cipher(Cipher.ENCRYPT_MODE, role.toInt(), sent++).doFinal(bytes)
    fun open(bytes: ByteArray): ByteArray {
        val plain = cipher(Cipher.DECRYPT_MODE, 1 - role, received).doFinal(bytes)
        received++
        return plain
    }

    companion object {
        fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
    }
}
