package dev.lanchatshare.connectivity

import android.content.Context
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.room.*
import java.security.*
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trusted_peers")
data class TrustedPeer(@PrimaryKey val id: String, val name: String, val colour: Int, val pin: String,
    val certificateSha256: String? = null)
@Dao internal interface PeerDao {
    @Query("SELECT * FROM trusted_peers ORDER BY name COLLATE NOCASE, id")
    fun observe(): Flow<List<TrustedPeer>>
    @Query("SELECT pin FROM trusted_peers WHERE id = :id")
    suspend fun pin(id: String): String?
    @Query("SELECT * FROM trusted_peers WHERE id = :id")
    suspend fun find(id: String): TrustedPeer?
    @Query("UPDATE trusted_peers SET name = :name, colour = :colour WHERE id = :id AND pin = :pin AND (name != :name OR colour != :colour)")
    suspend fun updateProfile(id: String, pin: String, name: String, colour: Int)
    @Query("DELETE FROM trusted_peers WHERE id = :id AND pin = :pin")
    suspend fun forget(id: String, pin: String): Int
    @Upsert suspend fun save(peer: TrustedPeer)
}
@Database(entities = [TrustedPeer::class, ChatMessage::class, Transfer::class], version = 4, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)])
internal abstract class TrustDatabase : RoomDatabase() {
    abstract fun transfers(): TransferDao
    abstract fun messages(): MessageDao
    abstract fun peers(): PeerDao
}
class TrustStore(context: Context, databaseName: String = "peers.db") {
    private val database = Room.databaseBuilder(context.applicationContext, TrustDatabase::class.java, databaseName).build()
    val transfers = Transfers(context.applicationContext, database)
    internal fun close() = database.close()
    internal val dao = database.peers()
    internal val messages = database.messages()
    val latest = messages.latest()
    fun conversation(id: String) = messages.conversation(id)
    suspend fun enqueue(peer: String, text: String): Unit = database.withTransaction {
        require(text.isNotBlank() && text.length <= 4096 && dao.pin(peer) != null)
        messages.insert(ChatMessage(peer, true, java.util.UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis(), false))
    }
    internal suspend fun saveIncoming(peer: TrustedPeer, message: ChatMessage) = database.withTransaction {
        check(dao.pin(peer.id) == peer.pin)
        messages.insert(message)
    }
    suspend fun forget(peer: TrustedPeer) {
        database.withTransaction {
            if (dao.forget(peer.id, peer.pin) == 1) {
                database.transfers().revoke(peer.id)
                messages.deletePending(peer.id)
                synchronized(this@TrustStore) {
                    tickets.remove(peer.id)
                    hints.value = hints.value - peer.id
                }
            }
        }
    }
    val peers = dao.observe()
    internal val hints = kotlinx.coroutines.flow.MutableStateFlow<Map<String, List<WifiEndpoint>>>(emptyMap())
    private data class Ticket(val bytes: ByteArray, val until: Long, val routes: List<WifiEndpoint>, val outgoing: Boolean)
    private val tickets = mutableMapOf<String, Ticket>()
    @Synchronized internal fun issue(id: String, bytes: ByteArray, routes: List<WifiEndpoint> = emptyList(), outgoing: Boolean = false) {
        tickets.entries.removeAll { it.value.until <= SystemClock.elapsedRealtime() }
        hints.value = hints.value + (id to routes.toList())
        tickets[id] = Ticket(bytes.copyOf(), SystemClock.elapsedRealtime() + 120_000, routes.toList(), outgoing)
    }
    @Synchronized internal fun takeOutgoing(id: String): Pair<ByteArray, List<WifiEndpoint>>? {
        val ticket = tickets[id] ?: return null
        if (!ticket.outgoing) return null
        tickets.remove(id)
        if (ticket.until <= SystemClock.elapsedRealtime()) return null
        return ticket.bytes to ticket.routes
    }
    @Synchronized internal fun pendingToken(id: String): ByteArray? =
        tickets[id]?.takeIf { it.outgoing && it.until > SystemClock.elapsedRealtime() }?.bytes?.copyOf()

    @Synchronized internal fun completeOutgoing(id: String, sent: ByteArray?) {
        val ticket = tickets[id] ?: return
        if (sent != null && ticket.outgoing && MessageDigest.isEqual(ticket.bytes, sent)) tickets.remove(id)
    }
    @Synchronized internal fun checkHandoff(id: String, presented: ByteArray?): Boolean {
        val ticket = tickets[id]
        if (presented != null) return consume(id, presented)
        return ticket == null || ticket.outgoing || ticket.until <= SystemClock.elapsedRealtime()
    }
    @Synchronized fun consume(id: String, presented: ByteArray): Boolean {
        val ticket = tickets[id] ?: return false
        if (ticket.until <= SystemClock.elapsedRealtime()) { tickets.remove(id); return false }
        if (ticket.outgoing) return false
        if (!MessageDigest.isEqual(ticket.bytes, presented)) return false
        tickets.remove(id)
        return true
    }
}
internal const val IDENTITY_ALIAS = "lanchatshare.identity.v1"
internal fun identityCertificate(): ByteArray = KeyStore.getInstance("AndroidKeyStore").run {
    load(null)
    getCertificate(IDENTITY_ALIAS).encoded
}
@Synchronized internal fun identityKeys(): KeyPair {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getEntry(IDENTITY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
        return KeyPair(it.certificate.publicKey, it.privateKey)
    }
    val spec = KeyGenParameterSpec.Builder(IDENTITY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
        .setDigests(KeyProperties.DIGEST_SHA256).build()
    return KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").run {
        initialize(spec); generateKeyPair()
    }
}




