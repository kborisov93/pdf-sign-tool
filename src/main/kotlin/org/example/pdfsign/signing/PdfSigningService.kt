package org.example.pdfsign.signing

import com.itextpdf.forms.fields.properties.SignedAppearanceText
import com.itextpdf.forms.form.element.SignatureFieldAppearance
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.kernel.crypto.DigestAlgorithms
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.signatures.BouncyCastleDigest
import com.itextpdf.signatures.IExternalSignature
import com.itextpdf.signatures.ISignatureMechanismParams
import com.itextpdf.signatures.PdfSigner
import com.itextpdf.signatures.SignerProperties
import com.itextpdf.signatures.TSAClientBouncyCastle
import org.example.pdfsign.AppInfo
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.PrivateKey
import java.security.Provider
import java.security.Signature
import java.util.Calendar
import kotlin.io.path.Path

/** Applies a visible PAdES (CAdES-based) signature with iText. */
class PdfSigningService(fontFile: Path? = defaultFontFile()) {
    private val log = LoggerFactory.getLogger(PdfSigningService::class.java)

    /** A Unicode TrueType font, so that names in Cyrillic and other scripts render; null means Helvetica. */
    private val fontBytes: ByteArray? = fontFile?.let { file ->
        try {
            Files.readAllBytes(file)
        } catch (e: IOException) {
            log.warn("Cannot read font {}; the signature appearance falls back to Helvetica", file, e)
            null
        }
    }

    fun sign(request: SignRequest, credential: SigningCredential, timestampUrl: String? = null): ByteArray {
        val options = request.options
        val info = request.documentInfo
        val signDate = Calendar.getInstance()
        val output = ByteArrayOutputStream(request.document.size + 64 * 1024)

        // Incremental update keeps the original bytes (and earlier signatures) intact. Only a document whose
        // cross-reference table had to be rebuilt, and that has nothing to preserve, is rewritten completely.
        val stamping = StampingProperties()
        if (!info.xrefRebuilt || info.existingSignatures > 0) stamping.useAppendMode()
        val signer = PdfSigner(PdfReader(ByteArrayInputStream(request.document)), output, stamping)

        val text = SignedAppearanceText()
            .setSignedBy(credential.certificate.subjectX500Principal.commonName())
            .setSignDate(signDate)
        options.reason?.let { text.setReasonLine("Reason: $it") }
        options.location?.let { text.setLocationLine("Location: $it") }
        val appearance = SignatureFieldAppearance(SignerProperties.IGNORED_ID).setContent(text)
        fontBytes?.let {
            appearance.setFont(PdfFontFactory.createFont(it, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED))
        }

        val properties = SignerProperties()
            .setFieldName(options.fieldName ?: signer.newSigFieldName)
            .setPageNumber(options.page)
            .setPageRect(info.signatureRect.toRectangle())
            .setClaimedSignDate(signDate)
            .setSignatureCreator("${AppInfo.NAME} ${AppInfo.version}")
            .setSignatureAppearance(appearance)
        options.reason?.let { properties.setReason(it) }
        options.location?.let { properties.setLocation(it) }
        options.contactInfo?.let { properties.setContact(it) }
        signer.setSignerProperties(properties)

        val tsaClient = timestampUrl?.takeIf { it.isNotBlank() }?.let { TSAClientBouncyCastle(it) }
        signer.signDetached(
            BouncyCastleDigest(),
            ProviderSignature(credential.privateKey, credential.provider),
            credential.chain.toTypedArray(),
            null,
            null,
            tsaClient,
            0,
            PdfSigner.CryptoStandard.CADES,
        )
        return output.toByteArray()
    }

    companion object {
        fun defaultFontFile(): Path? {
            val fonts = Path(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts")
            return listOf("arial.ttf", "segoeui.ttf", "tahoma.ttf").map(fonts::resolve).firstOrNull(Files::isRegularFile)
        }
    }
}

/**
 * Signs with a JCA [Provider] instance rather than a provider name, so a SunPKCS11 provider does not have to be
 * registered globally. With SunMSCAPI the smart card's PIN dialog appears during [sign].
 */
class ProviderSignature(private val key: PrivateKey, private val provider: Provider?) : IExternalSignature {
    private val algorithm = when (key.algorithm.uppercase()) {
        "RSA" -> "RSA"
        "EC", "ECDSA" -> "ECDSA"
        else -> throw GeneralSecurityException("Keys of type ${key.algorithm} are not supported")
    }

    override fun getDigestAlgorithmName(): String = DigestAlgorithms.SHA256

    override fun getSignatureAlgorithmName(): String = algorithm

    override fun getSignatureMechanismParameters(): ISignatureMechanismParams? = null

    override fun sign(message: ByteArray): ByteArray {
        val name = "SHA256with$algorithm"
        val signature = provider?.let { Signature.getInstance(name, it) } ?: Signature.getInstance(name)
        signature.initSign(key)
        signature.update(message)
        return signature.sign()
    }
}
