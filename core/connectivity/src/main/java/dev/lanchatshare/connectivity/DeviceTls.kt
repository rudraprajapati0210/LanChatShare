package dev.lanchatshare.connectivity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.*

private const val TLS_ALIAS = "lanchatshare.transport."

internal fun transportKey(store: KeyStore, alias: String): KeyPair {
    if (!store.containsAlias(alias)) {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setKeySize(2048).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512).build()
        KeyPairGenerator.getInstance("RSA", "AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
    }
    return KeyPair(store.getCertificate(alias).publicKey, store.getKey(alias, null) as PrivateKey)
}

@Synchronized internal fun deviceCredentials(id: String, now: Long = System.currentTimeMillis()): Credentials {
    val pairing = identityKeys()
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val prefix = TLS_ALIAS + now / TLS_PERIOD + "."
    val result = certify(id, pairing.private, store.getCertificate(IDENTITY_ALIAS) as X509Certificate, transportKey(store, prefix + "issuer"), transportKey(store, prefix + "tls"), now)
    store.aliases().toList().filter { it.startsWith(TLS_ALIAS) && !it.startsWith(prefix) }.forEach(store::deleteEntry)
    return result
}

internal fun serverTls(credentials: Credentials, trusted: () -> Map<String,String>): SSLContext {
    val key = credentials.key
    val keys = object : X509ExtendedKeyManager() {
        override fun getPrivateKey(alias: String?) = if (alias == TLS_ALIAS) key else null
        override fun getCertificateChain(alias: String?) = if (alias == TLS_ALIAS) credentials.chain else null
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = TLS_ALIAS.takeIf { type == "RSA" }
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = TLS_ALIAS.takeIf { type == "RSA" }
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(TLS_ALIAS).takeIf { type == "RSA" }
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
    val peers = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { verifyBinding(chain, trusted()) }
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) { throw CertificateException("Server-only context") }
    }
    return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(peers), null) }
}

