package org.example.pdfsign.signing

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.example.pdfsign.AppInfo
import java.security.AuthProvider
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateParsingException
import java.security.cert.X509Certificate
import java.util.Date
import javax.naming.InvalidNameException
import javax.naming.ldap.LdapName
import javax.security.auth.x500.X500Principal
import kotlin.io.path.Path

/** A private key (usually on a smart card) together with its certificate chain, signing certificate first. */
class SigningCredential(val privateKey: PrivateKey, val chain: List<X509Certificate>, val provider: Provider?) {
    init {
        require(chain.isNotEmpty()) { "The certificate chain is empty" }
    }

    val certificate: X509Certificate get() = chain.first()
}

enum class Validity(val label: String) { VALID("Valid"), EXPIRED("Expired"), NOT_YET_VALID("Not yet valid") }

class CertificateEntry(val alias: String, val chain: List<X509Certificate>) {
    val certificate: X509Certificate get() = chain.first()
    val subjectName: String = certificate.subjectX500Principal.commonName()
    val issuerName: String = certificate.issuerX500Principal.commonName()
    val validity: Validity get() = certificate.validity()

    /** Looks like a personal signing certificate rather than a device, TLS or self-signed one. */
    val isRecommended: Boolean = certificate.isDocumentSigningCandidate()
}

/** An opened key store. Closing it logs out of the token, if the source required a login. */
class LoadedKeyStore(
    private val keyStore: KeyStore,
    val entries: List<CertificateEntry>,
    private val onClose: () -> Unit = {},
) : AutoCloseable {

    fun credentialFor(entry: CertificateEntry): SigningCredential {
        val key = keyStore.getKey(entry.alias, null) as? PrivateKey
            ?: throw GeneralSecurityException("The private key of '${entry.subjectName}' is not accessible")
        return SigningCredential(key, entry.chain, keyStore.provider)
    }

    override fun close() = onClose()
}

interface CertificateSource {
    val displayName: String

    /** Whether [load] needs the token PIN; otherwise the provider asks for it itself when signing. */
    val needsPin: Boolean

    fun load(pin: CharArray?): LoadedKeyStore

    companion object {
        fun available(pkcs11Library: String): List<CertificateSource> = buildList {
            if (AppInfo.isWindows) add(WindowsCertificateStore)
            if (pkcs11Library.isNotBlank()) add(Pkcs11Token(pkcs11Library))
        }
    }
}

/**
 * The current user's personal certificate store (SunMSCAPI). Smart cards with a Windows minidriver show up here
 * automatically; Windows asks for the PIN when the key is used.
 */
object WindowsCertificateStore : CertificateSource {
    override val displayName = "Windows certificate store"
    override val needsPin = false

    override fun load(pin: CharArray?): LoadedKeyStore {
        val keyStore = KeyStore.getInstance("Windows-MY")
        keyStore.load(null, null)
        return LoadedKeyStore(keyStore, keyStore.signingEntries())
    }
}

/** A smart card accessed directly through its PKCS#11 driver (SunPKCS11). */
class Pkcs11Token(private val library: String) : CertificateSource {
    override val displayName = "Smart card via PKCS#11 (${Path(library).fileName})"
    override val needsPin = true

    override fun load(pin: CharArray?): LoadedKeyStore {
        // Inline configuration; backslashes must be escaped inside the quoted path.
        val config = "--name=PdfSignTool\nlibrary = \"${library.replace("\\", "\\\\")}\"\n"
        val baseProvider = Security.getProvider("SunPKCS11")
            ?: throw GeneralSecurityException("This Java runtime has no PKCS#11 support")
        val provider = baseProvider.configure(config)
        val keyStore = KeyStore.getInstance("PKCS11", provider)
        keyStore.load(null, pin)
        return LoadedKeyStore(keyStore, keyStore.signingEntries()) {
            runCatching { (provider as? AuthProvider)?.logout() }
        }
    }
}

private fun KeyStore.signingEntries(): List<CertificateEntry> =
    aliases().toList().mapNotNull { alias ->
        if (!isKeyEntry(alias)) return@mapNotNull null
        val certificate = getCertificate(alias) as? X509Certificate ?: return@mapNotNull null
        if (!certificate.allowsSigning()) return@mapNotNull null
        val chain = getCertificateChain(alias)?.filterIsInstance<X509Certificate>()?.takeIf { it.isNotEmpty() }
            ?: listOf(certificate)
        CertificateEntry(alias, chain)
    }.sortedWith(
        compareBy<CertificateEntry> { !it.isRecommended }
            .thenBy { it.validity != Validity.VALID }
            .thenBy { it.certificate.keyUsage?.getOrNull(1) != true } // non-repudiation (qualified) certificates first
            .thenBy { it.subjectName.lowercase() }
    )

/** Extended key usages that permit signing documents. */
private val DOCUMENT_SIGNING_USAGES = setOf(
    "2.5.29.37.0",             // any extended key usage
    "1.3.6.1.5.5.7.3.4",       // e-mail protection: typical for qualified signature certificates
    "1.3.6.1.5.5.7.3.36",      // document signing
    "1.3.6.1.4.1.311.10.3.12", // Microsoft document signing
    "1.2.840.113583.1.1.5",    // Adobe Authentic Documents Trust
)

/**
 * Hides what Windows keeps in the personal store besides signing certificates: self-signed device certificates
 * (Entra ID, Intune, ...), TLS certificates and client-authentication-only certificates.
 */
fun X509Certificate.isDocumentSigningCandidate(): Boolean {
    if (subjectX500Principal == issuerX500Principal) return false
    val usages = try {
        extendedKeyUsage
    } catch (_: CertificateParsingException) {
        null
    } ?: return true
    return usages.any { it in DOCUMENT_SIGNING_USAGES }
}

fun X509Certificate.validity(now: Date = Date()): Validity = when {
    now.before(notBefore) -> Validity.NOT_YET_VALID
    now.after(notAfter) -> Validity.EXPIRED
    else -> Validity.VALID
}

/** True unless the key usage extension rules out digital signatures (e.g. encryption-only certificates). */
fun X509Certificate.allowsSigning(): Boolean =
    keyUsage?.let { it.getOrElse(0) { false } || it.getOrElse(1) { false } } ?: true

/**
 * The subject the way Adobe Acrobat prints it in a signature: most specific part first, lowercase keys, e.g.
 * "email=…, o=Key Development, 2.5.4.97=NTRBG-…, cn=…, c=BG". Attributes without a short key keep their OID.
 */
fun X500Principal.displayDn(): String =
    X500Name.getInstance(encoded).rdNs.reversed().flatMap { it.typesAndValues.asList() }
        .joinToString(", ") { "${DN_KEYS[it.type.id] ?: it.type.id}=${IETFUtils.valueToString(it.value)}" }

private val DN_KEYS = mapOf(
    "2.5.4.3" to "cn", "2.5.4.4" to "sn", "2.5.4.5" to "serialNumber", "2.5.4.6" to "c", "2.5.4.7" to "l",
    "2.5.4.8" to "st", "2.5.4.9" to "street", "2.5.4.10" to "o", "2.5.4.11" to "ou", "2.5.4.12" to "title",
    "2.5.4.42" to "givenName", "1.2.840.113549.1.9.1" to "email", "0.9.2342.19200300.100.1.1" to "uid",
    "0.9.2342.19200300.100.1.25" to "dc",
)

fun X500Principal.commonName(): String = try {
    LdapName(name).rdns.lastOrNull { it.type.equals("CN", ignoreCase = true) }?.value?.toString()
} catch (_: InvalidNameException) {
    null
} ?: name
