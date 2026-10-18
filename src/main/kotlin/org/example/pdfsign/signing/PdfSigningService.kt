package org.example.pdfsign.signing

import com.itextpdf.forms.form.element.SignatureFieldAppearance
import com.itextpdf.io.font.PdfEncodings
import com.itextpdf.io.font.constants.StandardFonts
import com.itextpdf.kernel.crypto.DigestAlgorithms
import com.itextpdf.kernel.font.PdfFont
import com.itextpdf.kernel.font.PdfFontFactory
import com.itextpdf.kernel.font.PdfFontFactory.EmbeddingStrategy
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.kernel.pdf.StampingProperties
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Div
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.layout.LayoutArea
import com.itextpdf.layout.layout.LayoutContext
import com.itextpdf.layout.layout.LayoutResult
import com.itextpdf.layout.properties.BackgroundImage
import com.itextpdf.layout.properties.BackgroundPosition
import com.itextpdf.layout.properties.BackgroundRepeat
import com.itextpdf.layout.properties.BackgroundSize
import com.itextpdf.layout.properties.Property
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.VerticalAlignment
import com.itextpdf.signatures.BouncyCastleDigest
import com.itextpdf.signatures.IExternalSignature
import com.itextpdf.signatures.ISignatureMechanismParams
import com.itextpdf.signatures.PdfSigner
import com.itextpdf.signatures.SignerProperties
import com.itextpdf.signatures.TSAClientBouncyCastle
import com.itextpdf.svg.converter.SvgConverter
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
import java.time.format.DateTimeFormatter
import java.util.Calendar
import kotlin.io.path.Path

/**
 * Applies a visible PAdES (CAdES-based) signature with iText. [logoSvg] is drawn at the left of the signature box;
 * null keeps the appearance text only.
 */
class PdfSigningService(fontFile: Path? = defaultFontFile(), private val logoSvg: ByteArray? = defaultLogo()) {
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

    fun sign(
        request: SignRequest,
        credential: SigningCredential,
        timestampUrl: String? = null,
        signerDetails: SignerDetails = request.options.signerDetails ?: SignerDetails.NAME,
    ): ByteArray {
        val options = request.options
        val info = request.documentInfo
        val signDate = Calendar.getInstance()
        val output = ByteArrayOutputStream(request.document.size + 64 * 1024)

        // Incremental update keeps the original bytes (and earlier signatures) intact. Only a document whose
        // cross-reference table had to be rebuilt, and that has nothing to preserve, is rewritten completely.
        val stamping = StampingProperties()
        if (!info.xrefRebuilt || info.existingSignatures > 0) stamping.useAppendMode()
        val signer = PdfSigner(PdfReader(ByteArrayInputStream(request.document)), output, stamping)

        val subject = credential.certificate.subjectX500Principal
        val dateLine = "Date: ${acrobatDate(signDate)}"
        val lines = listOfNotNull(
            "Digitally signed by ${subject.commonName()}",
            if (signerDetails == SignerDetails.DN) "DN: ${subject.displayDn()}" else null,
            dateLine,
            options.reason?.let { "Reason: $it" },
            options.location?.let { "Location: $it" },
        )
        val appearance = buildAppearance(signer.document, lines, dateLine, visibleBox(info))

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

    /** Like Adobe Acrobat's signatures: "2026.09.13 21:30:21 +03'00'". */
    private fun acrobatDate(date: Calendar): String {
        val time = date.toInstant().atZone(date.timeZone.toZoneId())
        return DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss xxx").format(time).replace(Regex("""([+-]\d\d):(\d\d)$"""), "$1'$2'")
    }

    /** The signature box as the viewer shows it: iText turns the appearance upright on pages rotated by 90 or 270°. */
    private fun visibleBox(info: DocumentInfo): PdfRect {
        val rect = info.signatureRect
        return if (info.page.rotation % 180 == 90) PdfRect(rect.x, rect.y, rect.height, rect.width) else rect
    }

    /**
     * The logo at the left (if there is one) and the text lines to its right, both centred vertically in the box. iText's
     * own description layout keeps the top 30% of the box empty, so the lines are laid out here, in the largest font
     * size that fits them (wrapped) into the space next to the logo. [singleLine] (the date) must not wrap.
     */
    private fun buildAppearance(document: PdfDocument, lines: List<String>, singleLine: String, box: PdfRect): SignatureFieldAppearance {
        val appearance = SignatureFieldAppearance(SignerProperties.IGNORED_ID)
        val margin = box.height * MARGIN_SHARE
        val textLeft = logoSvg?.let { addLogo(appearance, document, it, box, margin) } ?: margin
        val fontSize = fittingFontSize(lines, singleLine, box.width - textLeft - margin, box.height - 2 * margin)
        appearance.setContent(textBlock(lines)).setFont(createFont()).setFontSize(fontSize)
        appearance.setProperty(Property.PADDING_LEFT, UnitValue.createPointValue(textLeft))
        listOf(Property.PADDING_TOP, Property.PADDING_RIGHT, Property.PADDING_BOTTOM)
            .forEach { appearance.setProperty(it, UnitValue.createPointValue(margin)) }
        appearance.setProperty(Property.VERTICAL_ALIGNMENT, VerticalAlignment.MIDDLE)
        return appearance
    }

    private fun createFont(): PdfFont =
        fontBytes?.let { PdfFontFactory.createFont(it, PdfEncodings.IDENTITY_H, EmbeddingStrategy.FORCE_EMBEDDED) }
            ?: PdfFontFactory.createFont(StandardFonts.HELVETICA)

    private fun textBlock(lines: List<String>): Div =
        Div().apply { lines.forEach { add(Paragraph(it).setMargin(0f).setMultipliedLeading(LEADING)) } }

    /**
     * The largest font size, up to [MAX_FONT_SIZE], at which [lines], wrapped by iText itself, fit into [width] x [height]. Measured by laying
     * the text out in a scratch document (a font belongs to one document), with binary search.
     */
    private fun fittingFontSize(lines: List<String>, singleLine: String, width: Float, height: Float): Float {
        PdfDocument(PdfWriter(ByteArrayOutputStream())).use { scratch ->
            val parent = Document(scratch).renderer
            val font = createFont()
            fun fits(size: Float): Boolean {
                if (font.getWidth(singleLine, size) > width) return false
                val renderer = textBlock(lines).setFont(font).setFontSize(size).createRendererSubTree().setParent(parent)
                val result = renderer.layout(LayoutContext(LayoutArea(1, Rectangle(width, height))))
                return result.status == LayoutResult.FULL
            }
            if (fits(MAX_FONT_SIZE)) return MAX_FONT_SIZE.also { scratch.addNewPage() }
            var low = MIN_FONT_SIZE
            var high = MAX_FONT_SIZE
            repeat(16) {
                val mid = (low + high) / 2
                if (fits(mid)) low = mid else high = mid
            }
            scratch.addNewPage() // a document without pages cannot be closed
            return low
        }
    }

    /**
     * Draws the logo as vector graphics at the left of the box, with [margin] around it, and returns where the text
     * starts. The logo never takes more than [MAX_LOGO_SHARE] of the width, so a narrow box still has room for the text.
     */
    private fun addLogo(appearance: SignatureFieldAppearance, document: PdfDocument, svg: ByteArray, box: PdfRect, margin: Float): Float {
        val logo = SvgConverter.convertToXObject(ByteArrayInputStream(svg), document)
        var height = box.height - 2 * margin
        var width = height * logo.width / logo.height
        if (width > box.width * MAX_LOGO_SHARE) {
            width = box.width * MAX_LOGO_SHARE
            height = width * logo.height / logo.width
        }
        val size = BackgroundSize().apply { setBackgroundSizeToValues(UnitValue.createPointValue(width), UnitValue.createPointValue(height)) }
        val position = BackgroundPosition()
            .setPositionX(BackgroundPosition.PositionX.LEFT)
            .setXShift(UnitValue.createPointValue(margin))
            .setPositionY(BackgroundPosition.PositionY.CENTER)
        appearance.setBackgroundImage(
            BackgroundImage.Builder()
                .setImage(logo)
                .setBackgroundSize(size)
                .setBackgroundPosition(position)
                .setBackgroundRepeat(BackgroundRepeat(BackgroundRepeat.BackgroundRepeatValue.NO_REPEAT))
                .build(),
        )
        return width + 2 * margin
    }

    companion object {
        private const val MAX_LOGO_SHARE = 0.45f
        private const val MARGIN_SHARE = 0.05f
        private const val LEADING = 1.15f
        private const val MIN_FONT_SIZE = 2f
        /** Big boxes get readable text rather than a poster. */
        private const val MAX_FONT_SIZE = 12f

        fun defaultFontFile(): Path? {
            val fonts = Path(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts")
            // Calibri first: narrow, so more text fits, and the font of the signatures made with Adobe Acrobat so far.
            return listOf("calibri.ttf", "arial.ttf", "segoeui.ttf", "tahoma.ttf").map(fonts::resolve).firstOrNull(Files::isRegularFile)
        }

        /** The company logo built into the application (an SVG resource), or null if the build has none. */
        fun defaultLogo(name: String = "logo.svg"): ByteArray? =
            PdfSigningService::class.java.getResourceAsStream(name)?.use { it.readBytes() }
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
