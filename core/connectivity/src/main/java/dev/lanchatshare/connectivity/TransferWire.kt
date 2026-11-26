package dev.lanchatshare.connectivity

import io.ktor.client.HttpClient
import io.ktor.client.statement.*
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
        val streamed = call.request.headers[HttpHeaders.Accept] == RANGE_FORMAT
        val limit = if (streamed) WINDOW_BYTES else RANGE_BLOCK
        val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(call.request.headers[HttpHeaders.Range].orEmpty())
        val start = match?.groupValues?.get(1)?.toLongOrNull() ?: -1
        val end = match?.groupValues?.get(2)?.toLongOrNull() ?: -1
        if (start < 0 || end < start || end >= row.size || end - start >= limit) {
            call.respond(HttpStatusCode.RequestedRangeNotSatisfiable); return@get
        }
        files.duringTransfer {
            if (streamed) {
                val length = end - start + 1
                call.response.headers.append(HttpHeaders.ContentRange, "bytes $start-$end/${row.size}")
                call.respondOutputStream(ContentType.parse(RANGE_FORMAT), HttpStatusCode.PartialContent, encodedSize(length)) {
                    val sink = this
                    withContext(Dispatchers.IO) {
                        RandomAccessFile(files.file(row),"r").use { source ->
                            source.seek(start)
                            writeBlocks(source,sink,length,files.timings(row)) { files.dao.granted(row.id,auth.removePrefix("Bearer ")) != null }
                        }
                    }
                }
                val timings = files.timings(row)
                if (end+1 == row.size) timings?.payloadSent()
                val saving = if (timings != null) System.nanoTime() else 0L
                files.dao.progress(row.peer,true,row.id,end+1)
                timings?.record("roomProgress",saving)
                return@duringTransfer
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
}

internal suspend fun Transfers.receive(
    row: Transfer,
    client: HttpClient,
    origin: String,
    token: String,
    preferredWindow: Int = RANGE_BLOCK,
    streams: Int = 1,
    timings: RangeTimings? = timings(row),
) = withContext(Dispatchers.IO) {
    require(preferredWindow in RANGE_BLOCK..WINDOW_BYTES && preferredWindow % RANGE_BLOCK == 0)
    require(streams in 1..4)
    val lock = downloadLock[(row.id.hashCode() and Int.MAX_VALUE) % downloadLock.size]
    lock.lock()
    try {
        duringTransfer {
            val target = file(row)
            target.parentFile!!.mkdirs()
            val part = File(target.path + ".partial")
            suspend fun active() = dao.get(row.peer, false, row.id)?.let { it.pin == row.pin && it.state == "active" } == true
            dao.change(row.peer,false,row.id,"retrying","active")
            if (!active()) return@duringTransfer true
            if (!target.exists()) {
                RandomAccessFile(part, "rw").use { out ->
                    val durable = dao.get(row.peer, false, row.id)?.offset ?: return@duringTransfer true
                    val present = minOf(out.length(), durable, row.size)
                    var start = if (present == row.size) present else present - present % RANGE_BLOCK
                    if (out.length() != start) out.setLength(start)
                    if (durable != start) dao.checkpoint(row.peer, row.id, start)
                    if (row.size - start > target.parentFile!!.usableSpace) {
                        dao.change(row.peer, false, row.id, "active", "storage")
                        return@duringTransfer true
                    }
                    val buffer = ByteArray(RANGE_BLOCK)
                    var window = RANGE_BLOCK // First request also negotiates compatibility with older peers.
                    var supportsChecks = false
                    while (start < row.size && active()) {
                        val begin = start
                        val length = minOf(row.size-begin,window.toLong())
                        val end = begin + length - 1
                        var diskError = false
                        try {
                            if (supportsChecks && streams > 1) {
                                val pieces = coroutineScope {
                                    (0 until streams).map { begin + it * window.toLong() }.takeWhile { it < row.size }.map { offset ->
                                        async {
                                            val count = minOf(window.toLong(), row.size-offset).toInt()
                                            readSegment(client,origin,row.id,token,offset,count,row.size)
                                        }
                                    }.awaitAll()
                                }
                                for (piece in pieces) {
                                    if (!active()) return@duringTransfer true
                                    try { out.seek(start); out.write(piece); out.fd.sync() }
                                    catch (e: java.io.IOException) { diskError = true; throw e }
                                    start += piece.size
                                    dao.progress(row.peer,false,row.id,start)
                                }
                                continue
                            }
                            val allowed = watchRange { heartbeat ->
                                val requested = if (timings != null) System.nanoTime() else 0L
                                client.prepareGet("$origin/v1/files/${row.id}") {
                                    header(HttpHeaders.Authorization,"Bearer $token")
                                    header(HttpHeaders.Range,"bytes=$begin-$end")
                                    header(HttpHeaders.Accept,RANGE_FORMAT)
                                }.execute { response ->
                                    timings?.record("responseHeaders",requested)
                                    if (response.status == HttpStatusCode.Forbidden) return@execute false
                                    val checked = response.headers[HttpHeaders.ContentType]?.substringBefore(';') == RANGE_FORMAT
                                    check(response.status == HttpStatusCode.PartialContent &&
                                        response.headers[HttpHeaders.ContentRange] == "bytes $begin-$end/${row.size}" &&
                                        response.headers[HttpHeaders.ContentLength]?.toLongOrNull() == if (checked) encodedSize(length) else length)
                                    val channel = response.bodyAsChannel()
                                    while (start <= end) {
                                        val n = minOf(end-start+1,buffer.size.toLong()).toInt()
                                        val reading = if (timings != null) System.nanoTime() else 0L
                                        channel.readBlock(buffer,n,checked,heartbeat,timings)
                                        timings?.record("bodyIncludingCrc",reading)
                                        try {
                                            val writing = if (timings != null) System.nanoTime() else 0L
                                            out.seek(start); out.write(buffer,0,n); start += n
                                            timings?.record("diskWrite",writing)
                                            if (start > end || start % (4L*RANGE_BLOCK) == 0L) {
                                                val syncing = if (timings != null) System.nanoTime() else 0L
                                                out.fd.sync()
                                                timings?.record("diskSync",syncing)
                                                val saving = if (timings != null) System.nanoTime() else 0L
                                                dao.progress(row.peer,false,row.id,start)
                                                timings?.record("roomProgress",saving)
                                                if (!active()) return@execute false
                                            }
                                        } catch (e: java.io.IOException) { diskError = true; throw e }
                                        heartbeat()
                                    }
                                    window = if (checked) preferredWindow else RANGE_BLOCK
                                    supportsChecks = checked
                                    true
                                }
                            }
                            if (!allowed) {
                                if (!active()) return@duringTransfer true
                                dao.change(row.peer, false, row.id, "active", "retrying")
                                return@duringTransfer false
                            }
                        } catch (e: ChunkMismatch) {
                            dao.change(row.peer,false,row.id,"active","invalid")
                            return@duringTransfer true
                        } catch (e: java.io.IOException) {
                            if (diskError) throw e
                            dao.change(row.peer,false,row.id,"active","retrying")
                            android.util.Log.i("LanChatShare","Range interrupted at $start of ${row.size}: " + e.javaClass.simpleName)
                            return@duringTransfer false
                        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
                            dao.change(row.peer,false,row.id,"active","retrying")
                            return@duringTransfer false
                        }
                    }
                }
                if (!active()) return@duringTransfer true
                val hashing = if (timings != null) System.nanoTime() else 0L
                val valid = part.length() == row.size && fileHash(part.inputStream()) == row.hash
                timings?.record("finalSha256",hashing)
                if (!valid) {
                    part.delete()
                    dao.checkpoint(row.peer, row.id, 0)
                    dao.change(row.peer, false, row.id, "active", "invalid")
                    return@duringTransfer true
                }
                check(part.renameTo(target))
            }
            dao.change(row.peer, false, row.id, "active", "done")
            true
        }
    } catch (e: CancellationException) {
        if (!currentCoroutineContext().isActive) throw e
        false
    } catch (_: Exception) {
        dao.change(row.peer, false, row.id, "active", "failed")
        true
    } finally { lock.unlock() }
}

private suspend fun readSegment(client: HttpClient, origin: String, id: String, token: String, start: Long, count: Int, size: Long): ByteArray =
    watchRange { heartbeat ->
        val end = start + count - 1
        client.prepareGet("$origin/v1/files/$id") {
            header(HttpHeaders.Authorization,"Bearer $token")
            header(HttpHeaders.Range,"bytes=$start-$end")
            header(HttpHeaders.Accept,RANGE_FORMAT)
        }.execute { response ->
            check(response.status == HttpStatusCode.PartialContent && response.headers[HttpHeaders.ContentRange] == "bytes $start-$end/$size" &&
                response.headers[HttpHeaders.ContentType]?.substringBefore(';') == RANGE_FORMAT &&
                response.headers[HttpHeaders.ContentLength]?.toLongOrNull() == encodedSize(count.toLong()))
            val result = ByteArray(count)
            val scratch = ByteArray(RANGE_BLOCK)
            val input = response.bodyAsChannel()
            var offset = 0
            while (offset < count) {
                val length = minOf(scratch.size,count-offset)
                input.readBlock(scratch,length,true,heartbeat)
                scratch.copyInto(result,offset,0,length)
                offset += length
            }
            result
        }
    }

internal suspend fun WebSocketSession.announceTransfers(peer: TrustedPeer, files: Transfers) {
    var previous = emptySet<ChatFrame>()
    files.dao.observe(peer.id, peer.pin).map { rows -> rows.mapNotNull { row ->
        val type = if (row.state == "cancelled") "file.decline" else if (row.outgoing) when (row.state) {
            "offered" -> "file.offer"
            "active" -> "file.grant"
            else -> null
        } else when (row.state) {
            "active", "retrying" -> "file.want"
            "done" -> "file.saved"
            "declined" -> "file.decline"
            else -> null
        }
        type?.let { ChatFrame(it, id = row.id, text = row.name, size = row.size, hash = row.hash,
            preview = if (it == "file.offer") files.previewFile(row).takeIf { f -> f.length() in 1..8192 }
                ?.let { f -> java.util.Base64.getEncoder().encodeToString(f.readBytes()) } else null,
            key = if (row.state == "cancelled") (if (row.outgoing) "cancel-sent" else "cancel-received") else "", mime = row.mime, token = if (it == "file.grant") row.token else null) }
    } }.distinctUntilChanged().collect { frames ->
        frames.filterNot { it in previous }.forEach { write(it) }
        previous = frames.toSet()
    }
}

