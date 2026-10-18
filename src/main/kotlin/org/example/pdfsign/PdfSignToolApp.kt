package org.example.pdfsign

import org.example.pdfsign.api.ApiServer
import org.example.pdfsign.settings.SettingsStore
import org.example.pdfsign.settings.WindowsStartup
import org.example.pdfsign.signing.PdfSigningService
import org.example.pdfsign.signing.SignRequestStore
import org.example.pdfsign.signing.SigningCoordinator
import org.example.pdfsign.ui.SettingsDialog
import org.example.pdfsign.ui.SwingUserInteraction
import org.example.pdfsign.ui.Theme
import org.example.pdfsign.ui.TrayController
import org.slf4j.LoggerFactory
import java.awt.TrayIcon
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** Wires the components together: settings, request store, signing worker, REST API and tray icon. */
class PdfSignToolApp(private val instanceLock: SingleInstanceLock) {
    private val log = LoggerFactory.getLogger(PdfSignToolApp::class.java)
    private val settingsStore = SettingsStore(AppPaths.settingsFile)
    private val requestStore = SignRequestStore()
    private val interaction = SwingUserInteraction { settingsStore.current }
    private val coordinator = SigningCoordinator(requestStore, PdfSigningService(), interaction, { settingsStore.current.signerDetails }) {
        settingsStore.current.timestampUrl
    }
    private val server = ApiServer(requestStore, coordinator::submit) { settingsStore.current }
    private val janitor = Executors.newSingleThreadScheduledExecutor { Thread(it, "request-janitor").apply { isDaemon = true } }
    private lateinit var tray: TrayController

    fun start() {
        val firstRun = settingsStore.isFirstRun
        if (firstRun) {
            settingsStore.update(settingsStore.current) // persist the defaults
            if (WindowsStartup.isSupported) WindowsStartup.setEnabled(true)
        }

        SwingUtilities.invokeAndWait {
            tray = TrayController(
                port = { settingsStore.current.port },
                onSettings = {
                    Theme.followSystem()
                    SettingsDialog.show(settingsStore)
                },
                onExit = ::exit,
            )
            tray.install()
            interaction.tray = tray
        }

        coordinator.start()
        janitor.scheduleWithFixedDelay({ requestStore.purgeExpired() }, 1, 1, TimeUnit.MINUTES)
        settingsStore.addListener { old, new ->
            if (old.port != new.port) thread(name = "api-restart") { startServer(new.port) }
        }
        startServer(settingsStore.current.port)

        if (firstRun) {
            tray.notify(AppInfo.NAME, "${AppInfo.NAME} runs in the notification area and signs PDF documents for web applications.")
        }
    }

    private fun startServer(port: Int) {
        try {
            server.start(port)
            log.info("API listening on http://127.0.0.1:{}", port)
            tray.setStatus("Listening on 127.0.0.1:$port")
        } catch (e: Exception) {
            log.error("Cannot start the API on port {}", port, e)
            tray.setStatus("Not running: port $port is unavailable")
            tray.notify(
                "${AppInfo.NAME} cannot receive requests",
                "Port $port is already in use. Choose another port in Settings.",
                TrayIcon.MessageType.ERROR,
            )
        }
    }

    private fun exit() {
        log.info("Exiting")
        tray.remove()
        thread(name = "shutdown") {
            runCatching { server.stop() }
            coordinator.stop()
            instanceLock.close()
            exitProcess(0)
        }
    }
}
