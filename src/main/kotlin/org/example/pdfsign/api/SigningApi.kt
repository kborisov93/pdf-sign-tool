package org.example.pdfsign.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.host
import io.ktor.server.request.httpMethod
import io.ktor.server.request.isMultipart
import io.ktor.server.request.path
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.readByteArray
import org.example.pdfsign.AppInfo
import org.example.pdfsign.settings.Settings
import org.example.pdfsign.signing.CoordinateOrigin
import org.example.pdfsign.signing.InvalidSignRequestException
import org.example.pdfsign.signing.PdfInspector
import org.example.pdfsign.signing.PdfRect
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignRequestStore
import org.example.pdfsign.signing.SignStatus
import org.example.pdfsign.signing.SignerDetails
import org.example.pdfsign.signing.SignatureOptions
import org.example.pdfsign.signing.userMessage
import org.slf4j.LoggerFactory
import java.net.URLEncoder
import java.util.UUID

const val MAX_DOCUMENT_BYTES = 100L * 1024 * 1024
const val MAX_ACTIVE_REQUESTS = 20

private val log = LoggerFactory.getLogger("org.example.pdfsign.api.SigningApi")
private val LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "[::1]", "::1")

class ApiException(val status: HttpStatusCode, val code: String, message: String) : RuntimeException(message)

/**
 * The REST API:
 *
 * - `GET    /api/v1/status`                          is the app running?
 * - `POST   /api/v1/sign-requests`                   multipart: `file` + signature placement → 202 + request
 * - `GET    /api/v1/sign-requests/{id}`              poll the status
 * - `GET    /api/v1/sign-requests/{id}/document`     download the signed PDF
 * - `DELETE /api/v1/sign-requests/{id}`              cancel a pending request or discard a finished one
 *
 * plus a test page at `/`.
 */
fun Application.signingApi(store: SignRequestStore, submit: (SignRequest) -> Unit, settings: () -> Settings) {
    install(ContentNegotiation) { json(apiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(e.status, ErrorDto(e.code, e.message.orEmpty())) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error in {} {}", call.request.httpMethod.value, call.request.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("internal_error", e.userMessage()))
        }
    }

    // Local access guard and CORS. Runs before routing for every request.
    intercept(ApplicationCallPipeline.Plugins) {
        val request = call.request
        // Only accept requests addressed to the loopback interface by name: defeats DNS rebinding.
        if (request.host().lowercase() !in LOCAL_HOSTS) {
            call.respond(HttpStatusCode.Forbidden, ErrorDto("forbidden_host", "Use http://127.0.0.1 or http://localhost"))
            return@intercept finish()
        }
        if (request.path().startsWith("/api/")) call.response.header(HttpHeaders.CacheControl, "no-store")

        val origin = request.headers[HttpHeaders.Origin] ?: return@intercept
        val port = request.local.localPort
        if (origin == "http://127.0.0.1:$port" || origin == "http://localhost:$port") return@intercept // the test page

        if (!settings().isOriginAllowed(origin)) {
            log.warn("Rejected {} {} from origin {}", request.httpMethod.value, request.uri, origin)
            call.respond(HttpStatusCode.Forbidden, ErrorDto("origin_not_allowed", "$origin may not use ${AppInfo.NAME}"))
            return@intercept finish()
        }
        call.response.header(HttpHeaders.AccessControlAllowOrigin, origin)
        call.response.header(HttpHeaders.Vary, HttpHeaders.Origin)
        call.response.header(HttpHeaders.AccessControlExposeHeaders, "${HttpHeaders.Location}, ${HttpHeaders.ContentDisposition}")

        if (request.httpMethod == HttpMethod.Options && request.headers.contains(HttpHeaders.AccessControlRequestMethod)) {
            call.response.header(HttpHeaders.AccessControlAllowMethods, "GET, POST, DELETE, OPTIONS")
            request.headers[HttpHeaders.AccessControlRequestHeaders]?.let {
                call.response.header(HttpHeaders.AccessControlAllowHeaders, it)
            }
            call.response.header(HttpHeaders.AccessControlMaxAge, "600")
            // Chrome's Private Network Access preflight for public sites calling localhost.
            if (request.headers["Access-Control-Request-Private-Network"].equals("true", ignoreCase = true)) {
                call.response.header("Access-Control-Allow-Private-Network", "true")
            }
            call.respond(HttpStatusCode.NoContent)
            return@intercept finish()
        }
    }

    routing {
        staticResources("/", "static")

        route("/api/v1") {
            get("/status") {
                call.respond(ServiceStatusDto(AppInfo.NAME, AppInfo.version, AppInfo.API_VERSION, store.activeCount()))
            }

            route("/sign-requests") {
                post {
                    if (store.activeCount() >= MAX_ACTIVE_REQUESTS) {
                        throw ApiException(HttpStatusCode.TooManyRequests, "too_many_requests", "Too many requests are waiting for the user")
                    }
                    val upload = call.receiveUpload()
                    val options = upload.fields.toSignatureOptions()
                    val info = withContext(Dispatchers.IO) {
                        try {
                            PdfInspector.inspect(upload.document, options)
                        } catch (e: InvalidSignRequestException) {
                            throw ApiException(HttpStatusCode.BadRequest, "invalid_request", e.message.orEmpty())
                        }
                    }
                    val request = SignRequest(
                        id = UUID.randomUUID().toString(),
                        origin = call.request.headers[HttpHeaders.Origin],
                        fileName = upload.fileName,
                        document = upload.document,
                        documentInfo = info,
                        options = options,
                    )
                    log.info(
                        "Request {} from {}: '{}' ({} bytes), page {}", request.id, request.origin ?: "a local client",
                        request.fileName, request.document.size, options.page,
                    )
                    submit(request)
                    val dto = request.toDto(call.baseUrl())
                    call.response.header(HttpHeaders.Location, dto.statusUrl)
                    call.respond(HttpStatusCode.Accepted, dto)
                }

                get("/{id}") {
                    call.respond(call.findRequest(store).toDto(call.baseUrl()))
                }

                get("/{id}/document") {
                    val request = call.findRequest(store)
                    val document = request.signedDocument
                    if (request.status != SignStatus.SIGNED || document == null) {
                        throw ApiException(HttpStatusCode.Conflict, "not_signed", "The document is not signed (status ${request.status})")
                    }
                    call.response.header(HttpHeaders.ContentDisposition, attachment(request.signedFileName))
                    call.respondBytes(document, ContentType.Application.Pdf)
                }

                delete("/{id}") {
                    val request = call.findRequest(store)
                    if (!request.cancel() && !request.status.isFinal) {
                        throw ApiException(HttpStatusCode.Conflict, "signing_in_progress", "The document is being signed right now")
                    }
                    store.remove(request.id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}

private class Upload(val fileName: String, val document: ByteArray, val fields: Map<String, String>)

private suspend fun ApplicationCall.receiveUpload(): Upload {
    if (!request.isMultipart()) {
        throw ApiException(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Send the request as multipart/form-data")
    }
    var fileName: String? = null
    var document: ByteArray? = null
    val fields = mutableMapOf<String, String>()
    receiveMultipart(formFieldLimit = MAX_DOCUMENT_BYTES + 1).forEachPart { part ->
        try {
            when (part) {
                is PartData.FormItem -> part.name?.let { fields[it] = part.value }
                is PartData.FileItem -> if (part.name == "file") {
                    val bytes = part.provider().readRemaining(MAX_DOCUMENT_BYTES + 1).readByteArray()
                    if (bytes.size > MAX_DOCUMENT_BYTES) {
                        throw ApiException(HttpStatusCode.PayloadTooLarge, "document_too_large", "The document is larger than ${MAX_DOCUMENT_BYTES / 1024 / 1024} MB")
                    }
                    document = bytes
                    fileName = part.originalFileName
                }
                else -> Unit
            }
        } finally {
            part.dispose()
        }
    }
    val bytes = document ?: throw ApiException(HttpStatusCode.BadRequest, "invalid_request", "The 'file' part with the PDF document is missing")
    return Upload(sanitizeFileName(fileName), bytes, fields)
}

private fun Map<String, String>.toSignatureOptions(): SignatureOptions {
    fun invalid(message: String): Nothing = throw ApiException(HttpStatusCode.BadRequest, "invalid_request", message)
    fun number(name: String): Float =
        this[name]?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() } ?: invalid("'$name' is required and must be a number")
    fun text(name: String, maxLength: Int = 256): String? =
        this[name]?.trim()?.takeIf { it.isNotEmpty() }?.also {
            if (it.length > maxLength) invalid("'$name' must not be longer than $maxLength characters")
        }

    val page = this["page"]?.trim()?.toIntOrNull() ?: invalid("'page' is required and must be an integer (1 = first page)")
    val origin = text("origin")?.let { CoordinateOrigin.parse(it) ?: invalid("'origin' must be 'bottom-left' or 'top-left'") }
        ?: CoordinateOrigin.BOTTOM_LEFT
    return SignatureOptions(
        page = page,
        rect = PdfRect(number("x"), number("y"), number("width"), number("height")),
        origin = origin,
        reason = text("reason"),
        location = text("location"),
        contactInfo = text("contactInfo"),
        fieldName = text("fieldName", maxLength = 64),
        signerDetails = text("signerDetails")?.let { SignerDetails.parse(it) ?: invalid("'signerDetails' must be 'name' or 'dn'") },
    )
}

/** Finds a request; a page from another origin cannot see it (it gets 404, as if the id did not exist). */
private fun ApplicationCall.findRequest(store: SignRequestStore): SignRequest {
    val request = store[parameters["id"].orEmpty()]
    val origin = this.request.headers[HttpHeaders.Origin]
    if (request == null || (origin != null && request.origin != null && !origin.equals(request.origin, ignoreCase = true))) {
        throw ApiException(HttpStatusCode.NotFound, "not_found", "Unknown signing request")
    }
    return request
}

private fun ApplicationCall.baseUrl(): String = request.local.let { "${it.scheme}://${it.serverHost}:${it.serverPort}" }

private fun sanitizeFileName(name: String?): String {
    val base = name.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        .filter { it >= ' ' && it !in "<>:\"|?*" }.trim().take(200)
    return base.ifEmpty { "document.pdf" }
}

/** Content-Disposition with an ASCII fallback and the exact UTF-8 name (RFC 6266). */
private fun attachment(fileName: String): String {
    val ascii = fileName.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\') it else '_' }.joinToString("")
    val encoded = URLEncoder.encode(fileName, Charsets.UTF_8).replace("+", "%20").replace("*", "%2A")
    return "attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded"
}
