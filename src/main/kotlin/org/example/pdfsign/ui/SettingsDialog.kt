package org.example.pdfsign.ui

import org.example.pdfsign.AppInfo
import org.example.pdfsign.settings.Settings
import org.example.pdfsign.settings.SettingsStore
import org.example.pdfsign.settings.WindowsStartup
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Frame
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.net.URI
import java.nio.file.Files
import java.text.ParseException
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.SpinnerNumberModel
import javax.swing.filechooser.FileNameExtensionFilter
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
    private val startupBox = JCheckBox("Start ${AppInfo.NAME} when I sign in to Windows")

    init {
        iconImages = AppIcon.windowImages
        defaultCloseOperation = DISPOSE_ON_CLOSE

        val form = JPanel(GridBagLayout())
        var row = 0
        fun addField(label: String, field: JComponent, hint: String?) {
            form.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.FIRST_LINE_START; insets = Insets(6, 0, 0, 12)
            })
            form.add(field, GridBagConstraints().apply {
                gridx = 1; gridy = row; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(4, 0, 0, 0)
            })
            row++
            if (hint != null) {
                form.add(hintLabel(hint), GridBagConstraints().apply {
                    gridx = 1; gridy = row; anchor = GridBagConstraints.LINE_START; insets = Insets(2, 0, 4, 0)
                })
                row++
            }
        }

        addField("API port:", JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(portSpinner) },
            "The API listens on http://127.0.0.1:<port> only.")
        addField("Allowed websites:", JScrollPane(originsArea),
            "One origin per line, e.g. https://portal.example.com. * allows every website; https://*.example.com its subdomains.")
        addField("Timestamp server:", timestampField,
            "Optional RFC 3161 TSA URL, e.g. http://timestamp.digicert.com. Leave empty for no timestamp.")
        val browse = JButton("Browse…").apply { addActionListener { choosePkcs11Library() } }
        addField("PKCS#11 library:", JPanel(BorderLayout(6, 0)).apply {
            add(pkcs11Field, BorderLayout.CENTER)
            add(browse, BorderLayout.EAST)
        }, "Optional: only for smart cards that do not appear in the Windows certificate store.")
        if (WindowsStartup.isSupported) {
            startupBox.isSelected = WindowsStartup.isEnabled()
            form.add(startupBox, GridBagConstraints().apply {
                gridx = 1; gridy = row; anchor = GridBagConstraints.LINE_START; insets = Insets(8, 0, 0, 0)
            })
        }

        val save = JButton("Save").apply { addActionListener { save() } }
        val cancel = JButton("Cancel").apply { addActionListener { dispose() } }
        contentPane = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            add(form, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply { add(save); add(cancel) }, BorderLayout.SOUTH)
        }
        rootPane.defaultButton = save
        rootPane.registerKeyboardAction({ dispose() }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW)
        pack()
        setLocationRelativeTo(null)
    }

    private fun choosePkcs11Library() {
        val chooser = JFileChooser(pkcs11Field.text.ifBlank { System.getenv("WINDIR")?.let { "$it\\System32" } }).apply {
            dialogTitle = "Choose the PKCS#11 library of your smart card"
            fileFilter = FileNameExtensionFilter("PKCS#11 libraries (*.dll)", "dll")
        }
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) pkcs11Field.text = chooser.selectedFile.absolutePath
    }

    private fun save() {
        try {
            portSpinner.commitEdit()
        } catch (_: ParseException) {
            return error("The port must be a number between 1024 and 65535.")
        }
        val timestampUrl = timestampField.text.trim()
        if (timestampUrl.isNotEmpty() && !isHttpUrl(timestampUrl)) {
            return error("The timestamp server must be an http:// or https:// URL.")
        }
        val pkcs11Library = pkcs11Field.text.trim()
        if (pkcs11Library.isNotEmpty() && !Files.isRegularFile(Path(pkcs11Library))) {
            return error("The PKCS#11 library does not exist.")
        }

        store.update(
            Settings(
                port = portSpinner.value as Int,
                allowedOrigins = Settings.parseOrigins(originsArea.text),
                timestampUrl = timestampUrl,
                pkcs11Library = pkcs11Library,
            )
        )
        if (WindowsStartup.isSupported && startupBox.isSelected != WindowsStartup.isEnabled()) {
            WindowsStartup.setEnabled(startupBox.isSelected)
        }
        dispose()
    }

    private fun isHttpUrl(text: String) = runCatching { URI(text).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)

    private fun error(message: String) = JOptionPane.showMessageDialog(this, message, title, JOptionPane.ERROR_MESSAGE)

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
