package org.example.pdfsign.settings

import org.example.pdfsign.AppInfo
import org.example.pdfsign.signing.SignerDetails
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CopyOnWriteArrayList

data class Settings(
    /** Port of the REST API; it only listens on 127.0.0.1. */
    val port: Int = DEFAULT_PORT,
    /** Web origins allowed to call the API: "*", exact origins, or wildcard patterns (see [originMatches]). */
    val allowedOrigins: List<String> = listOf("*"),
    /** RFC 3161 timestamp server; blank for no timestamp. */
    val timestampUrl: String = "",
    /** PKCS#11 driver of a smart card that is not visible in the Windows certificate store; blank to disable. */
    val pkcs11Library: String = "",
    /** What the visible signature shows about the signer, unless a request asks for something else. */
    val signerDetails: SignerDetails = SignerDetails.NAME,
) {
    fun isOriginAllowed(origin: String): Boolean = allowedOrigins.any { originMatches(it, origin) }

    fun toProperties(): Properties = Properties().apply {
        setProperty("port", port.toString())
        setProperty("allowedOrigins", allowedOrigins.joinToString(","))
        setProperty("timestampUrl", timestampUrl)
        setProperty("pkcs11Library", pkcs11Library)
        setProperty("signerDetails", signerDetails.key)
    }

    companion object {
        const val DEFAULT_PORT = 18765

        fun fromProperties(properties: Properties) = Settings(
            port = properties.getProperty("port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 } ?: DEFAULT_PORT,
            allowedOrigins = properties.getProperty("allowedOrigins")?.let(::parseOrigins) ?: listOf("*"),
            timestampUrl = properties.getProperty("timestampUrl")?.trim().orEmpty(),
            pkcs11Library = properties.getProperty("pkcs11Library")?.trim().orEmpty(),
            signerDetails = properties.getProperty("signerDetails")?.let(SignerDetails::parse) ?: SignerDetails.NAME,
        )

        /** Splits a comma- or line-separated list of origins. */
        fun parseOrigins(text: String): List<String> =
            text.split(',', '\n', '\r', ' ', '\t').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }

        internal fun originMatches(pattern: String, origin: String): Boolean {
            if (pattern == "*") return true
            if ('*' !in pattern) return pattern.equals(origin, ignoreCase = true)
            // "*" stands for any run of host-name characters, e.g. https://*.example.com or http://localhost:*
            val regex = pattern.split('*').joinToString("[a-z0-9.-]*") { Regex.escape(it) }
            return Regex(regex, RegexOption.IGNORE_CASE).matches(origin)
        }
    }
}

class SettingsStore(private val file: Path) {
    private val log = LoggerFactory.getLogger(SettingsStore::class.java)
    private val listeners = CopyOnWriteArrayList<(old: Settings, new: Settings) -> Unit>()

    /** True until settings have been saved for the first time. */
    val isFirstRun: Boolean = !Files.exists(file)

    @Volatile
    var current: Settings = if (isFirstRun) Settings() else load()
        private set

    fun addListener(listener: (old: Settings, new: Settings) -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun update(settings: Settings) {
        val old = current
        save(settings)
        current = settings
        listeners.forEach { it(old, settings) }
    }

    private fun load(): Settings = try {
        val properties = Properties()
        Files.newBufferedReader(file).use(properties::load)
        Settings.fromProperties(properties)
    } catch (e: Exception) {
        log.warn("Cannot read settings from {}; using defaults", file, e)
        Settings()
    }

    private fun save(settings: Settings) {
        Files.createDirectories(file.parent)
        Files.newBufferedWriter(file).use { settings.toProperties().store(it, "${AppInfo.NAME} settings") }
    }
}
