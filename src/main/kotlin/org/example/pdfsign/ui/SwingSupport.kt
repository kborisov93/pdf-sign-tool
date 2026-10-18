package org.example.pdfsign.ui

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.UIScale
import org.example.pdfsign.signing.userMessage
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Desktop
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.net.URI
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.UIManager
import javax.swing.border.Border
import javax.swing.border.EmptyBorder

private val log = LoggerFactory.getLogger("org.example.pdfsign.ui.SwingSupport")

/** A colour of the current theme, e.g. "PdfSign.errorColor" (see the themes/ properties files). */
internal fun themeColor(key: String): Color = UIManager.getColor(key) ?: Color.GRAY

/**
 * A label for text that comes from outside (web origin, file name, reason, certificate names). Swing renders text
 * starting with "<html>" as HTML, which a web page must not be able to inject into the approval dialog.
 */
internal fun plainLabel(text: String = "", bold: Boolean = false): JLabel = JLabel().apply {
    putClientProperty("html.disable", true)
    this.text = text
    if (bold) font = font.deriveFont(Font.BOLD)
}

/** Explanations and other secondary text, smaller and in a quieter colour (which follows theme changes). */
internal fun secondaryLabel(text: String = "", smaller: Boolean = false): JLabel = plainLabel(text).apply {
    style("foreground: \$PdfSign.secondaryForeground" + if (smaller) "; font: -1" else "")
}

/**
 * Empty space around a component, scaled like FlatLaf's own insets. Unlike FlatEmptyBorder, which is a UIResource,
 * it is kept when the theme switches between light and dark.
 */
internal fun padding(top: Int, left: Int, bottom: Int, right: Int): Border =
    EmptyBorder(UIScale.scale(top), UIScale.scale(left), UIScale.scale(bottom), UIScale.scale(right))

/** A FlatLaf style class: "h2", "semibold", "small", ... */
internal fun <T : JComponent> T.styleClass(name: String): T = apply {
    putClientProperty(FlatClientProperties.STYLE_CLASS, name)
}

/** FlatLaf style properties, e.g. "foreground: \$Label.disabledForeground". They are applied again on theme changes. */
internal fun <T : JComponent> T.style(style: String): T = apply {
    putClientProperty(FlatClientProperties.STYLE, style)
}

/** Components side by side at their preferred sizes, [gap] apart, without FlowLayout's gaps at the edges. */
internal fun flowRow(vararg components: JComponent, gap: Int = 8): JPanel = JPanel(FlowLayout(FlowLayout.LEADING, 0, 0)).apply {
    isOpaque = false
    components.forEachIndexed { index, component ->
        if (index > 0) add(Box.createHorizontalStrut(UIScale.scale(gap)))
        add(component)
    }
}

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
