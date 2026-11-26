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
    val result = transportCredentials(id,pairing.private,store.getCertificate(IDENTITY_ALIAS) as X509Certificate,store,prefix,now)
    store.aliases().toList().filter { it.startsWith(TLS_ALIAS) && !it.startsWith(prefix) }.forEach(store::deleteEntry)
    return result
}

internal fun transportCredentials(id: String, identity: PrivateKey, root: X509Certificate, store: KeyStore, prefix: String, now: Long): Credentials {
    val issuer = transportKey(store,prefix + "issuer")
    val rsa = certify(id,identity,root,issuer,transportKey(store,prefix + "tls"),now)
    val alias = prefix + "ecdsa"
    if (!store.containsAlias(alias)) {
        val spec = KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE,KeyProperties.DIGEST_SHA256).build()
        KeyPairGenerator.getInstance("EC","AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
    }
    val ec = KeyPair(store.getCertificate(alias).publicKey,store.getKey(alias,null) as PrivateKey)
    return rsa.copy(platform = certify(id,identity,root,issuer,ec,now))
}

internal fun serverTls(credentials: Credentials, trusted: () -> Map<String,String>): SSLContext =
    deviceTls(credentials, trusted, false).first

internal fun deviceTls(credentials: Credentials, trusted: () -> Map<String,String>, client: Boolean): Pair<SSLContext,X509TrustManager> {
    val aliases = buildMap { put("RSA",credentials); credentials.platform?.let { put("EC",it) } }
    val keys = object : X509ExtendedKeyManager() {
        override fun getPrivateKey(alias: String?) = aliases[alias]?.key
        override fun getCertificateChain(alias: String?) = aliases[alias]?.chain
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            type?.takeIf { it in aliases && !(it == "RSA" && engine?.handshakeSession?.protocol == "TLSv1.3") }
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = type?.takeIf { it in aliases }
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = type?.takeIf { it in aliases }?.let { arrayOf(it) }
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?) = type?.takeIf { client && it in aliases }?.let { arrayOf(it) }
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
            listOf("EC","RSA").firstOrNull { client && it in aliases && types?.contains(it) == true }
        override fun chooseEngineClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            chooseClientAlias(types,issuers,null)
    }
    val peers = object : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<X509Certificate>, auth: String) { verifyBinding(chain, trusted()) }
        override fun checkServerTrusted(chain: Array<X509Certificate>, auth: String) {
            if (!client) throw CertificateException("Server-only context")
            verifyBinding(chain, trusted())
        }
    }
    return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), arrayOf(peers), null) } to peers
}

