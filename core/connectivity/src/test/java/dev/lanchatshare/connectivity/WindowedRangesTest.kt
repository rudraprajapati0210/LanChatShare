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
    @Test fun streamsAcrossTwoGiBWithABoundedRangeBuffer() = runBlocking {
        val path = java.nio.file.Files.createTempDirectory("lcs-large").resolve("payload")
        java.nio.file.Files.newByteChannel(path, setOf(
            java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.SPARSE,
        )).close()
        val length = (1L shl 31) + 1
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            RandomAccessFile(path.toFile(), "rw").use { input ->
                input.seek(length - 1)
                input.write('X'.code)
                input.seek(0)
                val block = ByteArray(RANGE_BLOCK)
                val response = ByteArrayOutputStream(encodedSize(WINDOW_BYTES.toLong()).toInt())
                while (input.filePointer < length) {
                    val count = minOf(WINDOW_BYTES.toLong(), length - input.filePointer)
                    response.reset()
                    writeBlocks(input, response, count) { true }
                    val channel = ByteReadChannel(response.toByteArray())
                    var remaining = count
                    while (remaining > 0) {
                        val n = minOf(block.size.toLong(), remaining).toInt()
                        channel.readBlock(block, n, true) {}
                        digest.update(block, 0, n)
                        remaining -= n
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            assertEquals("a34d7bd4246865e6ea0b78baef6cd57db17310b437fe1b088bcfe6ed9e80afc7", actual)
        } finally {
            java.nio.file.Files.deleteIfExists(path)
            java.nio.file.Files.deleteIfExists(path.parent)
        }
    }
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
