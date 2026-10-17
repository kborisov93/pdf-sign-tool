package org.example.pdfsign.ui

import org.example.pdfsign.signing.PageGeometry
import org.example.pdfsign.signing.PdfRect
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import kotlin.math.min

/** A thumbnail of the page outline (as displayed, rotation applied) with the signature rectangle highlighted. */
class PlacementPreview(private val page: PageGeometry, signatureRect: PdfRect) : JComponent() {
    private val signature = page.userToDisplay(signatureRect)

    init {
        preferredSize = Dimension(100, 120)
        minimumSize = preferredSize
        toolTipText = "Where the signature will appear on the page"
    }

    override fun paintComponent(graphics: Graphics) {
        val g = graphics.create() as Graphics2D
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val scale = min((width - 8) / page.displayWidth.toDouble(), (height - 8) / page.displayHeight.toDouble())
            val pageWidth = page.displayWidth * scale
            val pageHeight = page.displayHeight * scale
            val left = (width - pageWidth) / 2
            val top = (height - pageHeight) / 2

            g.color = Color(0, 0, 0, 40)
            g.fill(Rectangle2D.Double(left + 2, top + 2, pageWidth, pageHeight))
            g.color = Color.WHITE
            g.fill(Rectangle2D.Double(left, top, pageWidth, pageHeight))
            g.color = Color.GRAY
            g.draw(Rectangle2D.Double(left, top, pageWidth, pageHeight))

            // Suggest text so the page reads as a page.
            g.color = Color(0xDD, 0xDD, 0xDD)
            var y = top + pageHeight * 0.12
            while (y < top + pageHeight * 0.9) {
                g.draw(Line2D.Double(left + pageWidth * 0.12, y, left + pageWidth * 0.88, y))
                y += pageHeight * 0.07
            }

            // Clip to the page in case the rectangle sticks out.
            g.clip(Rectangle2D.Double(left, top, pageWidth + 1, pageHeight + 1))
            val box = Rectangle2D.Double(
                left + signature.x * scale,
                top + signature.y * scale,
                maxOf(signature.width * scale, 2.0),
                maxOf(signature.height * scale, 2.0),
            )
            g.color = Color(0x15, 0x65, 0xC0, 70)
            g.fill(box)
            g.color = Color(0x15, 0x65, 0xC0)
            g.stroke = BasicStroke(1.5f)
            g.draw(box)
        } finally {
            g.dispose()
        }
    }
}
