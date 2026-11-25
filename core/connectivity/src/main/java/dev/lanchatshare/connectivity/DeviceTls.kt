package dev.lanchatshare.connectivity

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.Socket
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.net.ssl.*

private const val TLS_ALIAS = "lanchatshare.lan.tls.v1"

internal fun verifyServerChain(chain: Array<X509Certificate>, expectedPin: String) {
    if (chain.size != 2) throw CertificateException("Missing paired identity")
    val (leaf, identity) = chain
    if (Base64.getEncoder().encodeToString(digest(identity.publicKey.encoded)) != expectedPin)
        throw CertificateException("Peer identity changed")
    leaf.checkValidity()
    leaf.verify(identity.publicKey)
}

@Synchronized internal fun serverTls(): SSLContext {
    val identity = identityKeys()
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    if (!store.containsAlias(TLS_ALIAS)) {
        // Conscrypt signs a pre-hashed handshake. Existing pairing keys only authorize SHA-256.
        val spec = KeyGenParameterSpec.Builder(TLS_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256).build()
        KeyPairGenerator.getInstance("EC", "AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
    }
    val key = store.getKey(TLS_ALIAS, null) as PrivateKey
    val public = store.getCertificate(TLS_ALIAS).publicKey
    val root = store.getCertificate(IDENTITY_ALIAS) as X509Certificate
    val now = System.currentTimeMillis()
    val builder = X509v3CertificateBuilder(X500Name.getInstance(root.subjectX500Principal.encoded),
        BigInteger(128, SecureRandom()).setBit(0), Date(now - 86400000L), Date(now + 90L * 86400000L),
        X500Name("CN=LanChatShare"), SubjectPublicKeyInfo.getInstance(public.encoded))
    val der = builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(identity.private)).encoded
    val leaf = CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
    val keys = object : X509ExtendedKeyManager() {
        override fun getPrivateKey(alias: String?) = if (alias == TLS_ALIAS) key else null
        override fun getCertificateChain(alias: String?) = if (alias == TLS_ALIAS) arrayOf(leaf, root) else null
        override fun chooseEngineServerAlias(type: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = TLS_ALIAS.takeIf { type == "EC" }
        override fun chooseServerAlias(type: String?, issuers: Array<out Principal>?, socket: Socket?) = TLS_ALIAS.takeIf { type == "EC" }
        override fun getServerAliases(type: String?, issuers: Array<out Principal>?) = arrayOf(TLS_ALIAS).takeIf { type == "EC" }
        override fun getClientAliases(type: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(types: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
    return SSLContext.getInstance("TLS").apply { init(arrayOf(keys), null, null) }
}
