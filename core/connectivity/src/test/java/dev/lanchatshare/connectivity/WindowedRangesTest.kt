package dev.lanchatshare.connectivity

import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.random.Random

class WindowedRangesTest {
    @Test fun blockFramesPreserveBytesAndRejectDamage() = runBlocking {
        val bytes = Random(314).nextBytes(RANGE_BLOCK + 91)
        val f = File.createTempFile("block","test")
        try {
            f.writeBytes(bytes)
            val sink = ByteArrayOutputStream()
            RandomAccessFile(f,"r").use { writeBlocks(it,sink,f.length()) { true } }
            val encoded = sink.toByteArray()
            assertEquals(encodedSize(f.length()),encoded.size.toLong())
            val input = ByteReadChannel(encoded)
            val first = ByteArray(RANGE_BLOCK)
            input.readBlock(first,first.size,true) {}
            assertArrayEquals(bytes.copyOf(first.size),first)
            val tail = ByteArray(91)
            input.readBlock(tail,tail.size,true) {}
            assertArrayEquals(bytes.copyOfRange(first.size,bytes.size),tail)
            encoded[9] = encoded[9].inc()
            assertTrue(runCatching { ByteReadChannel(encoded).readBlock(first,first.size,true) {} }.exceptionOrNull() is ChunkMismatch)
            assertTrue(runCatching { ByteReadChannel(encoded.copyOf(120)).readBlock(first,first.size,true) {} }.exceptionOrNull() is IOException)
        } finally { f.delete() }
    }
    @Test fun watchdogAllowsProgressAndInterruptsIdleRead() = runBlocking {
        val completed = watchRange(250) { heartbeat ->
            repeat(10) { delay(40); heartbeat() }
            true
        }
        assertTrue(completed)
        val stalled = runCatching { watchRange(100) { delay(5000) } }.exceptionOrNull()
        assertTrue(stalled is IOException)
    }
}
