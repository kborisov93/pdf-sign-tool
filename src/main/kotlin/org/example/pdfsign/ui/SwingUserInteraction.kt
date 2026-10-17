package org.example.pdfsign.ui

import org.example.pdfsign.settings.Settings
import org.example.pdfsign.signing.CertificateSource
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignStatus
import org.example.pdfsign.signing.SigningPrompt
import org.example.pdfsign.signing.UserInteraction
import java.awt.TrayIcon
import javax.swing.SwingUtilities

class SwingUserInteraction(private val settings: () -> Settings) : UserInteraction {
    @Volatile
    var tray: TrayController? = null

    override fun requestQueued(request: SignRequest, waitingAhead: Int) {
        if (waitingAhead > 0) {
            tray?.notify(
                "Signature request queued",
                "${request.origin ?: "A local application"} sent ${request.fileName}. " +
                    "It will be shown after the $waitingAhead request(s) before it.",
            )
        }
    }

    override fun openPrompt(request: SignRequest): SigningPrompt {
        lateinit var window: SignRequestWindow
        SwingUtilities.invokeAndWait {
            window = SignRequestWindow(request, CertificateSource.available(settings().pkcs11Library))
            window.showWindow()
        }
        return window
    }

    override fun requestFinished(request: SignRequest) {
        when (request.status) {
            SignStatus.SIGNED -> tray?.notify("Document signed", "${request.fileName} was signed.")
            SignStatus.FAILED -> tray?.notify("Signing failed", request.error.orEmpty(), TrayIcon.MessageType.ERROR)
            else -> Unit
        }
    }
}
