package org.example.pdfsign.signing

import java.security.cert.X509Certificate
import java.time.Instant

enum class SignStatus {
    /** Waiting for the user to approve the request and choose a certificate. */
    PENDING,

    /** Approved; the document is being signed (the smart card may be asking for its PIN). */
    SIGNING,
    SIGNED,

    /** Rejected by the user or cancelled by the client. */
    CANCELLED,
    FAILED;

    val isFinal: Boolean get() = this == SIGNED || this == CANCELLED || this == FAILED
}

/** What the client asked for. */
data class SignatureOptions(
    /** 1-based page number. */
    val page: Int,
    val rect: PdfRect,
    val origin: CoordinateOrigin = CoordinateOrigin.BOTTOM_LEFT,
    val reason: String? = null,
    val location: String? = null,
    val contactInfo: String? = null,
    val fieldName: String? = null,
)

/** What [PdfInspector] found out about the uploaded document. */
data class DocumentInfo(
    val pageCount: Int,
    /** Geometry of the page that receives the signature. */
    val page: PageGeometry,
    /** Signature rectangle in PDF user space. */
    val signatureRect: PdfRect,
    val existingSignatures: Int,
    /** The cross-reference table was broken and had to be rebuilt; incremental updates are impossible. */
    val xrefRebuilt: Boolean,
)

data class SignerInfo(val commonName: String, val subject: String, val issuer: String, val serialNumber: String) {
    companion object {
        fun of(certificate: X509Certificate) = SignerInfo(
            commonName = certificate.subjectX500Principal.commonName(),
            subject = certificate.subjectX500Principal.name,
            issuer = certificate.issuerX500Principal.name,
            serialNumber = certificate.serialNumber.toString(16).uppercase(),
        )
    }
}

/** One signing request. Status changes are atomic, so the API, the UI and the signing thread can race safely. */
class SignRequest(
    val id: String,
    /** Origin header of the web page that created the request; null for non-browser clients. */
    val origin: String?,
    val fileName: String,
    val document: ByteArray,
    val documentInfo: DocumentInfo,
    val options: SignatureOptions,
    val createdAt: Instant = Instant.now(),
) {
    @Volatile
    var status: SignStatus = SignStatus.PENDING
        private set

    @Volatile
    var updatedAt: Instant = createdAt
        private set

    @Volatile
    var signedDocument: ByteArray? = null
        private set

    @Volatile
    var signer: SignerInfo? = null
        private set

    @Volatile
    var error: String? = null
        private set

    val signedFileName: String get() = fileName.replace(Regex("(?i)\\.pdf$"), "") + "-signed.pdf"

    @Synchronized
    fun startSigning(): Boolean = move(SignStatus.PENDING, SignStatus.SIGNING)

    /** Signing failed but the user may try again. */
    @Synchronized
    fun returnToPending(): Boolean = move(SignStatus.SIGNING, SignStatus.PENDING)

    @Synchronized
    fun cancel(): Boolean = move(SignStatus.PENDING, SignStatus.CANCELLED)

    @Synchronized
    fun complete(document: ByteArray, signer: SignerInfo): Boolean {
        if (status != SignStatus.SIGNING) return false
        signedDocument = document
        this.signer = signer
        return move(SignStatus.SIGNING, SignStatus.SIGNED)
    }

    @Synchronized
    fun fail(message: String): Boolean {
        if (status.isFinal) return false
        error = message
        status = SignStatus.FAILED
        updatedAt = Instant.now()
        return true
    }

    private fun move(from: SignStatus, to: SignStatus): Boolean {
        if (status != from) return false
        status = to
        updatedAt = Instant.now()
        return true
    }
}
