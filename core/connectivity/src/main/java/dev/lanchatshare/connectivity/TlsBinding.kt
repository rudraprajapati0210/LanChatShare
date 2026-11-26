package dev.lanchatshare.connectivity

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.*
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.*

internal const val TLS_PERIOD = 7L * 86400000
private const val SKEW = 5L * 60000

internal fun deviceHost(id: String): String {
    require(UUID.fromString(id).toString() == id)
    return "$id.lanchatshare.invalid"
}

internal data class Credentials(val key: PrivateKey, val chain: Array<X509Certificate>, val platform: Credentials? = null)

internal fun certificate(subject: X500Name, issuer: X500Name, public: PublicKey, signer: PrivateKey,
    start: Long, end: Long, host: String?, authority: Boolean): X509Certificate {
    val builder = X509v3CertificateBuilder(issuer, BigInteger(128, SecureRandom()).setBit(0), Date(start), Date(end),
        subject, SubjectPublicKeyInfo.getInstance(public.encoded))
    builder.addExtension(Extension.basicConstraints, true, if (authority) BasicConstraints(0) else BasicConstraints(false))
    builder.addExtension(Extension.keyUsage, true, KeyUsage(if (authority) KeyUsage.keyCertSign else KeyUsage.digitalSignature))
    if (host != null) builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(GeneralName(GeneralName.dNSName, host)))
    val algorithm = if (signer.algorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA"
    val encoded = builder.build(JcaContentSignerBuilder(algorithm).build(signer)).encoded
    return CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
}

internal fun certify(id: String, identity: PrivateKey, root: X509Certificate, issuer: KeyPair,
    transport: KeyPair, now: Long): Credentials {
    val host = deviceHost(id)
    val start = now - SKEW
    val end = (now / TLS_PERIOD + 1) * TLS_PERIOD + SKEW
    val issuerName = X500Name("CN=issuer.$host")
    // CIO selects the client's CertificateVerify algorithm from the leaf certificate signature.
    val intermediate = certificate(issuerName, X500Name.getInstance(root.subjectX500Principal.encoded),
        issuer.public, identity, start, end, host, true)
    val leaf = certificate(X500Name("CN=$host"), issuerName, transport.public, issuer.private, start, end, host, false)
    return Credentials(transport.private, arrayOf(leaf, intermediate, root))
}

internal fun verifyBinding(chain: Array<X509Certificate>, trusted: Map<String, String>, now: Date = Date()): String {
    if (chain.size != 3) throw CertificateException("Missing device binding")
    val (leaf, delegation, identity) = chain
    fun hostname(cert: X509Certificate): String? = cert.subjectAlternativeNames?.singleOrNull()
        ?.takeIf { it.size == 2 && it[0] == 2 }?.get(1) as? String
    val host = hostname(leaf) ?: throw CertificateException("Missing device name")
    val id = host.removeSuffix(".lanchatshare.invalid")
    if (trusted[id] == null || host != deviceHost(id) || hostname(delegation) != host)
        throw CertificateException("Untrusted device")
    val pin = Base64.getEncoder().encodeToString(digest(identity.publicKey.encoded))
    if (pin != trusted[id]) throw CertificateException("Peer identity changed")
    if (leaf.basicConstraints != -1 || delegation.basicConstraints != 0 ||
        leaf.issuerX500Principal != delegation.subjectX500Principal ||
        delegation.issuerX500Principal != identity.subjectX500Principal) throw CertificateException("Invalid binding chain")
    for (cert in listOf(leaf, delegation)) {
        cert.checkValidity(now)
        if (cert.notAfter.time - cert.notBefore.time > TLS_PERIOD + 2 * SKEW) throw CertificateException("Binding lifetime too long")
    }
    try { delegation.verify(identity.publicKey); leaf.verify(delegation.publicKey) }
    catch (e: GeneralSecurityException) { throw CertificateException("Invalid binding signature", e) }
    return id
}
