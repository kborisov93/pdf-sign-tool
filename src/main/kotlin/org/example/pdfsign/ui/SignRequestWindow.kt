package org.example.pdfsign.ui

import com.formdev.flatlaf.util.UIScale
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
import java.awt.CardLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.RoundRectangle2D
import java.nio.file.Files
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import javax.swing.AbstractListModel
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.SwingWorker
import javax.swing.Timer

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
    private val pinRow = flowRow(JLabel("PIN"), pinField, unlockButton)
    private val certificates = CertificateListModel()
    private val certificateList = object : JList<CertificateEntry>(certificates) {
        // Cells shrink with the window instead of scrolling sideways.
        override fun getScrollableTracksViewportWidth() = true
    }
    private val listCards = CardLayout()
    private val listArea = Card(listCards)
    private val placeholder = Notice(boxed = false)
    private val message = Notice(boxed = true)
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

        val body = JPanel(BorderLayout(0, UIScale.scale(18))).apply {
            add(documentCard(), BorderLayout.NORTH)
            add(certificatePanel(), BorderLayout.CENTER)
        }
        val content = JPanel(BorderLayout(0, UIScale.scale(18))).apply {
            border = padding(18, 20, 18, 20)
            add(header(), BorderLayout.NORTH)
            add(body, BorderLayout.CENTER)
            add(message, BorderLayout.SOUTH)
        }
        contentPane = JPanel(BorderLayout()).apply {
            add(content, BorderLayout.CENTER)
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
            showPlaceholder("No certificate source is available. Configure a PKCS#11 library in Settings.", Notice.Kind.ERROR)
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
        showMessage("Signing failed: ${error.userMessage()} Try again, or reject the request.", Notice.Kind.ERROR)
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
        val title = JLabel("Sign this document?").styleClass("h2")
        val requester = request.origin?.let(::originChip) ?: plainLabel("A local application", bold = true)
        val explanation = flowRow(requester, JLabel("is requesting your signature."))
        val texts = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            listOf(title, Box.createVerticalStrut(UIScale.scale(8)), explanation).forEach {
                (it as JComponent).alignmentX = LEFT_ALIGNMENT
                add(it)
            }
        }
        return JPanel(BorderLayout(UIScale.scale(16), 0)).apply {
            add(JLabel(AppIcon.icon(UIScale.scale(48))), BorderLayout.WEST)
            add(texts, BorderLayout.CENTER)
        }
    }

    /** The requesting website, set off from the text around it: it is what the user must check before signing. */
    private fun originChip(origin: String): JComponent = object : JLabel() {
        override fun paintComponent(g: Graphics) {
            paintRoundedBox(g, themeColor("PdfSign.cardBackground"), themeColor("PdfSign.cardBorderColor"), arc = 8)
            super.paintComponent(g)
        }
    }.apply {
        putClientProperty("html.disable", true)
        text = origin
        font = font.deriveFont(Font.BOLD)
        border = padding(3, 8, 3, 8)
        toolTipText = "The website that sent the document"
        if (origin.startsWith("https://", ignoreCase = true)) {
            icon = LockIcon()
            iconTextGap = UIScale.scale(6)
        }
    }

    private fun documentCard(): JComponent {
        val info = request.documentInfo
        val options = request.options
        val pages = "${info.pageCount} page${if (info.pageCount == 1) "" else "s"}"

        val openButton = JButton("Open document").apply {
            toolTipText = "View the document in your PDF reader before signing"
            addActionListener { openDocument() }
        }
        val title = JPanel(GridLayout(2, 1, 0, UIScale.scale(2))).apply {
            add(plainLabel(request.fileName.ellipsize(60)).style("font: +1 \$semibold.font"))
            add(secondaryLabel("$pages · ${formatSize(request.document.size)}"))
        }

        val details = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, value: JComponent) {
            details.add(secondaryLabel(label), GridBagConstraints().apply {
                gridx = 0; gridy = row; anchor = GridBagConstraints.LINE_START; insets = Insets(3, 0, 3, UIScale.scale(16))
            })
            details.add(value, GridBagConstraints().apply {
                gridx = 1; gridy = row; anchor = GridBagConstraints.LINE_START; weightx = 1.0
                fill = GridBagConstraints.HORIZONTAL; insets = Insets(3, 0, 3, 0)
            })
            row++
        }
        addRow("Signature", JLabel("Page ${options.page} of ${info.pageCount}"))
        options.reason?.let { addRow("Reason", plainLabel(it.ellipsize(80))) }
        options.location?.let { addRow("Location", plainLabel(it.ellipsize(80))) }
        if (info.existingSignatures > 0) {
            val count = info.existingSignatures
            addRow("Signatures", JLabel("$count existing signature${if (count == 1) "" else "s"} will be preserved"))
        }

        val text = JPanel(BorderLayout(0, UIScale.scale(12))).apply {
            add(JPanel(BorderLayout(UIScale.scale(12), 0)).apply {
                add(title, BorderLayout.CENTER)
                add(JPanel(BorderLayout()).apply { add(openButton, BorderLayout.NORTH) }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { add(details, BorderLayout.NORTH) }, BorderLayout.CENTER)
        }
        val rect = info.signatureRect
        val preview = PlacementPreview(info.page, rect).apply {
            toolTipText = "Where the signature will appear: page ${options.page}, ${rect.width.toInt()} × ${rect.height.toInt()} pt"
        }
        return Card(BorderLayout(UIScale.scale(18), 0)).apply {
            add(preview, BorderLayout.WEST)
            add(text.transparent(), BorderLayout.CENTER)
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
        showAllBox.addActionListener { refreshList() }

        val heading = JPanel(BorderLayout(UIScale.scale(12), 0)).apply {
            add(JLabel("Signing certificate").style("font: +1 \$semibold.font"), BorderLayout.WEST)
            val source = if (sources.size > 1) sourceBox else secondaryLabel(sources.firstOrNull()?.displayName.orEmpty())
            add(flowRow(source, refreshButton), BorderLayout.EAST)
        }

        val cell = CertificateCell()
        certificateList.apply {
            selectionModel.selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = cell
            fixedCellHeight = cell.preferredHeight()
            isOpaque = false
            selectionModel.addListSelectionListener { updateControls() }
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (e.clickCount == 2 && index >= 0 && getCellBounds(index, index).contains(e.point)) sign()
                }
            })
        }
        val scrollPane = JScrollPane(certificateList).apply {
            border = BorderFactory.createEmptyBorder() // not null, which a theme change would replace
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        pinRow.isVisible = false
        val placeholderPanel = JPanel(GridBagLayout()).apply {
            border = padding(12, 16, 12, 16)
            add(placeholder, GridBagConstraints().apply { gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL })
            add(pinRow, GridBagConstraints().apply {
                gridy = 1; anchor = GridBagConstraints.LINE_START; insets = Insets(UIScale.scale(10), UIScale.scale(20), 0, 0)
            })
        }

        listArea.apply {
            border = padding(4, 4, 4, 4)
            add(placeholderPanel.transparent(), PLACEHOLDER_CARD)
            add(scrollPane, LIST_CARD)
            preferredSize = Dimension(UIScale.scale(580), certificateList.fixedCellHeight * 3 + insets.top + insets.bottom)
        }

        return JPanel(BorderLayout(0, UIScale.scale(10))).apply {
            add(heading, BorderLayout.NORTH)
            add(listArea, BorderLayout.CENTER)
            add(flowRow(showAllBox), BorderLayout.SOUTH)
        }
    }

    private fun footer(): JComponent {
        signButton.addActionListener { sign() }
        rejectButton.addActionListener { reject() }
        return FooterBar(null, signButton, rejectButton)
    }

    // --- Behaviour -----------------------------------------------------------------------------------------------

    private val selectedSource: CertificateSource? get() = sourceBox.selectedItem as? CertificateSource

    private val selectedEntry: CertificateEntry? get() = certificateList.selectedValue

    /** Shows [text] instead of the certificate list, with the PIN field if the token has to be unlocked first. */
    private fun showPlaceholder(text: String, kind: Notice.Kind = Notice.Kind.INFO, pinEntry: Boolean = false) {
        placeholder.show(text, kind)
        pinRow.isVisible = pinEntry
        listCards.show(listArea, PLACEHOLDER_CARD)
    }

    /** Shows [text] above the buttons. The window grows if needed, so the certificate list keeps its size. */
    private fun showMessage(text: String, kind: Notice.Kind) {
        message.show(text, kind)
        // After the next layout, the message knows the height of its wrapped text.
        SwingUtilities.invokeLater {
            val wanted = preferredSize.height
            if (wanted > height) setSize(width, wanted)
        }
    }

    private fun loadCertificates(pin: CharArray? = null) {
        if (signing || closed) return
        val source = selectedSource ?: return
        val generation = ++loadGeneration
        keyStore?.close()
        keyStore = null
        allEntries = emptyList()
        certificates.entries = emptyList()
        if (source.needsPin && pin == null) {
            loading = false
            showPlaceholder("Enter the PIN of your smart card to show its certificates.", pinEntry = true)
            updateControls()
            pinField.requestFocusInWindow()
            return
        }

        loading = true
        showPlaceholder("Reading certificates…", Notice.Kind.PROGRESS)
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
                        showPlaceholder("Could not read certificates: ${cause.userMessage()}", Notice.Kind.ERROR, pinEntry = source.needsPin)
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
                refreshList()
            }
        }.execute()
    }

    private fun refreshList() {
        val shown = if (showAllBox.isSelected) allEntries else allEntries.filter { it.isRecommended }
        certificates.entries = shown
        if (keyStore != null) {
            when {
                shown.isNotEmpty() -> listCards.show(listArea, LIST_CARD)
                allEntries.isNotEmpty() -> showPlaceholder(
                    "No document signing certificate found. Insert your smart card and click Refresh, or show all certificates.",
                    Notice.Kind.ERROR,
                )
                selectedSource?.needsPin == true -> showPlaceholder("The smart card holds no signing certificate.", Notice.Kind.ERROR)
                else -> showPlaceholder("No signing certificate found. Insert your smart card and click Refresh.", Notice.Kind.ERROR)
            }
        }
        val firstValid = shown.indexOfFirst { it.validity == Validity.VALID }
        if (firstValid >= 0) certificateList.selectedIndex = firstValid
        updateControls()
    }

    private fun unlock() {
        if (loading || signing) return
        val pin = pinField.password
        pinField.text = ""
        if (pin.isEmpty()) {
            showPlaceholder("Enter the PIN first.", Notice.Kind.ERROR, pinEntry = true)
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
            showMessage("This certificate cannot be used: ${e.userMessage()}", Notice.Kind.ERROR)
            return
        }
        signing = true
        showMessage("Signing… If you are asked for the PIN of your smart card, enter it to continue.", Notice.Kind.PROGRESS)
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
        if (error != null) showMessage("Cannot open the document: $error", Notice.Kind.ERROR)
    }

    private fun updateControls() {
        val idle = !signing && !loading
        sourceBox.isEnabled = idle && sources.size > 1
        refreshButton.isEnabled = idle && selectedSource != null
        showAllBox.isEnabled = idle
        pinField.isEnabled = idle
        unlockButton.isEnabled = idle
        certificateList.isEnabled = !signing
        signButton.isEnabled = idle && selectedEntry?.validity == Validity.VALID
        rejectButton.isEnabled = !signing
    }

    private fun formatSize(bytes: Int): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes bytes"
    }

    private fun <T : JComponent> T.transparent(): T = apply {
        isOpaque = false
        components.forEach { (it as? JPanel)?.transparent() }
    }

    private companion object {
        const val LIST_CARD = "list"
        const val PLACEHOLDER_CARD = "placeholder"
    }
}

private class CertificateListModel : AbstractListModel<CertificateEntry>() {
    var entries: List<CertificateEntry> = emptyList()
        set(value) {
            val removed = field.size
            field = value
            // Removing and adding (rather than "changed") also clears the selection, like a table data change.
            if (removed > 0) fireIntervalRemoved(this, 0, removed - 1)
            if (value.isNotEmpty()) fireIntervalAdded(this, 0, value.size - 1)
        }

    override fun getSize() = entries.size

    override fun getElementAt(index: Int) = entries[index]
}

/** A certificate as two lines of text with an icon and a validity badge; the selected one is highlighted. */
private class CertificateCell : JPanel(BorderLayout(UIScale.scale(12), 0)), ListCellRenderer<CertificateEntry> {
    private val icon = CertificateIcon()
    private val subject = plainLabel().style("font: \$semibold.font")
    private val issuer = plainLabel().style("font: -1")
    private val status = Pill()
    private var selected = false

    init {
        isOpaque = false
        border = padding(8, 12, 8, 12)
        add(JLabel(icon), BorderLayout.WEST)
        add(JPanel(GridLayout(2, 1, 0, UIScale.scale(2))).apply {
            isOpaque = false
            add(subject)
            add(issuer)
        }, BorderLayout.CENTER)
        add(JPanel(GridBagLayout()).apply {
            isOpaque = false
            add(status)
        }, BorderLayout.EAST)
    }

    /** The height of every cell: they all show the same lines. */
    fun preferredHeight(): Int {
        subject.text = "X"
        issuer.text = "X"
        status.text = "X"
        return preferredSize.height
    }

    override fun getListCellRendererComponent(
        list: JList<out CertificateEntry>, entry: CertificateEntry, index: Int, isSelected: Boolean, cellHasFocus: Boolean,
    ): Component {
        val usable = list.isEnabled && entry.validity == Validity.VALID
        subject.text = entry.subjectName
        subject.foreground = themeColor(if (usable) "Label.foreground" else "PdfSign.secondaryForeground")
        issuer.text = "Issued by ${entry.issuerName} · valid until ${DATE_FORMAT.format(entry.certificate.notAfter.toInstant())}"
        issuer.foreground = themeColor("PdfSign.secondaryForeground")
        icon.muted = !usable
        val tone = when (entry.validity) {
            Validity.VALID -> "success"
            Validity.EXPIRED -> "error"
            Validity.NOT_YET_VALID -> "warning"
        }
        status.show(entry.validity.label, "PdfSign.${tone}Color", "PdfSign.${tone}Background")
        toolTipText = "${entry.certificate.subjectX500Principal.name}  (serial ${entry.certificate.serialNumber.toString(16).uppercase()})"
        selected = isSelected
        return this
    }

    // Like a Windows 11 list: a rounded highlight with an accent bar at its left edge.
    override fun paintComponent(g: Graphics) {
        if (!selected) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val gap = UIScale.scale(2f)
            val arc = UIScale.scale(8f)
            g2.color = themeColor("PdfSign.selectionBackground")
            g2.fill(RoundRectangle2D.Float(0f, gap, width.toFloat(), height - 2 * gap, arc, arc))
            val barHeight = height * 0.4f
            val barWidth = UIScale.scale(3f)
            g2.color = themeColor("Component.accentColor")
            g2.fill(RoundRectangle2D.Float(gap, (height - barHeight) / 2, barWidth, barHeight, barWidth, barWidth))
        } finally {
            g2.dispose()
        }
    }

    companion object {
        // Locale-independent, so the bundled runtime can do without the large jdk.localedata module.
        private val DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneId.systemDefault())
    }
}
