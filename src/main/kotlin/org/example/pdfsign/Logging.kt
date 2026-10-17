package org.example.pdfsign

import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Configures slf4j-simple to write to %LOCALAPPDATA%\PdfSignTool\logs. Must run before the first logger is created.
 * Every -Dorg.slf4j.simpleLogger.* option given on the command line takes precedence.
 */
object Logging {
    private const val PREFIX = "org.slf4j.simpleLogger."

    fun init() {
        if (System.getProperty(PREFIX + "logFile") == null) {
            runCatching {
                Files.createDirectories(AppPaths.logDir)
                // slf4j-simple truncates its file on start, so keep the previous session's log next to it.
                val current = AppPaths.logDir.resolve("pdf-sign-tool.log")
                if (Files.exists(current)) {
                    Files.move(current, AppPaths.logDir.resolve("pdf-sign-tool.previous.log"), StandardCopyOption.REPLACE_EXISTING)
                }
                System.setProperty(PREFIX + "logFile", current.toString())
            }
        }
        setDefault("defaultLogLevel", "info")
        setDefault("showDateTime", "true")
        setDefault("dateTimeFormat", "yyyy-MM-dd HH:mm:ss.SSS")
        setDefault("showShortLogName", "true")
    }

    private fun setDefault(key: String, value: String) {
        if (System.getProperty(PREFIX + key) == null) System.setProperty(PREFIX + key, value)
    }
}
