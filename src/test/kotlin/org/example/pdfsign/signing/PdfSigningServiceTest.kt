package org.example.pdfsign.signing

import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfName
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.kernel.pdf.PdfStream
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject
import com.itextpdf.signatures.SignatureUtil
import org.example.pdfsign.TestPki
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfSigningServiceTest {
    private val service = PdfSigningService()

    @Test
    fun `adds a valid visible signature at the requested rectangle`() {
        val options = SignatureOptions(page = 2, rect = PdfRect(50f, 60f, 200f, 70f), reason = "Approved", location = "София")
        val signed = service.sign(request(TestPki.samplePdf(), options), TestPki.credential())

        read(signed) { pdf ->
            val signatures = SignatureUtil(pdf)
            val name = signatures.signatureNames.single()
            assertTrue(signatures.signatureCoversWholeDocument(name))
            val pkcs7 = signatures.readSignatureData(name)
            assertTrue(pkcs7.verifySignatureIntegrityAndAuthenticity())
            assertEquals("Approved", pkcs7.reason)
            assertEquals("София", pkcs7.location)
            assertEquals("Иван Петров Тестов", pkcs7.signingCertificate.subjectX500Principal.commonName())

            val widget = PdfAcroForm.getAcroForm(pdf, false)!!.getField(name)!!.widgets.single()
            assertEquals(PdfRect(50f, 60f, 200f, 70f), PdfRect.of(widget.rectangle.toRectangle()))
            assertEquals(2, pdf.getPageNumber(widget.page))
        }
    }

    @Test
    fun `a second signature keeps the first one valid`() {
        val once = service.sign(request(TestPki.samplePdf(), SignatureOptions(1, PdfRect(36f, 36f, 180f, 50f))), TestPki.credential("First Signer"))
        val twice = service.sign(
            request(once, SignatureOptions(1, PdfRect(300f, 36f, 180f, 50f), fieldName = "Approver")),
            TestPki.credential("Second Signer"),
        )

        read(twice) { pdf ->
            val signatures = SignatureUtil(pdf)
            assertEquals(listOf("Signature1", "Approver"), signatures.signatureNames)
            signatures.signatureNames.forEach { assertTrue(signatures.readSignatureData(it).verifySignatureIntegrityAndAuthenticity(), it) }
            assertTrue(signatures.signatureCoversWholeDocument("Approver"))
        }
    }

    @Test
    fun `signs with an elliptic curve key`() {
        val signed = service.sign(request(TestPki.samplePdf(1), SignatureOptions(1, PdfRect(36f, 36f, 180f, 50f))), TestPki.credential(keyAlgorithm = "EC"))
        read(signed) { pdf ->
            val signatures = SignatureUtil(pdf)
            val pkcs7 = signatures.readSignatureData(signatures.signatureNames.single())
            assertTrue(pkcs7.verifySignatureIntegrityAndAuthenticity())
            assertEquals("ECDSA", pkcs7.signatureAlgorithmName.substringAfter("with"))
        }
    }

    @Test
    fun `top-left placement on a rotated page lands where the viewer shows it`() {
        val options = SignatureOptions(1, PdfRect(0f, 0f, 200f, 60f), CoordinateOrigin.TOP_LEFT)
        val signed = service.sign(request(TestPki.samplePdf(1, rotation = 90), options), TestPki.credential())
        read(signed) { pdf ->
            val name = SignatureUtil(pdf).signatureNames.single()
            val widget = PdfAcroForm.getAcroForm(pdf, false)!!.getField(name)!!.widgets.single()
            assertEquals(PdfRect(0f, 0f, 60f, 200f), PdfRect.of(widget.rectangle.toRectangle()))
        }
    }

    @Test
    fun `draws the built-in logo at the left of the signature`() {
        val signed = service.sign(request(TestPki.samplePdf(1), SignatureOptions(1, PdfRect(36f, 36f, 240f, 70f))), TestPki.credential())
        read(signed) { pdf ->
            val signatures = SignatureUtil(pdf)
            val name = signatures.signatureNames.single()
            assertTrue(signatures.readSignatureData(name).verifySignatureIntegrityAndAuthenticity())

            val logo = assertNotNull(logoXObject(pdf, name), "logo form XObject in the appearance")
            assertEquals(202.24f / 144.87f, logo.width / logo.height, 0.01f) // the viewBox of logo.svg
        }
    }

    @Test
    fun `without a logo the appearance is text only`() {
        val textOnly = PdfSigningService(logoSvg = null)
        val signed = textOnly.sign(request(TestPki.samplePdf(1), SignatureOptions(1, PdfRect(36f, 36f, 240f, 70f))), TestPki.credential())
        read(signed) { pdf -> assertNull(logoXObject(pdf, SignatureUtil(pdf).signatureNames.single())) }
    }

    @Test
    fun `the built-in logo resources exist`() {
        assertNotNull(PdfSigningService.defaultLogo())
        assertNotNull(PdfSigningService.defaultLogo("logo-mark.svg"))
    }

    @Test
    fun `inspection rejects invalid requests`() {
        val pdf = TestPki.samplePdf(2)
        assertFailsWith<InvalidSignRequestException> { PdfInspector.inspect(pdf, SignatureOptions(3, PdfRect(0f, 0f, 100f, 50f))) }
        assertFailsWith<InvalidSignRequestException> { PdfInspector.inspect(pdf, SignatureOptions(1, PdfRect(2000f, 0f, 100f, 50f))) }
        assertFailsWith<InvalidSignRequestException> { PdfInspector.inspect(pdf, SignatureOptions(1, PdfRect(0f, 0f, 1f, 50f))) }
        assertFailsWith<InvalidSignRequestException> { PdfInspector.inspect(pdf, SignatureOptions(1, PdfRect(0f, 0f, 100f, 50f), fieldName = "a.b")) }
        assertFailsWith<InvalidSignRequestException> { PdfInspector.inspect("not a pdf".toByteArray(), SignatureOptions(1, PdfRect(0f, 0f, 100f, 50f))) }
    }

    private fun request(document: ByteArray, options: SignatureOptions) = SignRequest(
        id = "test",
        origin = null,
        fileName = "test.pdf",
        document = document,
        documentInfo = PdfInspector.inspect(document, options),
        options = options,
    )

    /** The form XObject the SVG logo was converted to (iText marks it "% svg root"), nested in the field's appearance. */
    private fun logoXObject(pdf: PdfDocument, fieldName: String): PdfFormXObject? {
        val widget = PdfAcroForm.getAcroForm(pdf, false)!!.getField(fieldName)!!.widgets.single()
        val seen = mutableSetOf<PdfStream>()
        fun search(form: PdfFormXObject): PdfFormXObject? {
            if (!seen.add(form.pdfObject)) return null
            val xObjects = form.resources.getResource(PdfName.XObject) ?: return null
            return xObjects.keySet().asSequence()
                .mapNotNull { xObjects.getAsStream(it) }
                .filter { PdfName.Form == it.getAsName(PdfName.Subtype) }
                .map { PdfFormXObject(it) }
                .firstNotNullOfOrNull { child -> child.takeIf { "% svg root" in String(it.pdfObject.bytes) } ?: search(child) }
        }
        return search(PdfFormXObject(widget.normalAppearanceObject as PdfStream))
    }

    private fun read(document: ByteArray, block: (PdfDocument) -> Unit) =
        PdfDocument(PdfReader(ByteArrayInputStream(document))).use(block)
}
