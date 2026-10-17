package org.example.pdfsign.signing

import kotlin.test.Test
import kotlin.test.assertEquals

class PageGeometryTest {
    private val a4 = PdfRect(0f, 0f, 595f, 842f)

    @Test
    fun `top-left coordinates on an unrotated page`() {
        val page = PageGeometry(a4, 0)
        assertEquals(PdfRect(50f, 742f, 200f, 60f), page.toUserSpace(PdfRect(50f, 40f, 200f, 60f), CoordinateOrigin.TOP_LEFT))
    }

    @Test
    fun `bottom-left coordinates are used as they are`() {
        val rect = PdfRect(50f, 40f, 200f, 60f)
        assertEquals(rect, PageGeometry(a4, 90).toUserSpace(rect, CoordinateOrigin.BOTTOM_LEFT))
    }

    @Test
    fun `crop box offset is applied`() {
        val page = PageGeometry(PdfRect(20f, 30f, 500f, 700f), 0)
        assertEquals(PdfRect(30f, 720f, 100f, 10f), page.displayToUser(PdfRect(10f, 0f, 100f, 10f)))
    }

    @Test
    fun `rotated pages swap the displayed width and height`() {
        val page = PageGeometry(a4, 90)
        assertEquals(842f, page.displayWidth)
        assertEquals(595f, page.displayHeight)
    }

    @Test
    fun `top-left corner of a page rotated by 90 degrees is the user-space origin`() {
        // /Rotate 90 turns the page clockwise: the bottom-left corner of the page ends up at the top left.
        val page = PageGeometry(a4, 90)
        assertEquals(PdfRect(0f, 0f, 60f, 200f), page.displayToUser(PdfRect(0f, 0f, 200f, 60f)))
    }

    @Test
    fun `display and user space conversions are inverse for every rotation`() {
        val display = PdfRect(40f, 70f, 180f, 55f)
        for (rotation in listOf(0, 90, 180, 270, -90, 450)) {
            val page = PageGeometry(PdfRect(10f, 20f, 595f, 842f), rotation)
            assertEquals(display, page.userToDisplay(page.displayToUser(display)), "rotation $rotation")
        }
    }

    @Test
    fun `origin names are parsed leniently`() {
        assertEquals(CoordinateOrigin.TOP_LEFT, CoordinateOrigin.parse("top-left"))
        assertEquals(CoordinateOrigin.BOTTOM_LEFT, CoordinateOrigin.parse("BOTTOM_LEFT"))
        assertEquals(null, CoordinateOrigin.parse("center"))
    }
}
