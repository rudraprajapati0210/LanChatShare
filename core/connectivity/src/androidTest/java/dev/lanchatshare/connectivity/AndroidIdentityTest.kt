package dev.lanchatshare.connectivity

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import java.security.Signature
import java.util.UUID

class AndroidIdentityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun keySurvivesReopenAndMatchesTlsCertificate() {
        val first = identityKeys()
        val reopened = identityKeys()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val certificate = store.getCertificate(IDENTITY_ALIAS)
        assertArrayEquals(first.public.encoded, certificate.publicKey.encoded)
        assertArrayEquals(reopened.public.encoded, first.public.encoded)
        assertNull(first.private.encoded)
        val nonce = UUID.randomUUID().toString().encodeToByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(first.private)
        signer.update(nonce)
        val proof = signer.sign()
        signer.initVerify(certificate.publicKey)
        signer.update(nonce)
        assertTrue(signer.verify(proof))
    }

    @Test fun savedPinsSurviveDatabaseReopen() = runBlocking {
        val file = "test-" + UUID.randomUUID() + ".db"
        val peer = TrustedPeer(UUID.randomUUID().toString(), "রুদ্র", 2, "example-pin")
        try {
            val writer = Room.databaseBuilder(context, TrustDatabase::class.java, file).build()
            try { writer.peers().save(peer) } finally { writer.close() }
            val reader = Room.databaseBuilder(context, TrustDatabase::class.java, file).build()
            try { assertEquals(peer, reader.peers().observe().first().single()) } finally { reader.close() }
        } finally { context.deleteDatabase(file) }
        Unit
    }
    @Test fun sessionTokenCanBeConsumedOnlyOnceAndOnlyByIntendedPeer() {
        val store = TrustStore(context)
        val token = ByteArray(32) { it.toByte() }
        store.issue("first", token)
        assertFalse(store.consume("second", token))
        assertFalse(store.consume("first", ByteArray(32)))
        assertTrue(store.consume("first", token))
        assertFalse(store.consume("first", token))
    }
}
