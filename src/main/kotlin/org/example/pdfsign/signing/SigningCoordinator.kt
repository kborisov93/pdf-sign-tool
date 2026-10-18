package org.example.pdfsign.signing

import org.slf4j.LoggerFactory
import java.util.concurrent.LinkedBlockingQueue

interface UserInteraction {
    /** The API accepted [request]; [waitingAhead] requests are in line before it. Must not block. */
    fun requestQueued(request: SignRequest, waitingAhead: Int)

    /** Shows the approval prompt. Called on the signing thread, for one request at a time. */
    fun openPrompt(request: SignRequest): SigningPrompt

    /** [request] reached a final status. */
    fun requestFinished(request: SignRequest)
}

interface SigningPrompt : AutoCloseable {
    /** Blocks until the user approves with a certificate; returns null when the user rejects the request. */
    fun awaitApproval(): SigningCredential?

    /** Signing with the approved certificate failed; the prompt lets the user try again or give up. */
    fun signingFailed(error: Throwable)

    override fun close()
}

/** Takes accepted requests one by one, asks the user for approval and signs. */
class SigningCoordinator(
    private val store: SignRequestStore,
    private val signingService: PdfSigningService,
    private val interaction: UserInteraction,
    /** The app's default for requests that do not choose [SignatureOptions.signerDetails]. */
    private val signerDetails: () -> SignerDetails = { SignerDetails.NAME },
    private val timestampUrl: () -> String?,
) {
    private val log = LoggerFactory.getLogger(SigningCoordinator::class.java)
    private val queue = LinkedBlockingQueue<SignRequest>()
    private val worker = Thread(::processQueue, "signing-worker").apply { isDaemon = true }

    @Volatile
    private var busy = false

    fun start() = worker.start()

    fun stop() = worker.interrupt()

    fun submit(request: SignRequest) {
        store.add(request)
        val waitingAhead = queue.size + if (busy) 1 else 0
        queue.put(request)
        interaction.requestQueued(request, waitingAhead)
    }

    private fun processQueue() {
        while (true) {
            val request = try {
                queue.take()
            } catch (_: InterruptedException) {
                return
            }
            if (request.status != SignStatus.PENDING) continue // cancelled while waiting in line
            busy = true
            try {
                process(request)
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                log.error("Processing request {} failed", request.id, e)
                request.fail(e.userMessage())
            } finally {
                busy = false
            }
            if (request.status.isFinal) interaction.requestFinished(request)
        }
    }

    private fun process(request: SignRequest) {
        interaction.openPrompt(request).use { prompt ->
            var lastError: Throwable? = null
            while (true) {
                val credential = prompt.awaitApproval()
                if (credential == null) {
                    if (lastError != null) {
                        request.fail(lastError.userMessage())
                    } else if (request.cancel()) {
                        log.info("Request {} rejected by the user", request.id)
                    }
                    return
                }
                if (!request.startSigning()) return // cancelled by the client in the meantime

                log.info("Signing request {} as '{}'", request.id, credential.certificate.subjectX500Principal.name)
                try {
                    val details = request.options.signerDetails ?: signerDetails()
                    val signed = signingService.sign(request, credential, timestampUrl(), details)
                    request.complete(signed, SignerInfo.of(credential.certificate))
                    log.info("Request {} signed", request.id)
                    return
                } catch (e: Exception) {
                    log.warn("Signing request {} failed", request.id, e)
                    lastError = e
                    request.returnToPending()
                    prompt.signingFailed(e)
                }
            }
        }
    }
}

/** The most specific message in the cause chain, e.g. "The operation was canceled by the user." from SunMSCAPI. */
fun Throwable.userMessage(): String =
    generateSequence(this) { it.cause }.take(16).toList().asReversed()
        .firstNotNullOfOrNull { it.message?.trim()?.takeIf(String::isNotEmpty) }
        ?: javaClass.simpleName
