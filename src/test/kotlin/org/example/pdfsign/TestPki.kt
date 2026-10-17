package org.example.pdfsign

import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Paragraph
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.example.pdfsign.signing.SigningCredential
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date

/** Software keys and sample documents, so the signing flow can be tested without a smart card. */
object TestPki {
    fun credential(commonName: String = "Иван Петров Тестов", keyAlgorithm: String = "RSA"): SigningCredential {
        val keyPair = KeyPairGenerator.getInstance(keyAlgorithm)
            .apply { initialize(if (keyAlgorithm == "EC") 256 else 2048) }
            .generateKeyPair()
        val name = X500Name("CN=$commonName, O=PDF Sign Tool Tests, C=BG")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 3_600_000), Date(now + 86_400_000), name, keyPair.public,
        ).addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.nonRepudiation))
        val contentSigner = JcaContentSignerBuilder(if (keyAlgorithm == "EC") "SHA256withECDSA" else "SHA256withRSA")
            .build(keyPair.private)
        val certificate = JcaX509CertificateConverter().getCertificate(builder.build(contentSigner))
        return SigningCredential(keyPair.private, listOf(certificate), provider = null)
    }

    fun samplePdf(pageCount: Int = 2, rotation: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        PdfDocument(PdfWriter(out)).use { pdf ->
            Document(pdf, PageSize.A4).use { document ->
                repeat(pageCount) { index ->
                    if (index > 0) document.add(com.itextpdf.layout.element.AreaBreak())
                    document.add(Paragraph("Sample page ${index + 1}"))
                }
                (1..pdf.numberOfPages).forEach { pdf.getPage(it).setRotation(rotation) }
            }
        }
        return out.toByteArray()
    }
}
