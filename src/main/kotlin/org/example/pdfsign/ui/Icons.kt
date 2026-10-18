package org.example.pdfsign.ui

import com.formdev.flatlaf.icons.FlatAbstractIcon
import java.awt.BasicStroke
import java.awt.Component
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

// Small icons drawn in code (FlatAbstractIcon scales them and turns on antialiasing), in the colours of the theme.

private val thinStroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

/** A filled circle in a status colour with a glyph, like the icons of Windows 11 info bars. */
internal class StatusIcon(private val colorKey: String, private val glyph: Glyph) : FlatAbstractIcon(16, 16, null) {
    enum class Glyph { INFO, ERROR }

    override fun paintIcon(c: Component, g: Graphics2D) {
        g.color = themeColor(colorKey)
        g.fill(Ellipse2D.Float(0f, 0f, 16f, 16f))
        g.color = themeColor("PdfSign.iconGlyphColor")
        g.stroke = BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        when (glyph) {
            Glyph.INFO -> {
                g.fill(Ellipse2D.Float(7.1f, 3.5f, 1.8f, 1.8f))
                g.draw(Line2D.Float(8f, 7.4f, 8f, 11.8f))
            }
            Glyph.ERROR -> {
                g.draw(Line2D.Float(5.6f, 5.6f, 10.4f, 10.4f))
                g.draw(Line2D.Float(10.4f, 5.6f, 5.6f, 10.4f))
            }
        }
    }
}

/** A padlock in the colour of the component's text, shown next to an https:// origin. */
internal class LockIcon : FlatAbstractIcon(12, 14, null) {
    override fun paintIcon(c: Component, g: Graphics2D) {
        g.color = c.foreground
        g.stroke = thinStroke
        g.draw(Arc2D.Float(2.75f, 1f, 6.5f, 7f, 0f, 180f, Arc2D.OPEN))
        g.draw(Line2D.Float(2.75f, 4.5f, 2.75f, 6.5f))
        g.draw(Line2D.Float(9.25f, 4.5f, 9.25f, 6.5f))
        g.fill(RoundRectangle2D.Float(0.5f, 6f, 11f, 8f, 3f, 3f))
    }
}

/** A certificate: a sheet with a seal, in the accent colour, or muted for certificates that cannot be used. */
internal class CertificateIcon : FlatAbstractIcon(24, 24, null) {
    var muted = false

    override fun paintIcon(c: Component, g: Graphics2D) {
        g.color = themeColor(if (muted) "PdfSign.secondaryForeground" else "Component.accentColor")
        g.stroke = thinStroke
        g.draw(RoundRectangle2D.Float(1.75f, 4.75f, 20.5f, 14.5f, 4f, 4f))
        g.draw(Line2D.Float(5.5f, 9f, 13f, 9f))
        g.draw(Line2D.Float(5.5f, 12.5f, 10f, 12.5f))
        g.fill(Ellipse2D.Float(14f, 12f, 6.5f, 6.5f))
        g.draw(Line2D.Float(15.5f, 18f, 15f, 22f))
        g.draw(Line2D.Float(19f, 18f, 19.5f, 22f))
    }
}
