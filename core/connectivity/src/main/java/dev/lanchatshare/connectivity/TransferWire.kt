package dev.lanchatshare.connectivity

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.RandomAccessFile

internal const val RANGE_BLOCK = 256 * 1024
internal fun Route.fileRanges(files: Transfers) {
    get("/v1/files/{id}") {
        val auth = call.request.headers[HttpHeaders.Authorization].orEmpty()
        val row = if (auth.startsWith("Bearer ")) files.dao.granted(call.parameters["id"].orEmpty(), auth.removePrefix("Bearer ")) else null
        if (row == null) { call.respond(HttpStatusCode.Forbidden); return@get }
        val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(call.request.headers[HttpHeaders.Range].orEmpty())
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val end = match?.groupValues?.get(2)?.toLongOrNull() ?: -1
        if (start < 0 || end < start || end >= row.size || end - start >= RANGE_BLOCK) {
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable); return@get
        }
        val bytes = withContext(Dispatchers.IO) {
            RandomAccessFile(files.file(row), "r").use { file ->
                file.seek(start)
                ByteArray((end - start + 1).toInt()).also(file::readFully)
            }
        }
        call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/${row.size}")
        call.respondBytes(bytes, ContentType.Application.OctetStream, HttpStatusCode.PartialContent)
        files.dao.progress(row.peer, true, row.id, end + 1)
    }
}

internal suspend fun Transfers.receive(row: Transfer, client: HttpClient, origin: String, token: String) = withContext(Dispatchers.IO) {
    val lock = downloadLock[(row.id.hashCode() and Int.MAX_VALUE) % downloadLock.size]
    lock.lock()
    try {
        val target = file(row)
        target.parentFile!!.mkdirs()
        val part = File(target.path + ".partial")
        suspend fun active() = dao.get(row.peer, false, row.id)?.let { it.pin == row.pin && it.state == "active" } == true
        if (!active()) return@withContext
        if (!target.exists()) {
            RandomAccessFile(part, "rw").use { out ->
                var start = out.length()
                while (start < row.size && active()) {
                    val end = start + minOf(row.size - start, RANGE_BLOCK.toLong()) - 1
                    val response = withTimeout(30000) { client.get("$origin/v1/files/${row.id}") {
                        header(HttpHeaders.Authorization, "Bearer $token")
                        header(HttpHeaders.Range, "bytes=$start-$end")
                    } }
                    if (response.status == HttpStatusCode.Forbidden) return@withContext
                    check(response.status == HttpStatusCode.PartialContent &&
                        response.headers[HttpHeaders.ContentRange] == "bytes $start-$end/${row.size}")
                    val bytes = response.body<ByteArray>()
                    check(bytes.size.toLong() == end - start + 1)
                    out.seek(start); out.write(bytes); out.fd.sync()
                    start = end + 1
                    dao.progress(row.peer, false, row.id, start)
                }
            }
            if (!active()) return@withContext
            if (part.length() != row.size || fileHash(part.inputStream()) != row.hash) {
                part.delete()
                error("Attachment verification failed")
            }
            check(part.renameTo(target))
        }
        dao.change(row.peer, false, row.id, "active", "done")
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        dao.change(row.peer, false, row.id, "active", "failed")
    } catch (_: Exception) {
        dao.change(row.peer, false, row.id, "active", "failed")
    } finally { lock.unlock() }
}

internal suspend fun WebSocketSession.announceTransfers(peer: TrustedPeer, files: Transfers) {
    files.dao.observe(peer.id, peer.pin).map { rows -> rows.mapNotNull { row ->
        val type = if (row.outgoing) when (row.state) {
            "offered" -> "file.offer"
            "active" -> "file.grant"
            else -> null
        } else when (row.state) {
            "active" -> "file.want"
            "done" -> "file.saved"
            "declined" -> "file.decline"
            else -> null
        }
        type?.let { ChatFrame(it, id = row.id, text = row.name, size = row.size, hash = row.hash,
            mime = row.mime, token = if (it == "file.grant") row.token else null) }
    } }.distinctUntilChanged().collect { frames -> frames.forEach { write(it) } }
}

