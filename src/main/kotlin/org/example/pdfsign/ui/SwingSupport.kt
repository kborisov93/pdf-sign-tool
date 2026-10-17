package org.example.pdfsign.ui

import org.example.pdfsign.signing.userMessage
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Desktop
import java.awt.Font
import java.io.File
import java.net.URI
import javax.swing.BorderFactory
import javax.swing.JLabel
import javax.swing.UIManager
import javax.swing.border.Border

private val log = LoggerFactory.getLogger("org.example.pdfsign.ui.SwingSupport")

internal val errorColor = Color(0xB0, 0x00, 0x20)

/**
 * A label for text that comes from outside (web origin, file name, reason, certificate names). Swing renders text
 * starting with "<html>" as HTML, which a web page must not be able to inject into the approval dialog.
 */
internal fun plainLabel(text: String = "", bold: Boolean = false): JLabel = JLabel().apply {
    putClientProperty("html.disable", true)
    this.text = text
    if (bold) font = font.deriveFont(Font.BOLD)
}

internal fun JLabel.showText(text: String, error: Boolean = false) {
    this.text = text
    foreground = if (error) errorColor else UIManager.getColor("Label.foreground")
}

internal fun hintLabel(text: String): JLabel = JLabel(text).apply {
    font = font.deriveFont(font.size2D - 1f)
    foreground = UIManager.getColor("Label.disabledForeground") ?: Color.GRAY
}

internal fun titledBorder(title: String): Border = BorderFactory.createCompoundBorder(
    BorderFactory.createTitledBorder(title),
    BorderFactory.createEmptyBorder(4, 8, 8, 8),
)

internal fun String.ellipsize(max: Int): String = if (length <= max) this else take(max - 1) + "…"

internal fun browse(uri: URI) = desktopAction("open $uri") { Desktop.getDesktop().browse(uri) }

internal fun openFile(file: File) = desktopAction("open $file") { Desktop.getDesktop().open(file) }

private fun desktopAction(description: String, action: () -> Unit): String? = try {
    action()
    null
} catch (e: Exception) {
    log.warn("Cannot {}", description, e)
    e.userMessage()
}
