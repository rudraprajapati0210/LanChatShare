package dev.lanchatshare.connectivity

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages", primaryKeys = ["peerId", "outgoing", "id"],
    indices = [Index(value = ["peerId", "time"])])
data class ChatMessage(val peerId: String, val outgoing: Boolean, val id: String,
    val text: String, val time: Long, val delivered: Boolean, @ColumnInfo(defaultValue = "0") val attachment: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unread: Boolean = false)

data class UnreadText(val sequence: Long, val peerId: String, val name: String, val colour: Int, val text: String, val time: Long)

@Dao
internal interface MessageDao {
    @Query("DELETE FROM messages WHERE peerId = :peer AND outgoing = 1 AND delivered = 0")
    suspend fun deletePending(peer: String)

    @Query("SELECT m.rowid AS sequence, m.peerId, p.name, p.colour, m.text, m.time FROM messages m JOIN trusted_peers p ON p.id=m.peerId WHERE m.unread=1 ORDER BY m.rowid")
    fun unread(): Flow<List<UnreadText>>
    @Query("UPDATE messages SET unread=0 WHERE peerId=:peer AND unread=1 AND rowid<=:through")
    suspend fun read(peer: String, through: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM messages WHERE peerId = :peer ORDER BY time, rowid")
    fun conversation(peer: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE peerId = :peer AND outgoing = 1 AND delivered = 0 AND attachment = 0 ORDER BY time, rowid LIMIT 1")
    fun next(peer: String): Flow<ChatMessage?>

    @Query("UPDATE messages SET delivered = 1 WHERE peerId = :peer AND id = :id AND outgoing = 1 AND attachment = :attachment")
    suspend fun acknowledge(peer: String, id: String, attachment: Boolean = false)

    @Query("SELECT * FROM messages m WHERE rowid = (SELECT rowid FROM messages WHERE peerId = m.peerId ORDER BY time DESC, rowid DESC LIMIT 1) ORDER BY time DESC")
    fun latest(): Flow<List<ChatMessage>>
}
