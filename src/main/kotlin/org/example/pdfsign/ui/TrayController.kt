package org.example.pdfsign.ui

import org.example.pdfsign.AppInfo
import org.example.pdfsign.AppPaths
import java.awt.EventQueue
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.net.URI

/** The notification-area icon: the only permanent UI of the application. */
class TrayController(
    private val port: () -> Int,
    private val onSettings: () -> Unit,
    private val onExit: () -> Unit,
) {
    private val statusItem = MenuItem("Starting…").apply { isEnabled = false }
    private lateinit var trayIcon: TrayIcon

    /** Must be called on the event dispatch thread. */
    fun install() {
        val menu = PopupMenu().apply {
            add(MenuItem("${AppInfo.NAME} ${AppInfo.version}").apply { isEnabled = false })
            add(statusItem)
            addSeparator()
            add(menuItem("Open test page") { browse(URI("http://127.0.0.1:${port()}/"))?.let(::showError) })
            add(menuItem("Settings…") { onSettings() })
            add(menuItem("Open log folder") { openFile(AppPaths.logDir.toFile())?.let(::showError) })
            addSeparator()
            add(menuItem("Exit") { onExit() })
        }
        trayIcon = TrayIcon(AppIcon.trayImage(), AppInfo.NAME, menu).apply {
            isImageAutoSize = true
            addActionListener { onSettings() } // double-click
        }
        SystemTray.getSystemTray().add(trayIcon)
    }

    fun setStatus(text: String) = EventQueue.invokeLater {
        statusItem.label = text
        trayIcon.toolTip = "${AppInfo.NAME}: $text"
    }

    fun notify(caption: String, text: String, type: TrayIcon.MessageType = TrayIcon.MessageType.INFO) =
        EventQueue.invokeLater { trayIcon.displayMessage(caption, text, type) }

    fun remove() = EventQueue.invokeLater { SystemTray.getSystemTray().remove(trayIcon) }

    private fun showError(message: String) = notify(AppInfo.NAME, message, TrayIcon.MessageType.ERROR)

    private fun menuItem(label: String, action: () -> Unit) = MenuItem(label).apply { addActionListener { action() } }
}
