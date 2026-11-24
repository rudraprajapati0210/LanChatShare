package dev.lanchatshare.connectivity

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages", primaryKeys = ["peerId", "outgoing", "id"],
    indices = [Index(value = ["peerId", "time"])])
data class ChatMessage(val peerId: String, val outgoing: Boolean, val id: String,
    val text: String, val time: Long, val delivered: Boolean)

@Dao
internal interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: ChatMessage): Long

    @Query("SELECT * FROM messages WHERE peerId = :peer ORDER BY time, rowid")
    fun conversation(peer: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE peerId = :peer AND outgoing = 1 AND delivered = 0 ORDER BY time, rowid LIMIT 1")
    fun next(peer: String): Flow<ChatMessage?>

    @Query("UPDATE messages SET delivered = 1 WHERE peerId = :peer AND id = :id AND outgoing = 1")
    suspend fun acknowledge(peer: String, id: String)

    @Query("SELECT * FROM messages m WHERE rowid = (SELECT rowid FROM messages WHERE peerId = m.peerId ORDER BY time DESC, rowid DESC LIMIT 1) ORDER BY time DESC")
    fun latest(): Flow<List<ChatMessage>>
}
