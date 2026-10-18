package org.example.pdfsign.ui

import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.util.SystemFileChooser
import com.formdev.flatlaf.util.UIScale
import org.example.pdfsign.AppInfo
import org.example.pdfsign.settings.Settings
import org.example.pdfsign.settings.SettingsStore
import org.example.pdfsign.settings.WindowsStartup
import org.example.pdfsign.signing.SignerDetails
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.text.ParseException
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import kotlin.io.path.Path

class SettingsDialog private constructor(private val store: SettingsStore) :
    JDialog(null as Frame?, "Settings – ${AppInfo.NAME}", true) {

    private val settings = store.current
    private val portSpinner = JSpinner(SpinnerNumberModel(settings.port, 1024, 65535, 1)).apply {
        editor = JSpinner.NumberEditor(this, "#")
    }
    private val originsArea = JTextArea(settings.allowedOrigins.joinToString("\n"), 4, 40)
    private val timestampField = JTextField(settings.timestampUrl, 40)
    private val pkcs11Field = JTextField(settings.pkcs11Library, 32)
    private val dnBox = JCheckBox("Show the certificate details (DN)", settings.signerDetails == SignerDetails.DN).apply { isOpaque = false }
    private val startupBox = JCheckBox("Start ${AppInfo.NAME} when I sign in to Windows")
    private val error = Notice(boxed = false)

    private class Field(val label: String, val component: JComponent, val hint: String)

    init {
        iconImages = AppIcon.windowImages
        defaultCloseOperation = DISPOSE_ON_CLOSE

        timestampField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "No timestamp")
        pkcs11Field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Not used")
        val browse = JButton("Browse…").apply { addActionListener { choosePkcs11Library() } }

        val sections = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = padding(18, 20, 18, 20)
            addSection(
                "Web applications",
                Field(
                    "Allowed websites", JScrollPane(originsArea),
                    "One origin per line, e.g. https://portal.example.com. * allows every website; https://*.example.com its subdomains.",
                ),
                Field("API port", flowRow(portSpinner), "The API listens on http://127.0.0.1:<port> only."),
            )
            add(Box.createVerticalStrut(UIScale.scale(18)))
            addSection(
                "Signing",
                Field("Timestamp server", timestampField, "Optional RFC 3161 TSA URL, e.g. http://timestamp.digicert.com."),
                Field(
                    "PKCS#11 library",
                    JPanel(BorderLayout(UIScale.scale(8), 0)).apply {
                        isOpaque = false
                        add(pkcs11Field, BorderLayout.CENTER)
                        add(browse, BorderLayout.EAST)
                    },
                    "Optional: only for smart cards that do not appear in the Windows certificate store.",
                ),
                Field(
                    "Signature text", dnBox,
                    "Adds email, organisation and personal number from the certificate. A request can override it with signerDetails.",
                ),
            )
            if (WindowsStartup.isSupported) {
                startupBox.isSelected = WindowsStartup.isEnabled()
                add(Box.createVerticalStrut(UIScale.scale(18)))
                add(startupBox.apply { alignmentX = LEFT_ALIGNMENT })
            }
        }

        val save = JButton("Save").apply { addActionListener { save() } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        contentPane = JPanel(BorderLayout()).apply {
            add(sections, BorderLayout.CENTER)
            add(FooterBar(error, save, cancel), BorderLayout.SOUTH)
        }
        rootPane.defaultButton = save
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        setLocationRelativeTo(null)
    }

    /** A heading and a card with the labels on the left, the fields on the right and a hint below each field. */
    private fun JPanel.addSection(title: String, vararg fields: Field) {
        add(JLabel(title).style("font: +1 \$semibold.font").apply {
            alignmentX = LEFT_ALIGNMENT
            border = padding(0, 2, 8, 0)
        })
        val card = Card(GridBagLayout()).apply { alignmentX = LEFT_ALIGNMENT }
        fields.forEachIndexed { index, field ->
            val top = if (index == 0) 0 else UIScale.scale(14)
            card.add(JLabel(field.label), GridBagConstraints().apply {
                gridx = 0; gridy = 2 * index; anchor = GridBagConstraints.FIRST_LINE_START
                insets = Insets(top + UIScale.scale(5), 0, 0, UIScale.scale(16))
            })
            card.add(field.component, GridBagConstraints().apply {
                gridx = 1; gridy = 2 * index; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(top, 0, 0, 0)
            })
            card.add(secondaryLabel(field.hint, smaller = true), GridBagConstraints().apply {
                gridx = 1; gridy = 2 * index + 1; anchor = GridBagConstraints.LINE_START; insets = Insets(UIScale.scale(4), 0, 0, 0)
            })
        }
        add(card)
    }

    private fun choosePkcs11Library() {
        val current = pkcs11Field.text.trim().takeIf { it.isNotEmpty() }?.let(::File)
        val folder = current?.parentFile ?: System.getenv("WINDIR")?.let { File(it, "System32") }
        val chooser = SystemFileChooser(folder).apply {
            dialogTitle = "Choose the PKCS#11 library of your smart card"
            fileFilter = SystemFileChooser.FileNameExtensionFilter("PKCS#11 libraries (*.dll)", "dll")
            if (current != null) selectedFile = current
        }
        if (chooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) pkcs11Field.text = chooser.selectedFile.absolutePath
    }

    private fun save() {
        error.clear()
        listOf(portSpinner, timestampField, pkcs11Field).forEach { it.putClientProperty(FlatClientProperties.OUTLINE, null) }
        try {
            portSpinner.commitEdit()
        } catch (_: ParseException) {
            return fieldError(portSpinner, "The port must be a number between 1024 and 65535.")
        }
        val timestampUrl = timestampField.text.trim()
        if (timestampUrl.isNotEmpty() && !isHttpUrl(timestampUrl)) {
            return fieldError(timestampField, "The timestamp server must be an http:// or https:// URL.")
        }
        val pkcs11Library = pkcs11Field.text.trim()
        if (pkcs11Library.isNotEmpty() && !Files.isRegularFile(Path(pkcs11Library))) {
            return fieldError(pkcs11Field, "The PKCS#11 library does not exist.")
        }

        store.update(
            Settings(
                port = portSpinner.value as Int,
                allowedOrigins = Settings.parseOrigins(originsArea.text),
                timestampUrl = timestampUrl,
                pkcs11Library = pkcs11Library,
                signerDetails = if (dnBox.isSelected) SignerDetails.DN else SignerDetails.NAME,
            )
        )
        if (WindowsStartup.isSupported && startupBox.isSelected != WindowsStartup.isEnabled()) {
            WindowsStartup.setEnabled(startupBox.isSelected)
        }
        dispose()
    }

    private fun isHttpUrl(text: String) = runCatching { URI(text).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    /** Marks the field red and explains the problem next to the buttons. */
    private fun fieldError(field: JComponent, message: String) {
        field.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR)
        field.requestFocusInWindow()
        error.show(message, Notice.Kind.ERROR)
    }

    companion object {
        private var open: SettingsDialog? = null

        /** Shows the dialog (modal), or brings the already open one to the front. Call on the event dispatch thread. */
        fun show(store: SettingsStore) {
            open?.let {
                it.toFront()
                return
            }
            val dialog = SettingsDialog(store)
            open = dialog
            try {
                dialog.isVisible = true
            } finally {
                open = null
            }
        }
    }
}
