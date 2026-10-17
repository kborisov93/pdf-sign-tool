package org.example.pdfsign.ui

import kotlin.io.path.Path

/** Build-time entry point (Gradle task `generateIcon`): writes the application icon as a .ico file. */
fun main(args: Array<String>) {
    require(args.size == 1) { "Usage: IconExporter <target.ico>" }
    AppIcon.writeIco(Path(args[0]))
}
