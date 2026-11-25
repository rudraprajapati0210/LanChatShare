package dev.lanchatshare.connectivity

import org.bouncycastle.asn1.x500.X500Name
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.cert.*
import java.util.*

class TlsBindingTest {
    companion object {
        private val id = UUID.randomUUID().toString()
        private val now = 1770000000000L
        private val identity = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        private val name = X500Name("CN=existing-identity")
        private val root = certificate(name, name, identity.public, identity.private, now - 86400000, now + 10 * TLS_PERIOD, null, true)
        private fun rsa() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        private val binding = certify(id, identity.private, root, rsa(), rsa(), now)
        private val pins = mapOf(id to Base64.getEncoder().encodeToString(digest(identity.public.encoded)))
    }
    @Test fun pinnedIdentitySurvivesTransportRotation() {
        val replacement = certify(id, identity.private, root, rsa(), rsa(), now + TLS_PERIOD)
        assertFalse(binding.chain[0].publicKey.encoded.contentEquals(replacement.chain[0].publicKey.encoded))
        assertEquals(id, verifyBinding(binding.chain, pins, Date(now)))
        assertEquals(id, verifyBinding(replacement.chain, pins, Date(now + TLS_PERIOD)))
    }
    @Test fun wrongPinAndRevokedPeerRejected() {
        for (untrusted in listOf(emptyMap(), mapOf(id to "changed"), mapOf(UUID.randomUUID().toString() to pins.getValue(id)))) {
            assertThrows(CertificateException::class.java) { verifyBinding(binding.chain, untrusted, Date(now)) }
        }
    }
    @Test fun expiredAndPrematureBindingRejected() {
        for (time in listOf(binding.chain[0].notAfter.time + 1, binding.chain[0].notBefore.time - 1)) {
            assertThrows(CertificateException::class.java) { verifyBinding(binding.chain, pins, Date(time)) }
        }
    }
    @Test fun tamperedLeafAndTamperedDelegationRejected() {
        for (index in 0..1) {
            val altered = binding.chain.copyOf()
            val der = altered[index].encoded
            der[der.lastIndex] = (der.last().toInt() xor 1).toByte()
            altered[index] = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
            assertThrows(CertificateException::class.java) { verifyBinding(altered, pins, Date(now)) }
        }
    }
}
