package dev.lanchatshare.connectivity

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

@Entity(tableName = "transfers", primaryKeys = ["peer", "outgoing", "id"],
    indices = [Index("time"),Index("mime"),Index(value = ["peer","time"]),Index(value = ["outgoing","id"])])
data class Transfer(val peer: String, val outgoing: Boolean, val id: String, val name: String,
    val mime: String, val size: Long, val hash: String, val pin: String, val token: String,
    val state: String, val offset: Long, val time: Long)

@Dao internal interface TransferDao {
    @RawQuery(observedEntities = [Transfer::class])
    fun library(query: androidx.sqlite.db.SupportSQLiteQuery): androidx.paging.PagingSource<Int,Transfer>
    @Query("SELECT COALESCE(SUM(bytes),0) FROM (SELECT MAX(size) AS bytes FROM transfers WHERE (outgoing=1 OR state='done') AND state!='removed' GROUP BY outgoing,id,CASE WHEN outgoing=1 THEN '' ELSE peer END)")
    fun storedBytes(): Flow<Long>
    @Query("SELECT COUNT(*) FROM transfers WHERE id=:id AND outgoing=1 AND state!='removed'")
    suspend fun copies(id: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: Transfer): Long
    @Query("SELECT * FROM transfers ORDER BY time DESC") fun all(): Flow<List<Transfer>>
    @Query("SELECT * FROM transfers WHERE peer=:peer AND pin=:pin ORDER BY time") fun observe(peer: String, pin: String): Flow<List<Transfer>>
    @Query("SELECT * FROM transfers WHERE peer=:peer AND outgoing=:outgoing AND id=:id") suspend fun get(peer: String, outgoing: Boolean, id: String): Transfer?
    @Query("SELECT t.* FROM transfers t JOIN trusted_peers p ON p.id=t.peer AND p.pin=t.pin WHERE t.outgoing=1 AND t.state='active' AND t.id=:id AND t.token=:token")
    suspend fun granted(id: String, token: String): Transfer?
    @Query("UPDATE transfers SET state=:next WHERE peer=:peer AND outgoing=:outgoing AND id=:id AND state=:was")
    suspend fun change(peer: String, outgoing: Boolean, id: String, was: String, next: String)
    @Query("UPDATE transfers SET offset=:offset WHERE peer=:peer AND outgoing=0 AND id=:id")
    suspend fun checkpoint(peer: String, id: String, offset: Long)
    @Query("UPDATE transfers SET offset=:offset WHERE peer=:peer AND outgoing=:outgoing AND id=:id AND offset<:offset")
    suspend fun progress(peer: String, outgoing: Boolean, id: String, offset: Long)
    @Query("UPDATE transfers SET state='revoked' WHERE peer=:peer AND state NOT IN ('done','declined')")
    suspend fun revoke(peer: String)
}

internal fun fileHash(input: InputStream): String = input.use {
    val hash = MessageDigest.getInstance("SHA-256")
    val bytes = ByteArray(65536)
    while (true) { val n = it.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
    hash.digest().joinToString("") { b -> "%02x".format(b) }
}

class Transfers internal constructor(private val context: Context, private val db: TrustDatabase) {
    private val profiles = object : LinkedHashMap<String, RangeTimings>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RangeTimings>?) = size > 64
    }
    internal fun timings(row: Transfer): RangeTimings? {
        if (!row.name.startsWith("LAN benchmark ") || row.mime != "application/octet-stream") return null
        return synchronized(profiles) { profiles.getOrPut("${row.peer}/${row.outgoing}/${row.id}") { RangeTimings() } }
    }
    fun benchmarkStages(row: Transfer) = timings(row)?.snapshot().orEmpty()
    val storageUsage = db.transfers().storedBytes()
    fun library(filter: LibraryFilter) = androidx.paging.Pager(androidx.paging.PagingConfig(50, enablePlaceholders = false)) {
        dao.library(filter.query())
    }.flow
    suspend fun removeLocal(row: Transfer): Boolean = withContext(Dispatchers.IO) {
        db.withTransaction {
            val current = dao.get(row.peer,row.outgoing,row.id) ?: return@withTransaction false
            if (current.state !in listOf("done","cancelled","declined","revoked")) return@withTransaction false
            dao.change(row.peer,row.outgoing,row.id,current.state,"removed")
            if (!row.outgoing || dao.copies(row.id) == 0) {
                val payload = file(row)
                check(!payload.exists() || payload.delete())
                previewFile(row).delete()
            }
            true
        }
    }
    private val operations = kotlinx.coroutines.flow.MutableStateFlow(0)
    val activeOperations: kotlinx.coroutines.flow.StateFlow<Int> get() = operations
    internal suspend fun <T> duringTransfer(block: suspend () -> T): T {
        operations.update { it + 1 }
        try { return block() } finally { operations.update { it - 1 } }
    }
    internal val dao = db.transfers()
    val all = dao.all()
    private val directory = File(context.filesDir, "attachments")
    internal val downloadLock = Array(16) { Mutex() }
    fun file(row: Transfer): File = if (row.outgoing) File(directory, "sent/" + row.id) else File(directory, row.peer + "/received/" + row.id)
    suspend fun add(peers: List<TrustedPeer>, uri: Uri) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "Attachment"
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val image = mime.startsWith("image/")
        val video = mime.startsWith("video/")
        val thumbnail = if (image || video) context.tinyPreview(uri) else null
        if ((image || video) && context.standardMedia().first()) {
            val extension = if (image) ".jpg" else ".mp4"
            val temp = File.createTempFile("media",extension,context.cacheDir)
            try {
                if (image) context.standardPhoto(uri,temp) else context.standardVideo(uri,temp)
                stage(peers,name.substringBeforeLast('.') + extension,if (image) "image/jpeg" else "video/mp4",thumbnail) { temp.inputStream() }
            } finally { temp.delete() }
        } else stage(peers, name, mime, thumbnail) { requireNotNull(resolver.openInputStream(uri)) }
    }
    suspend fun addBenchmark(peer: TrustedPeer, megabytes: Int, setup: String = "Unspecified") {
        require(megabytes in listOf(10, 100, 1000))
        require(setup in listOf("Router","Hotspot","Direct","Unspecified"))
        stage(listOf(peer), "LAN benchmark ${megabytes} MB - $setup.bin", "application/octet-stream") {
            val block = ByteArray(65536).also(java.util.Random(147)::nextBytes)
            object : InputStream() {
                var remaining = megabytes * 1_000_000L
                var cursor = 0
                override fun read(): Int {
                    if (remaining == 0L) return -1
                    remaining--
                    return block[cursor++ and 65535].toInt() and 255
                }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    if (length == 0) return 0
                    if (remaining == 0L) return -1
                    val pos = cursor and 65535
                    val count = minOf(length.toLong(), remaining, (block.size-pos).toLong()).toInt()
                    block.copyInto(bytes,offset,pos,pos+count)
                    cursor += count; remaining -= count
                    return count
                }
            }
        }
    }
    suspend fun addText(peers: List<TrustedPeer>, text: String) = stage(peers, "Shared text.txt", "text/plain") { text.byteInputStream() }
    fun forPeer(peer: TrustedPeer) = dao.observe(peer.id, peer.pin)
    fun previewFile(row: Transfer) = File(file(row).path + ".thumb")
    internal suspend fun stage(peers: List<TrustedPeer>, title: String, mime: String, thumbnail: ByteArray? = null, open: () -> InputStream) = withContext(Dispatchers.IO) {
        require(peers.isNotEmpty())
        directory.mkdirs()
        val temp = File.createTempFile("stage", ".partial", directory)
        val id = UUID.randomUUID().toString()
        val stored = File(directory, "sent/$id")
        try {
            open().use { input -> temp.outputStream().use { input.copyTo(it) } }
            val hashing = System.nanoTime()
            val hash = fileHash(temp.inputStream())
            val name = title.filter { it >= ' ' && it !in "/\\" }.take(200).ifBlank { "Attachment" }
            val rows = peers.distinctBy { it.id }.map { peer ->
                val row = Transfer(peer.id, true, id, name, mime.take(127), temp.length(), hash,
                    peer.pin, UUID.randomUUID().toString(), "offered", 0, System.currentTimeMillis())
                timings(row)?.record("stagingSha256",hashing)
                row
            }
            stored.parentFile!!.mkdirs()
            check(temp.renameTo(stored))
            if (thumbnail != null) previewFile(rows.first()).writeBytes(thumbnail)
            db.withTransaction {
                rows.forEach { row ->
                    check(db.peers().pin(row.peer) == row.pin)
                    check(dao.insert(row) != -1L)
                    db.messages().insert(ChatMessage(row.peer, true, row.id, row.name, row.time, false, true))
                }
            }
        } catch (e: Exception) { stored.delete(); File(stored.path + ".thumb").delete(); throw e }
        finally { temp.delete() }
    }
    suspend fun action(row: Transfer, next: String) {
        val allowed = when (next) {
            "active" -> row.state in listOf("paused", "failed", "storage", "invalid") || (!row.outgoing && row.state == "offered")
            "paused" -> row.state in listOf("active","retrying")
            "cancelled" -> row.state in listOf("active","retrying","paused","failed","storage","invalid","offered")
            "declined" -> !row.outgoing && row.state == "offered"
            else -> false
        }
        if (!allowed) return
        dao.change(row.peer, row.outgoing, row.id, row.state, next)
        if (next == "cancelled" && !row.outgoing) withContext(Dispatchers.IO) {
            downloadLock[(row.id.hashCode() and Int.MAX_VALUE) % downloadLock.size].withLock {
                if (dao.get(row.peer,false,row.id)?.state == "cancelled") File(file(row).path + ".partial").delete()
            }
        }
    }
    internal suspend fun offer(peer: TrustedPeer, frame: ChatFrame) = db.withTransaction {
        require(UUID.fromString(frame.id).toString() == frame.id && frame.text.length in 1..200 && frame.size >= 0 &&
            frame.hash.matches(Regex("[0-9a-f]{64}")) && frame.mime.length <= 127 && frame.text.none { it < ' ' || it in "/\\" })
        check(db.peers().pin(peer.id) == peer.pin)
        val row = Transfer(peer.id, false, frame.id, frame.text, frame.mime, frame.size, frame.hash, peer.pin,
            "", "offered", 0, System.currentTimeMillis())
        if (dao.insert(row) != -1L) {
            frame.preview?.takeIf { it.length <= 10924 }?.let { encoded ->
                runCatching {
                    val bytes = java.util.Base64.getDecoder().decode(encoded)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                    if (bounds.outMimeType == "image/jpeg" && bounds.outWidth in 1..160 && bounds.outHeight in 1..160 && bytes.size <= 8192) {
                        previewFile(row).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
                    }
                }
            }
            db.messages().insert(ChatMessage(peer.id, false, row.id, row.name, row.time, true, true))
        }
    }
    internal suspend fun receipt(peer: TrustedPeer, id: String) = db.withTransaction {
        val row = dao.get(peer.id, true, id) ?: return@withTransaction
        if (row.pin == peer.pin && row.state in listOf("active","paused")) {
            timings(row)?.receipt()
            dao.change(row.peer, true, row.id, row.state, "done")
            dao.progress(row.peer, true, row.id, row.size)
            db.messages().acknowledge(row.peer, row.id, attachment = true)
        }
    }
}
