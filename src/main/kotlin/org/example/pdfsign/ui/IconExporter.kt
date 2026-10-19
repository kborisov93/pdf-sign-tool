package org.example.pdfsign.ui

import kotlin.io.path.Path
import kotlin.io.path.extension

/** Build-time entry point (Gradle task `generateIcon`): writes the application icon as .ico, .icns or .png. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: IconExporter <target.ico|target.icns|target.png>" }
    val target = Path(args[0])
    when (target.extension.lowercase()) {
        "ico" -> AppIcon.writeIco(target)
        "icns" -> AppIcon.writeIcns(target)
        "png" -> AppIcon.writePng(target)
        else -> throw IllegalArgumentException("Unsupported icon format: $target")
    }
}
