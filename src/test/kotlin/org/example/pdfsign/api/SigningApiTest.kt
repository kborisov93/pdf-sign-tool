package org.example.pdfsign.api

import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfReader
import com.itextpdf.signatures.SignatureUtil
import io.ktor.client.request.delete
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.example.pdfsign.TestPki
import org.example.pdfsign.settings.Settings
import org.example.pdfsign.signing.PdfSigningService
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignRequestStore
import org.example.pdfsign.signing.SignStatus
import org.example.pdfsign.signing.SigningCoordinator
import org.example.pdfsign.signing.SigningCredential
import org.example.pdfsign.signing.SigningPrompt
import org.example.pdfsign.signing.UserInteraction
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningApiTest {
    private val webApp = "https://app.example.com"

    @Test
    fun `full flow - submit, poll, download`() = withApi(FakeUser(TestPki.credential())) {
        val created = submit()
        assertEquals(HttpStatusCode.Accepted, created.status)
        val request = apiJson.decodeFromString<SignRequestDto>(created.bodyAsText())
        assertEquals(request.statusUrl, created.headers[HttpHeaders.Location])
        assertEquals(webApp, created.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("contract.pdf", request.fileName)
        assertEquals(2, request.pageCount)

        val done = awaitFinal(request.id)
        assertEquals("SIGNED", done.status)
        assertEquals("Иван Петров Тестов", done.signer?.commonName)
        assertNotNull(done.documentUrl)

        val download = client.get("/api/v1/sign-requests/${request.id}/document") { header(HttpHeaders.Origin, webApp) }
        assertEquals(HttpStatusCode.OK, download.status)
        assertEquals(ContentType.Application.Pdf, download.contentType()?.withoutParameters())
        assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.contains("filename=\"contract-signed.pdf\""))
        PdfDocument(PdfReader(ByteArrayInputStream(download.bodyAsBytes()))).use { pdf ->
            val signatures = SignatureUtil(pdf)
            assertTrue(signatures.readSignatureData(signatures.signatureNames.single()).verifySignatureIntegrityAndAuthenticity())
        }
    }

    @Test
    fun `rejection by the user cancels the request`() = withApi(FakeUser(null)) {
        val request = apiJson.decodeFromString<SignRequestDto>(submit().bodyAsText())
        val done = awaitFinal(request.id)
        assertEquals("CANCELLED", done.status)
        assertNull(done.documentUrl)
        val download = client.get("/api/v1/sign-requests/${request.id}/document")
        assertEquals(HttpStatusCode.Conflict, download.status)
    }

    @Test
    fun `the client can cancel a pending request`() = withApi(WaitingUser()) {
        val request = apiJson.decodeFromString<SignRequestDto>(submit().bodyAsText())
        assertEquals("PENDING", status(request.id).status)
        val deleted = client.delete("/api/v1/sign-requests/${request.id}") { header(HttpHeaders.Origin, webApp) }
        assertEquals(HttpStatusCode.NoContent, deleted.status)
        assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/sign-requests/${request.id}").status)
    }

    @Test
    fun `another origin cannot see a request`() = withApi(WaitingUser(), origins = listOf("*")) {
        val request = apiJson.decodeFromString<SignRequestDto>(submit().bodyAsText())
        val response = client.get("/api/v1/sign-requests/${request.id}") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `invalid placement is rejected with 400`() = withApi(WaitingUser()) {
        val response = submit(page = "7")
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", apiJson.decodeFromString<ErrorDto>(response.bodyAsText()).error)
    }

    @Test
    fun `CORS preflight allows configured origins, including private network access`() = withApi(WaitingUser()) {
        val response = client.options("/api/v1/sign-requests") {
            header(HttpHeaders.Origin, webApp)
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header("Access-Control-Request-Private-Network", "true")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(webApp, response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers["Access-Control-Allow-Private-Network"])
        assertTrue(response.headers[HttpHeaders.AccessControlAllowMethods]!!.contains("POST"))
    }

    @Test
    fun `unknown origins are refused`() = withApi(WaitingUser()) {
        val response = client.get("/api/v1/status") { header(HttpHeaders.Origin, "https://evil.example") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertNull(response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun `requests for other host names are refused`() = withApi(WaitingUser()) {
        val response = client.get("/api/v1/status") { header(HttpHeaders.Host, "attacker.example:18765") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `status endpoint and test page are served`() = withApi(WaitingUser()) {
        val status = apiJson.decodeFromString<ServiceStatusDto>(client.get("/api/v1/status").bodyAsText())
        assertEquals(1, status.apiVersion)
        assertTrue(client.get("/").bodyAsText().contains("PDF Sign Tool"))
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun withApi(user: UserInteraction, origins: List<String> = listOf(webApp), block: suspend ApplicationTestBuilder.() -> Unit) {
        val store = SignRequestStore()
        val coordinator = SigningCoordinator(store, PdfSigningService(), user) { null }
        coordinator.start()
        try {
            testApplication {
                application { signingApi(store, coordinator::submit) { Settings(allowedOrigins = origins) } }
                block()
            }
        } finally {
            coordinator.stop()
        }
    }

    private suspend fun ApplicationTestBuilder.submit(page: String = "1"): HttpResponse =
        client.post("/api/v1/sign-requests") {
            header(HttpHeaders.Origin, webApp)
            setBody(MultiPartFormDataContent(formData {
                append("file", TestPki.samplePdf(), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"contract.pdf\"")
                })
                append("page", page)
                append("x", "36")
                append("y", "36")
                append("width", "200")
                append("height", "60")
                append("reason", "Test")
            }))
        }

    private suspend fun ApplicationTestBuilder.status(id: String): SignRequestDto =
        apiJson.decodeFromString(client.get("/api/v1/sign-requests/$id") { header(HttpHeaders.Origin, webApp) }.bodyAsText())

    private suspend fun ApplicationTestBuilder.awaitFinal(id: String): SignRequestDto {
        repeat(200) {
            val request = status(id)
            if (SignStatus.valueOf(request.status).isFinal) return request
            delay(50)
        }
        error("Request $id did not finish")
    }

    /** Approves with [credential] (or rejects when null) without showing anything. */
    private class FakeUser(private val credential: SigningCredential?) : UserInteraction {
        override fun requestQueued(request: SignRequest, waitingAhead: Int) = Unit
        override fun requestFinished(request: SignRequest) = Unit
        override fun openPrompt(request: SignRequest) = object : SigningPrompt {
            private var answered = false
            override fun awaitApproval(): SigningCredential? = if (answered) null else credential.also { answered = true }
            override fun signingFailed(error: Throwable) = Unit
            override fun close() = Unit
        }
    }

    /** Keeps the prompt open until the request is cancelled through the API. */
    private class WaitingUser : UserInteraction {
        override fun requestQueued(request: SignRequest, waitingAhead: Int) = Unit
        override fun requestFinished(request: SignRequest) = Unit
        override fun openPrompt(request: SignRequest) = object : SigningPrompt {
            override fun awaitApproval(): SigningCredential? {
                while (request.status == SignStatus.PENDING) Thread.sleep(20)
                return null
            }
            override fun signingFailed(error: Throwable) = Unit
            override fun close() = Unit
        }
    }
}
