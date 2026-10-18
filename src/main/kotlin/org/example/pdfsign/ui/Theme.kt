package org.example.pdfsign.ui

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import org.example.pdfsign.AppInfo
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The look and feel: FlatLaf in the blue of the application icon, light or dark like the user's Windows apps.
 * The customisations, including the application's own colours ("PdfSign.*" keys), are in the themes/ properties files.
 */
object Theme {
    private const val PERSONALIZE_KEY = """HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"""
    private val log = LoggerFactory.getLogger(Theme::class.java)

    @Volatile
    private var dark: Boolean? = null

    /** Installs the theme matching the Windows app mode. Call before the first window is created. */
    fun install() = apply(systemUsesDarkMode())

    /**
     * Switches between light and dark when the user changed the Windows app mode since the theme was installed, so
     * that a tray application running for days follows the system. Call it from any thread before opening a window.
     */
    fun followSystem() {
        val dark = systemUsesDarkMode()
        if (SwingUtilities.isEventDispatchThread()) apply(dark) else SwingUtilities.invokeLater { apply(dark) }
    }

    fun apply(dark: Boolean) {
        if (this.dark == dark) return
        val installed = this.dark != null
        if (!installed) FlatLaf.registerCustomDefaultsSource("org.example.pdfsign.ui.themes", Theme::class.java.classLoader)
        if (dark) FlatDarkLaf.setup() else FlatLightLaf.setup()
        if (installed) FlatLaf.updateUI()
        this.dark = dark
    }

    /** "Choose your app mode" in the Windows personalization settings. */
    private fun systemUsesDarkMode(): Boolean {
        if (!AppInfo.isWindows) return false
        return try {
            val process = ProcessBuilder("reg.exe", "query", PERSONALIZE_KEY, "/v", "AppsUseLightTheme")
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)
            Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x0\b""").containsMatchIn(output)
        } catch (e: Exception) {
            log.warn("Cannot read the Windows app mode", e)
            false
        }
    }
}
