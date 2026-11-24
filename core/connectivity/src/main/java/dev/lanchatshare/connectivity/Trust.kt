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
    @Upsert suspend fun save(peer: TrustedPeer)
}
@Database(entities = [TrustedPeer::class], version = 2, autoMigrations = [AutoMigration(from = 1, to = 2)])
internal abstract class TrustDatabase : RoomDatabase() {
    abstract fun peers(): PeerDao
}
class TrustStore(context: Context) {
    private val database = Room.databaseBuilder(context.applicationContext, TrustDatabase::class.java, "peers.db").build()
    internal val dao = database.peers()
    val peers = dao.observe()
    private data class Ticket(val bytes: ByteArray, val until: Long, val routes: List<WifiEndpoint>, val outgoing: Boolean)
    private val tickets = mutableMapOf<String, Ticket>()
    @Synchronized internal fun issue(id: String, bytes: ByteArray, routes: List<WifiEndpoint> = emptyList(), outgoing: Boolean = false) {
        tickets.entries.removeAll { it.value.until <= SystemClock.elapsedRealtime() }
        tickets[id] = Ticket(bytes.copyOf(), SystemClock.elapsedRealtime() + 120_000, routes.toList(), outgoing)
    }
    @Synchronized internal fun takeOutgoing(id: String): Pair<ByteArray, List<WifiEndpoint>>? {
        val ticket = tickets[id] ?: return null
        if (!ticket.outgoing) return null
        tickets.remove(id)
        if (ticket.until <= SystemClock.elapsedRealtime()) return null
        return ticket.bytes to ticket.routes
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

