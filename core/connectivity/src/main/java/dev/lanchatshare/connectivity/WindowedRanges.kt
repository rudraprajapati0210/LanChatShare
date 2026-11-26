package dev.lanchatshare.connectivity

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import java.util.concurrent.CancellationException
import kotlinx.coroutines.*
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

internal const val WINDOW_BYTES = 4 * 1024 * 1024
internal const val RANGE_FORMAT = "application/vnd.lanchatshare.crc-range"
internal class ChunkMismatch : IOException("Range block checksum mismatch")

internal fun encodedSize(length: Long) = length + 4 * ((length + RANGE_BLOCK - 1) / RANGE_BLOCK)

internal suspend fun writeBlocks(input: RandomAccessFile, output: OutputStream, length: Long, allow: suspend () -> Boolean) {
    val buffer = ByteArray(RANGE_BLOCK)
    val crc = CRC32()
    var sent = 0L
    while (sent < length) {
        if (sent % (4L * RANGE_BLOCK) == 0L && !allow()) throw IOException("Transfer no longer active")
        val n = minOf(length-sent, buffer.size.toLong()).toInt()
        input.readFully(buffer,0,n)
        crc.reset(); crc.update(buffer,0,n)
        val sum = crc.value
        output.write(byteArrayOf((sum ushr 24).toByte(),(sum ushr 16).toByte(),(sum ushr 8).toByte(),sum.toByte()))
        output.write(buffer,0,n)
        sent += n
    }
}

internal suspend fun ByteReadChannel.readBlock(buffer: ByteArray, length: Int, checked: Boolean, progress: () -> Unit) {
    readBlock(buffer,length,checked,progress,null)
}

internal suspend fun ByteReadChannel.readBlock(buffer: ByteArray, length: Int, checked: Boolean, progress: () -> Unit, timings: RangeTimings?) {
    val header = ByteArray(4)
    if (checked) readFully(header)
    var offset = 0
    while (offset < length) {
        val n = readAvailable(buffer,offset,length-offset)
        if (n == -1) throw java.io.EOFException("Range ended inside block")
        if (n > 0) { offset += n; progress() }
    }
    if (checked) {
        val started = if (timings != null) System.nanoTime() else 0L
        val expected = header.fold(0L) { value,b -> (value shl 8) or (b.toLong() and 255) }
        if (CRC32().apply { update(buffer,0,length) }.value != expected) throw ChunkMismatch()
        timings?.record("crc",started)
    }
}

/** Times idle network reads, including connection setup. Total transfer time is unrestricted. */
internal suspend fun <T> watchRange(idleMs: Long = 10000, read: suspend (() -> Unit) -> T): T = coroutineScope {
    val touch = AtomicLong(System.nanoTime())
    val task = async { read { touch.set(System.nanoTime()) } }
    val alarm = launch {
        while (isActive) {
            delay((idleMs / 4).coerceAtLeast(1))
            if ((System.nanoTime()-touch.get())/1_000_000 >= idleMs) {
                task.cancel(CancellationException("LAN range idle")); break
            }
        }
    }
    try { task.await() } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        throw IOException("LAN range interrupted",e)
    } finally { alarm.cancel() }
}

