package org.example.pdfsign

import org.example.pdfsign.ui.Theme
import org.slf4j.LoggerFactory
import java.awt.GraphicsEnvironment
import java.awt.SystemTray
import javax.swing.JOptionPane
import kotlin.system.exitProcess

fun main() {
    // Take the lock before logging starts: a second instance must not rotate or truncate the running instance's log.
    val instanceLock = SingleInstanceLock.tryAcquire(AppPaths.lockFile)
    Logging.init(toFile = instanceLock != null)
    val log = LoggerFactory.getLogger("org.example.pdfsign.Main")
    Thread.setDefaultUncaughtExceptionHandler { thread, e -> log.error("Uncaught exception in thread {}", thread.name, e) }
    log.info("Starting {} {} on Java {}", AppInfo.NAME, AppInfo.version, Runtime.version())

    if (!GraphicsEnvironment.isHeadless()) Theme.install()
    if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
        log.error("The system tray is not available")
        if (!GraphicsEnvironment.isHeadless()) {
            JOptionPane.showMessageDialog(null, "${AppInfo.NAME} needs the system tray, which is not available.", AppInfo.NAME, JOptionPane.ERROR_MESSAGE)
        }
        exitProcess(1)
    }

    if (instanceLock == null) {
        log.info("Another instance is already running")
        JOptionPane.showMessageDialog(
            null,
            "${AppInfo.NAME} is already running.\nLook for its icon in the notification area of the taskbar.",
            AppInfo.NAME,
            JOptionPane.INFORMATION_MESSAGE,
        )
        exitProcess(0)
    }

    PdfSignToolApp(instanceLock).start()
}
