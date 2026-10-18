package org.example.pdfsign.ui

import com.formdev.flatlaf.util.UIScale
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.LayoutManager
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.SwingConstants
import javax.swing.UIManager

/** A rounded panel in the card colours of the current theme, which groups related content. */
internal class Card(layout: LayoutManager) : JPanel(layout) {
    init {
        isOpaque = false
        border = padding(14, 16, 14, 16)
    }

    override fun paintComponent(g: Graphics) =
        paintRoundedBox(g, themeColor("PdfSign.cardBackground"), themeColor("PdfSign.cardBorderColor"), arc = 12)
}

/** The bottom bar of a dialog: optional content on the left, buttons of equal width on the right (as in Windows 11). */
internal class FooterBar(leading: JComponent?, vararg buttons: JButton) : JPanel(BorderLayout(16, 0)) {
    init {
        border = padding(14, 20, 14, 20)
        if (leading != null) add(leading, BorderLayout.CENTER)
        add(JPanel(GridLayout(1, 0, UIScale.scale(8), 0)).apply {
            isOpaque = false
            buttons.forEach { add(it.style("minimumWidth: 96")) }
        }, BorderLayout.EAST)
    }

    override fun paintComponent(g: Graphics) {
        g.color = themeColor("PdfSign.footerBackground")
        g.fillRect(0, 0, width, height)
        g.color = themeColor("PdfSign.footerBorderColor")
        g.fillRect(0, 0, width, UIScale.scale(1))
    }
}

/**
 * Plain text that wraps at the width the layout gives it, like a multi-line label. It never renders HTML, so it can
 * show messages that contain text from outside.
 */
internal class WrappingText : JTextArea() {
    init {
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
    }

    override fun updateUI() {
        super.updateUI()
        font = UIManager.getFont("Label.font")
        foreground = UIManager.getColor("Label.foreground")
        border = null
        highlighter = null
    }

    // Asks for little width: a long message wraps instead of widening the window.
    override fun getPreferredSize(): Dimension = super.getPreferredSize().apply { width = minOf(width, UIScale.scale(240)) }
}

/** A status message with an icon and wrapping text, optionally in a tinted box (an "info bar"). Hidden until shown. */
internal class Notice(private val boxed: Boolean) : JPanel(BorderLayout(UIScale.scale(10), 0)) {
    enum class Kind(val colorKey: String, val backgroundKey: String) {
        INFO("PdfSign.infoColor", "PdfSign.infoBackground"),
        PROGRESS("PdfSign.infoColor", "PdfSign.infoBackground"),
        ERROR("PdfSign.errorColor", "PdfSign.errorBackground"),
    }

    private val icon = JLabel().apply {
        verticalAlignment = SwingConstants.TOP
        border = padding(2, 0, 0, 0)
    }
    private val text = WrappingText()
    private val progress = JProgressBar().apply { isIndeterminate = true }
    private var kind = Kind.INFO

    init {
        isOpaque = false
        isVisible = false
        if (boxed) border = padding(10, 12, 10, 12)
        add(icon, BorderLayout.WEST)
        add(JPanel(BorderLayout(0, UIScale.scale(8))).apply {
            isOpaque = false
            add(text, BorderLayout.CENTER)
            add(progress, BorderLayout.SOUTH)
        }, BorderLayout.CENTER)
    }

    fun show(message: String, kind: Kind = Kind.INFO) {
        this.kind = kind
        text.text = message
        icon.icon = StatusIcon(kind.colorKey, if (kind == Kind.ERROR) StatusIcon.Glyph.ERROR else StatusIcon.Glyph.INFO)
        progress.isVisible = kind == Kind.PROGRESS
        isVisible = true
        revalidate()
        repaint()
    }

    fun clear() {
        isVisible = false
    }

    override fun paintComponent(g: Graphics) {
        if (!boxed) return
        val color = themeColor(kind.colorKey)
        paintRoundedBox(g, themeColor(kind.backgroundKey), Color(color.red, color.green, color.blue, 70), arc = 10)
    }
}

/** A small rounded label in a status colour, e.g. "Valid". */
internal class Pill : JLabel() {
    private var backgroundKey = "PdfSign.successBackground"

    init {
        border = padding(1, 8, 1, 8)
        horizontalAlignment = CENTER
        style("font: -1 \$semibold.font")
    }

    fun show(text: String, colorKey: String, backgroundKey: String) {
        this.text = text
        this.backgroundKey = backgroundKey
        foreground = themeColor(colorKey)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = themeColor(backgroundKey)
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat()))
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }
}

internal fun JComponent.paintRoundedBox(graphics: Graphics, background: Color, border: Color, arc: Int) {
    val g = graphics.create() as Graphics2D
    try {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        val a = UIScale.scale(arc).toFloat()
        val shape = RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, a, a)
        g.color = background
        g.fill(shape)
        g.color = border
        g.draw(shape)
    } finally {
        g.dispose()
    }
}
