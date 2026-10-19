package org.example.pdfsign

import java.nio.file.Path
import kotlin.io.path.Path

object AppInfo {
    const val NAME = "PDF Sign Tool"
    const val API_VERSION = 1

    /** Taken from the jar manifest; "dev" when running from the IDE. */
    val version: String = AppInfo::class.java.`package`?.implementationVersion ?: "dev"

    val isWindows: Boolean = System.getProperty("os.name").startsWith("Windows")
    val isMac: Boolean = System.getProperty("os.name").startsWith("Mac")
}

object AppPaths {
    /** Roaming user data, e.g. %APPDATA%\PdfSignTool. */
    val dataDir: Path = baseDir("APPDATA").resolve("PdfSignTool")

    /** Machine-local user data, e.g. %LOCALAPPDATA%\PdfSignTool. */
    val localDir: Path = baseDir("LOCALAPPDATA").resolve("PdfSignTool")

    val settingsFile: Path = dataDir.resolve("settings.properties")
    val lockFile: Path = localDir.resolve("instance.lock")
    val logDir: Path = localDir.resolve("logs")

    private fun baseDir(environmentVariable: String): Path =
        System.getenv(environmentVariable)?.takeIf { it.isNotBlank() }?.let { Path(it) }
            ?: Path(System.getProperty("user.home"), ".pdf-sign-tool")
}
