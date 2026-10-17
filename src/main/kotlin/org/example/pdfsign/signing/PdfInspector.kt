package org.example.pdfsign.signing

import com.itextpdf.forms.PdfAcroForm
import com.itextpdf.kernel.exceptions.BadPasswordException
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.signatures.SignatureUtil
import java.io.ByteArrayInputStream

class InvalidSignRequestException(message: String) : Exception(message)

/** Validates an uploaded document against the requested signature placement before the user is bothered. */
object PdfInspector {
    private const val MIN_SIZE = 4f
    private val FIELD_NAME = Regex("[A-Za-z0-9_ -]{1,64}")

    fun inspect(document: ByteArray, options: SignatureOptions): DocumentInfo {
        val rect = options.rect
        if (listOf(rect.x, rect.y, rect.width, rect.height).any { !it.isFinite() }) {
            invalid("The signature rectangle must consist of finite numbers")
        }
        if (rect.width < MIN_SIZE || rect.height < MIN_SIZE) {
            invalid("The signature rectangle must be at least ${MIN_SIZE.toInt()} x ${MIN_SIZE.toInt()} points")
        }
        if (options.fieldName != null && !FIELD_NAME.matches(options.fieldName)) {
            invalid("fieldName may contain only letters, digits, spaces, '-' and '_' (at most 64 characters)")
        }

        openDocument(document).use { pdf ->
            if (pdf.reader.isEncrypted) invalid("Encrypted PDF documents cannot be signed")
            val pageCount = pdf.numberOfPages
            if (options.page !in 1..pageCount) invalid("page must be between 1 and $pageCount")

            val geometry = PageGeometry.of(pdf.getPage(options.page))
            val signatureRect = geometry.toUserSpace(rect, options.origin)
            if (!signatureRect.intersects(geometry.cropBox)) {
                invalid("The signature rectangle lies outside the visible area of page ${options.page}")
            }
            if (options.fieldName != null && PdfAcroForm.getAcroForm(pdf, false)?.getField(options.fieldName) != null) {
                invalid("The document already has a field named '${options.fieldName}'")
            }
            return DocumentInfo(
                pageCount = pageCount,
                page = geometry,
                signatureRect = signatureRect,
                existingSignatures = SignatureUtil(pdf).signatureNames.size,
                xrefRebuilt = pdf.reader.hasRebuiltXref(),
            )
        }
    }

    private fun openDocument(bytes: ByteArray): PdfDocument = try {
        PdfDocument(PdfReader(ByteArrayInputStream(bytes)))
    } catch (_: BadPasswordException) {
        invalid("The PDF document is password protected")
    } catch (_: Exception) {
        invalid("The file is not a valid PDF document")
    }

    private fun invalid(message: String): Nothing = throw InvalidSignRequestException(message)
}
