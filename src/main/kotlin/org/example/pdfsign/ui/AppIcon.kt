package org.example.pdfsign.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Image
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.Icon
import javax.swing.ImageIcon
import kotlin.math.roundToInt

/** The application icon, drawn in code so the tray, the windows and the installer share one design. */
object AppIcon {
    private val outline = Color(0x37, 0x47, 0x4F)
    private val fold = Color(0xCF, 0xD8, 0xDC)
    private val textLine = Color(0x90, 0xA4, 0xAE)
    private val ink = Color(0x15, 0x65, 0xC0)

    val windowImages: List<Image> by lazy { listOf(16, 20, 24, 32, 40, 48, 64, 128).map(::render) }

    fun trayImage(): Image = BaseMultiResolutionImage(*listOf(16, 20, 24, 32, 40, 48, 64).map(::render).toTypedArray())

    /** An icon of [size] that stays sharp on scaled displays. */
    fun icon(size: Int): Icon =
        ImageIcon(BaseMultiResolutionImage(*listOf(1.0, 1.25, 1.5, 1.75, 2.0).map { render((size * it).roundToInt()) }.toTypedArray()))

    /** A page with a folded corner and a blue signature stroke, designed on a 64 x 64 grid. */
    fun render(size: Int): BufferedImage {
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.scale(size / 64.0, size / 64.0)
            val stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

            val page = Path2D.Double().apply {
                moveTo(11.0, 4.0); lineTo(40.0, 4.0); lineTo(53.0, 17.0); lineTo(53.0, 60.0); lineTo(11.0, 60.0); closePath()
            }
            g.color = Color.WHITE
            g.fill(page)
            g.color = outline
            g.stroke = stroke
            g.draw(page)

            val corner = Path2D.Double().apply { moveTo(40.0, 4.0); lineTo(40.0, 17.0); lineTo(53.0, 17.0); closePath() }
            g.color = fold
            g.fill(corner)
            g.color = outline
            g.draw(corner)

            g.color = textLine
            g.draw(Line2D.Double(19.0, 22.0, 32.0, 22.0))
            g.draw(Line2D.Double(19.0, 30.0, 45.0, 30.0))
            g.draw(Line2D.Double(19.0, 38.0, 41.0, 38.0))

            g.color = ink
            g.stroke = BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Path2D.Double().apply {
                moveTo(17.0, 53.0)
                curveTo(20.0, 42.0, 27.0, 42.0, 25.0, 50.0)
                curveTo(23.5, 56.0, 30.0, 56.0, 33.0, 48.0)
                curveTo(35.0, 44.0, 37.0, 52.0, 41.0, 50.0)
                lineTo(47.0, 48.0)
            })
        } finally {
            g.dispose()
        }
        return image
    }

    /** Writes a Windows .ico with BMP entries up to 128 px and a PNG entry for 256 px. */
    fun writeIco(target: Path, sizes: List<Int> = listOf(16, 20, 24, 32, 40, 48, 64, 128, 256)) {
        val entries = sizes.map { it to encodeIconImage(render(it)) }
        val directory = ByteBuffer.allocate(6 + 16 * entries.size).order(ByteOrder.LITTLE_ENDIAN)
        directory.putShort(0).putShort(1).putShort(entries.size.toShort())
        var offset = 6 + 16 * entries.size
        for ((size, data) in entries) {
            val dimension = (if (size >= 256) 0 else size).toByte() // 0 means 256
            directory.put(dimension).put(dimension).put(0).put(0)
            directory.putShort(1).putShort(32)
            directory.putInt(data.size).putInt(offset)
            offset += data.size
        }
        val out = ByteArrayOutputStream()
        out.write(directory.array())
        entries.forEach { out.write(it.second) }
        write(target, out.toByteArray())
    }

    /** Writes a macOS .icns with PNG entries from 16 px up to 512 px @2x. */
    fun writeIcns(target: Path) {
        // Element types for PNG data (macOS 10.7 and later); the @2x types hold twice the pixels of their point size.
        val types = listOf(
            "icp4" to 16, "icp5" to 32, "ic11" to 32, "ic12" to 64, "ic07" to 128,
            "ic13" to 256, "ic08" to 256, "ic14" to 512, "ic09" to 512, "ic10" to 1024,
        )
        val elements = types.map { (type, size) -> type to encodePng(render(size)) }
        // Big-endian: the "icns" header with the file length, then each element as type, length (with header) and data.
        val buffer = ByteBuffer.allocate(8 + elements.sumOf { 8 + it.second.size })
        buffer.put("icns".toByteArray(Charsets.US_ASCII)).putInt(buffer.capacity())
        for ((type, data) in elements) buffer.put(type.toByteArray(Charsets.US_ASCII)).putInt(8 + data.size).put(data)
        write(target, buffer.array())
    }

    /** Writes the icon as a PNG of [size] pixels (the Linux launcher icon). */
    fun writePng(target: Path, size: Int = 256) = write(target, encodePng(render(size)))

    private fun write(target: Path, data: ByteArray) {
        target.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.write(target, data)
    }

    private fun encodePng(image: BufferedImage): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    private fun encodeIconImage(image: BufferedImage): ByteArray {
        if (image.width >= 256) return encodePng(image)

        // BITMAPINFOHEADER (height counts the XOR and the AND bitmap), bottom-up BGRA pixels, 1-bit AND mask.
        val w = image.width
        val h = image.height
        val maskStride = (w + 31) / 32 * 4
        val buffer = ByteBuffer.allocate(40 + w * h * 4 + maskStride * h).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(40).putInt(w).putInt(h * 2).putShort(1).putShort(32).putInt(0)
            .putInt(w * h * 4 + maskStride * h).putInt(0).putInt(0).putInt(0).putInt(0)
        for (y in h - 1 downTo 0) {
            for (x in 0 until w) {
                val argb = image.getRGB(x, y)
                buffer.put(argb.toByte()).put((argb shr 8).toByte()).put((argb shr 16).toByte()).put((argb ushr 24).toByte())
            }
        }
        for (y in h - 1 downTo 0) {
            val row = ByteArray(maskStride)
            for (x in 0 until w) {
                if (image.getRGB(x, y) ushr 24 == 0) row[x / 8] = (row[x / 8].toInt() or (0x80 ushr (x % 8))).toByte()
            }
            buffer.put(row)
        }
        return buffer.array()
    }
}
