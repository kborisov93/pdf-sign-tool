package org.example.pdfsign.signing

import com.itextpdf.kernel.geom.Rectangle
import com.itextpdf.kernel.pdf.PdfPage

/** A rectangle in PDF points (1/72 inch). */
data class PdfRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val right: Float get() = x + width
    val top: Float get() = y + height

    fun intersects(other: PdfRect): Boolean = x < other.right && other.x < right && y < other.top && other.y < top

    fun toRectangle() = Rectangle(x, y, width, height)

    companion object {
        fun of(rectangle: Rectangle) = PdfRect(rectangle.x, rectangle.y, rectangle.width, rectangle.height)
    }
}

enum class CoordinateOrigin {
    /** PDF user space: origin at the bottom-left corner of the unrotated page, y grows upwards. */
    BOTTOM_LEFT,

    /**
     * The page as a viewer displays it (crop box, with the page rotation applied): origin at the top-left corner,
     * y grows downwards. Matches a PDF.js viewport at scale 1.
     */
    TOP_LEFT;

    companion object {
        /** Accepts "bottom-left", "top_left", "TOP_LEFT", ... */
        fun parse(value: String): CoordinateOrigin? =
            entries.firstOrNull { it.name.equals(value.trim().replace('-', '_'), ignoreCase = true) }
    }
}

/**
 * The visible area of a page: its crop box and its rotation (/Rotate, clockwise).
 * "Display" coordinates are relative to the top-left corner of the page as it is shown on screen.
 */
class PageGeometry(val cropBox: PdfRect, rotation: Int) {
    val rotation: Int = Math.floorMod(rotation, 360).let { if (it % 90 == 0) it else 0 }

    private val quarterTurn = this.rotation == 90 || this.rotation == 270
    val displayWidth: Float = if (quarterTurn) cropBox.height else cropBox.width
    val displayHeight: Float = if (quarterTurn) cropBox.width else cropBox.height

    fun toUserSpace(rect: PdfRect, origin: CoordinateOrigin): PdfRect = when (origin) {
        CoordinateOrigin.BOTTOM_LEFT -> rect
        CoordinateOrigin.TOP_LEFT -> displayToUser(rect)
    }

    fun displayToUser(r: PdfRect): PdfRect {
        val w = cropBox.width
        val h = cropBox.height
        val relative = when (rotation) {
            90 -> PdfRect(r.y, r.x, r.height, r.width)
            180 -> PdfRect(w - r.x - r.width, r.y, r.width, r.height)
            270 -> PdfRect(w - r.y - r.height, h - r.x - r.width, r.height, r.width)
            else -> PdfRect(r.x, h - r.y - r.height, r.width, r.height)
        }
        return relative.copy(x = relative.x + cropBox.x, y = relative.y + cropBox.y)
    }

    fun userToDisplay(r: PdfRect): PdfRect {
        val w = cropBox.width
        val h = cropBox.height
        val px = r.x - cropBox.x
        val py = r.y - cropBox.y
        return when (rotation) {
            90 -> PdfRect(py, px, r.height, r.width)
            180 -> PdfRect(w - px - r.width, py, r.width, r.height)
            270 -> PdfRect(h - py - r.height, w - px - r.width, r.height, r.width)
            else -> PdfRect(px, h - py - r.height, r.width, r.height)
        }
    }

    companion object {
        fun of(page: PdfPage) = PageGeometry(PdfRect.of(page.cropBox), page.rotation)
    }
}
