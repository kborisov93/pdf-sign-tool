package org.example.pdfsign.signing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.cert.X509Certificate
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificateFilterTest {
    private val caKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `personal signing certificates are recommended`() {
        assertTrue(issue(issuer = "CN=Test CA").isDocumentSigningCandidate())
        assertTrue(issue(issuer = "CN=Test CA", KeyPurposeId.id_kp_clientAuth, KeyPurposeId.id_kp_emailProtection).isDocumentSigningCandidate())
    }

    @Test
    fun `device, TLS and self-signed certificates are not recommended`() {
        assertFalse(issue(issuer = "CN=Holder").isDocumentSigningCandidate())
        assertFalse(issue(issuer = "CN=Test CA", KeyPurposeId.id_kp_serverAuth).isDocumentSigningCandidate())
        assertFalse(issue(issuer = "CN=Test CA", KeyPurposeId.id_kp_clientAuth).isDocumentSigningCandidate())
    }

    private fun issue(issuer: String, vararg usages: KeyPurposeId): X509Certificate {
        val holderKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            X500Name(issuer), BigInteger.valueOf(now), Date(now - 60_000), Date(now + 86_400_000), X500Name("CN=Holder"), holderKey.public,
        )
        if (usages.isNotEmpty()) builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(usages))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(caKey.private)
        return JcaX509CertificateConverter().getCertificate(builder.build(signer))
    }
}
