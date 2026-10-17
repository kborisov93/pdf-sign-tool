package org.example.pdfsign.ui

import org.example.pdfsign.AppInfo
import org.example.pdfsign.signing.CertificateEntry
import org.example.pdfsign.signing.CertificateSource
import org.example.pdfsign.signing.LoadedKeyStore
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignStatus
import org.example.pdfsign.signing.SigningCredential
import org.example.pdfsign.signing.SigningPrompt
import org.example.pdfsign.signing.Validity
import org.example.pdfsign.signing.userMessage
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JProgressBar
import javax.swing.JScrollPane
import javax.swing.JTable
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Asks the user to approve a signing request and choose a certificate. It stays open while signing, so that the
 * smart card's PIN dialog belongs to the foreground application and is not hidden behind the browser.
 *
 * Create and show it on the event dispatch thread; [awaitApproval] is called from the signing thread.
 */
class SignRequestWindow(
    private val request: SignRequest,
    private val sources: List<CertificateSource>,
) : JFrame("Signature request – ${AppInfo.NAME}"), SigningPrompt {

    private sealed interface Decision {
        class Approve(val credential: SigningCredential) : Decision
        data object Reject : Decision
    }

    private val decisions = LinkedBlockingQueue<Decision>()

    private val sourceBox = JComboBox(sources.toTypedArray())
    private val refreshButton = JButton("Refresh")
    private val showAllBox = JCheckBox("Show all certificates")
    private val pinField = JPasswordField(12)
    private val unlockButton = JButton("Unlock")
    private val pinRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
    private val certificates = CertificateTableModel()
    private val table = JTable(certificates)
    private val certificateHint = plainLabel(" ")
    private val message = plainLabel(" ")
    private val progress = JProgressBar()
    private val signButton = JButton("Sign")
    private val rejectButton = JButton("Reject")
    private val statusWatcher = Timer(500) { onStatusTick() }

    private var keyStore: LoadedKeyStore? = null
    private var allEntries: List<CertificateEntry> = emptyList()
    private var loadGeneration = 0
    private var loading = false
    private var signing = false
    private var closed = false

    init {
        iconImages = AppIcon.windowImages
        defaultCloseOperation = DO_NOTHING_ON_CLOSE
        addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = reject()
        })

        val body = JPanel(BorderLayout(0, 10)).apply {
            add(documentPanel(), BorderLayout.NORTH)
            add(certificatePanel(), BorderLayout.CENTER)
        }
        contentPane = JPanel(BorderLayout(0, 12)).apply {
            border = BorderFactory.createEmptyBorder(14, 16, 14, 16)
            add(header(), BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(footer(), BorderLayout.SOUTH)
        }
        rootPane.defaultButton = signButton
        rootPane.registerKeyboardAction(
            { reject() },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW,
        )
        updateControls()
        pack()
        minimumSize = size
        setLocationRelativeTo(null)
    }

    /** Shows the window in front of the browser and starts loading certificates. */
    fun showWindow() {
        isAlwaysOnTop = true
        isVisible = true
        toFront()
        requestFocus()
        Timer(1500) { isAlwaysOnTop = false }.apply { isRepeats = false }.start()
        statusWatcher.start()
        if (sources.isEmpty()) {
            certificateHint.showText("No certificate source is available. Configure a PKCS#11 library in Settings.", error = true)
        } else {
            loadCertificates()
        }
    }

    // --- SigningPrompt (called from the signing thread) ----------------------------------------------------------

    override fun awaitApproval(): SigningCredential? = when (val decision = decisions.take()) {
        is Decision.Approve -> decision.credential
        Decision.Reject -> null
    }

    override fun signingFailed(error: Throwable) = SwingUtilities.invokeLater {
        signing = false
        progress.isVisible = false
        message.showText("Signing failed: ${error.userMessage()} Try again, or reject the request.", error = true)
        updateControls()
        toFront()
    }

    override fun close() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(::close)
            return
        }
        if (closed) return
        closed = true
        loadGeneration++ // a certificate load still running will close its own result
        statusWatcher.stop()
        keyStore?.close()
        keyStore = null
        dispose()
    }

    // --- Layout --------------------------------------------------------------------------------------------------

    private fun header(): JComponent {
        val title = JLabel("Signature request").apply { font = font.deriveFont(Font.BOLD, font.size2D + 5f) }
        val requester = plainLabel(request.origin ?: "A local application", bold = true)
        val explanation = JLabel("asks you to sign the document below with your certificate.")
        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(title, Box.createVerticalStrut(6), requester, explanation).forEach {
                (it as JComponent).alignmentX = LEFT_ALIGNMENT
                add(it)
            }
        }
        return JPanel(BorderLayout(14, 0)).apply {
            add(JLabel(ImageIcon(AppIcon.render(48))), BorderLayout.WEST)
            add(texts, BorderLayout.CENTER)
        }
    }

    private fun documentPanel(): JComponent {
        val info = request.documentInfo
        val options = request.options
        val panel = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, value: JComponent) {
            panel.add(JLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_START; insets = Insets(2, 0, 2, 12)
            })
            panel.add(value, GridBagConstraints().apply {
                gridx = 1; gridy = row; anchor = GridBagConstraints.LINE_START; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL; insets = Insets(2, 0, 2, 12)
            })
            row++
        }

        val openButton = JButton("Open").apply {
            toolTipText = "View the document in your PDF reader before signing"
            addActionListener { openDocument() }
        }
        addRow("File:", JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(plainLabel(request.fileName.ellipsize(60), bold = true))
            add(Box.createHorizontalStrut(10))
            add(openButton)
        })
        addRow("Size:", JLabel("${formatSize(request.document.size)}, ${info.pageCount} page${if (info.pageCount == 1) "" else "s"}"))
        val rect = info.signatureRect
        addRow("Signature:", JLabel("page ${options.page}, ${rect.width.toInt()} × ${rect.height.toInt()} pt"))
        options.reason?.let { addRow("Reason:", plainLabel(it.ellipsize(80))) }
        options.location?.let { addRow("Location:", plainLabel(it.ellipsize(80))) }
        if (info.existingSignatures > 0) {
            addRow("Already signed:", JLabel("${info.existingSignatures} existing signature(s) will be preserved"))
        }

        return JPanel(BorderLayout(12, 0)).apply {
            border = titledBorder("Document")
            add(JPanel(BorderLayout()).apply { add(panel, BorderLayout.NORTH) }, BorderLayout.CENTER)
            add(PlacementPreview(info.page, rect), BorderLayout.EAST)
        }
    }

    private fun certificatePanel(): JComponent {
        sourceBox.renderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component =
                super.getListCellRendererComponent(list, (value as? CertificateSource)?.displayName ?: value, index, isSelected, cellHasFocus)
        }
        sourceBox.addActionListener { loadCertificates() }
        refreshButton.addActionListener { loadCertificates() }
        refreshButton.toolTipText = "Read the certificates again, e.g. after inserting the smart card"
        unlockButton.addActionListener { unlock() }
        pinField.addActionListener { unlock() }
        showAllBox.toolTipText = "Include device, server and self-signed certificates, which are normally not used to sign documents"
        showAllBox.addActionListener { refreshTable() }

        val sourceRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JLabel("Source:"))
            add(if (sources.size > 1) sourceBox else JLabel(sources.firstOrNull()?.displayName ?: "none"))
            add(refreshButton)
            add(Box.createHorizontalStrut(8))
            add(showAllBox)
        }
        pinRow.apply {
            add(JLabel("PIN:"))
            add(pinField)
            add(unlockButton)
            isVisible = false
        }
        val controls = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            sourceRow.alignmentX = LEFT_ALIGNMENT
            pinRow.alignmentX = LEFT_ALIGNMENT
            add(sourceRow)
            add(Box.createVerticalStrut(6))
            add(pinRow)
        }

        table.apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            fillsViewportHeight = true
            rowHeight = maxOf(rowHeight, getFontMetrics(font).height + 6)
            preferredScrollableViewportSize = Dimension(620, rowHeight * 5)
            setDefaultRenderer(Any::class.java, CertificateRenderer(certificates))
            columnModel.getColumn(0).preferredWidth = 230
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).preferredWidth = 100
            columnModel.getColumn(3).preferredWidth = 90
            selectionModel.addListSelectionListener { updateControls() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && rowAtPoint(e.point) >= 0) sign()
                }
            })
        }

        return JPanel(BorderLayout(0, 6)).apply {
            border = titledBorder("Signing certificate")
            add(controls, BorderLayout.NORTH)
            add(JScrollPane(table), BorderLayout.CENTER)
            add(certificateHint, BorderLayout.SOUTH)
        }
    }

    private fun footer(): JComponent {
        signButton.addActionListener { sign() }
        rejectButton.addActionListener { reject() }
        progress.isIndeterminate = true
        progress.isVisible = false
        progress.preferredSize = Dimension(120, progress.preferredSize.height)
        val status = JPanel(BorderLayout(10, 0)).apply {
            add(message, BorderLayout.CENTER)
            add(progress, BorderLayout.EAST)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            add(signButton)
            add(rejectButton)
        }
        return JPanel(BorderLayout(0, 10)).apply {
            add(status, BorderLayout.NORTH)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    // --- Behaviour -----------------------------------------------------------------------------------------------

    private val selectedSource: CertificateSource? get() = sourceBox.selectedItem as? CertificateSource

    private val selectedEntry: CertificateEntry? get() = table.selectedRow.takeIf { it >= 0 }?.let { certificates.entries[it] }

    private fun loadCertificates(pin: CharArray? = null) {
        if (signing || closed) return
        val source = selectedSource ?: return
        val generation = ++loadGeneration
        keyStore?.close()
        keyStore = null
        allEntries = emptyList()
        certificates.entries = emptyList()
        pinRow.isVisible = source.needsPin
        if (source.needsPin && pin == null) {
            loading = false
            certificateHint.showText("Enter the PIN of your smart card and click Unlock.")
            updateControls()
            pinField.requestFocusInWindow()
            return
        }

        loading = true
        certificateHint.showText("Reading certificates…")
        updateControls()
        object : SwingWorker<LoadedKeyStore, Unit>() {
            override fun doInBackground(): LoadedKeyStore = source.load(pin)

            override fun done() {
                pin?.fill('\u0000')
                val result = try {
                    get()
                } catch (e: Exception) {
                    if (generation == loadGeneration) {
                        loading = false
                        val cause = (e as? ExecutionException)?.cause ?: e
                        certificateHint.showText("Could not read certificates: ${cause.userMessage()}", error = true)
                        updateControls()
                    }
                    return
                }
                if (generation != loadGeneration) {
                    result.close() // superseded, or the window was closed meanwhile
                    return
                }
                loading = false
                keyStore = result
                allEntries = result.entries
                refreshTable()
            }
        }.execute()
    }

    private fun refreshTable() {
        val shown = if (showAllBox.isSelected) allEntries else allEntries.filter { it.isRecommended }
        certificates.entries = shown
        if (keyStore != null) {
            when {
                shown.isNotEmpty() -> certificateHint.showText("Select the certificate to sign with.")
                allEntries.isNotEmpty() -> certificateHint.showText(
                    "No document signing certificate found. Insert your smart card and click Refresh, or show all certificates.",
                    error = true,
                )
                selectedSource?.needsPin == true -> certificateHint.showText("The smart card holds no signing certificate.", error = true)
                else -> certificateHint.showText("No signing certificate found. Insert your smart card and click Refresh.", error = true)
            }
        }
        val firstValid = shown.indexOfFirst { it.validity == Validity.VALID }
        if (firstValid >= 0) table.setRowSelectionInterval(firstValid, firstValid)
        updateControls()
    }

    private fun unlock() {
        if (loading || signing) return
        val pin = pinField.password
        pinField.text = ""
        if (pin.isEmpty()) {
            certificateHint.showText("Enter the PIN first.", error = true)
            return
        }
        loadCertificates(pin)
    }

    private fun sign() {
        if (signing || loading || closed) return
        val entry = selectedEntry ?: return
        if (entry.validity != Validity.VALID) return
        val credential = try {
            keyStore?.credentialFor(entry) ?: return
        } catch (e: Exception) {
            message.showText("This certificate cannot be used: ${e.userMessage()}", error = true)
            return
        }
        signing = true
        progress.isVisible = true
        message.showText("Signing… If you are asked for the PIN of your smart card, enter it to continue.")
        updateControls()
        decisions.put(Decision.Approve(credential))
    }

    private fun reject() {
        if (signing) return
        decisions.put(Decision.Reject)
        close()
    }

    /** Closes the window when the web application cancels the request. */
    private fun onStatusTick() {
        if (!signing && request.status == SignStatus.CANCELLED) {
            decisions.put(Decision.Reject)
            close()
        }
    }

    private fun openDocument() {
        val error = try {
            val name = request.fileName.replace(Regex("[^A-Za-z0-9._ -]"), "_").let {
                if (it.endsWith(".pdf", ignoreCase = true)) it else "$it.pdf"
            }
            val file = Files.createTempFile("pdf-sign-", "-$name").toFile()
            file.deleteOnExit()
            file.writeBytes(request.document)
            openFile(file)
        } catch (e: Exception) {
            e.userMessage()
        }
        if (error != null) message.showText("Cannot open the document: $error", error = true)
    }

    private fun updateControls() {
        val idle = !signing && !loading
        sourceBox.isEnabled = idle && sources.size > 1
        refreshButton.isEnabled = idle && selectedSource != null
        showAllBox.isEnabled = idle
        pinField.isEnabled = idle
        unlockButton.isEnabled = idle
        table.isEnabled = !signing
        signButton.isEnabled = idle && selectedEntry?.validity == Validity.VALID
        rejectButton.isEnabled = !signing
    }

    private fun formatSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }
}

private class CertificateTableModel : AbstractTableModel() {
    var entries: List<CertificateEntry> = emptyList()
        set(value) {
            field = value
            fireTableDataChanged()
        }

    override fun getRowCount() = entries.size

    override fun getColumnCount() = COLUMNS.size

    override fun getColumnName(column: Int) = COLUMNS[column]

    override fun getValueAt(row: Int, column: Int): Any {
        val entry = entries[row]
        return when (column) {
            0 -> entry.subjectName
            1 -> entry.issuerName
            2 -> DATE_FORMAT.format(entry.certificate.notAfter.toInstant())
            else -> entry.validity.label
        }
    }

    companion object {
        private val COLUMNS = arrayOf("Issued to", "Issued by", "Valid until", "Status")
        // Locale-independent, so the bundled runtime can do without the large jdk.localedata module.
        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
    }
}

private class CertificateRenderer(private val model: CertificateTableModel) : DefaultTableCellRenderer() {
    init {
        putClientProperty("html.disable", true)
    }

    override fun getTableCellRendererComponent(
        table: JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int,
    ): Component {
        super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val entry = model.entries[row]
        if (!isSelected && entry.validity != Validity.VALID) foreground = Color.GRAY
        toolTipText = "${entry.certificate.subjectX500Principal.name}  (serial ${entry.certificate.serialNumber.toString(16).uppercase()})"
        return this
    }
}
