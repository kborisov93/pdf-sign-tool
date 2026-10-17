package org.example.pdfsign.settings

import org.example.pdfsign.AppInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

/** Registers the installed launcher under HKCU\...\Run so the tray app starts when the user signs in. */
object WindowsStartup {
    private const val RUN_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Run"""
    private val log = LoggerFactory.getLogger(WindowsStartup::class.java)

    /** Set by the jpackage launcher; null when running from Gradle or the IDE. */
    private val launcherPath: String? = System.getProperty("jpackage.app-path")?.takeIf { AppInfo.isWindows }

    val isSupported: Boolean get() = launcherPath != null

    fun isEnabled(): Boolean = isSupported && reg("query", RUN_KEY, "/v", AppInfo.NAME)

    fun setEnabled(enabled: Boolean) {
        val path = launcherPath ?: return
        val ok = if (enabled) {
            // The escaped quotes end up in the registry value, which keeps the path with spaces unambiguous.
            reg("add", RUN_KEY, "/v", AppInfo.NAME, "/t", "REG_SZ", "/d", "\\\"$path\\\"", "/f")
        } else {
            reg("delete", RUN_KEY, "/v", AppInfo.NAME, "/f")
        }
        if (ok) log.info("Start with Windows {}", if (enabled) "enabled" else "disabled")
        else log.warn("Could not {} start with Windows", if (enabled) "enable" else "disable")
    }

    private fun reg(vararg args: String): Boolean = try {
        val process = ProcessBuilder(listOf("reg.exe") + args).redirectErrorStream(true).start()
        process.inputStream.readAllBytes()
        process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: Exception) {
        log.warn("reg.exe failed", e)
        false
    }
}
