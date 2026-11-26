package dev.lanchatshare.connectivity

import org.junit.Assert.*
import org.junit.Test

class UpgradeChannelTest {
    @Test fun credentialsRequireTheSameTranscriptDirectionAndSequence() {
        val a = UpgradeChannel.ephemeral()
        val b = UpgradeChannel.ephemeral()
        val transcript = digest("signed hellos".encodeToByteArray())
        val sender = UpgradeChannel(a, b.public.encoded, transcript, 0)
        val receiver = UpgradeChannel(b, a.public.encoded, transcript, 1)
        val record = sender.seal("temporary passphrase".encodeToByteArray())
        val altered = record.copyOf().apply { this[0] = this[0].inc() }
        assertTrue(runCatching { receiver.open(altered) }.isFailure)
        assertTrue(runCatching { sender.open(record) }.isFailure)
        assertTrue(runCatching { UpgradeChannel(b, a.public.encoded, digest(transcript), 1).open(record) }.isFailure)
        assertEquals("temporary passphrase", receiver.open(record).decodeToString())
        assertTrue(runCatching { receiver.open(record) }.isFailure)
        assertEquals("joined", sender.open(receiver.seal("joined".encodeToByteArray())).decodeToString())
    }
}
