package org.example.pdfsign.api

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.runBlocking
import org.example.pdfsign.settings.Settings
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignRequestStore

/** Runs the REST API on 127.0.0.1 only; it can be restarted on another port. */
class ApiServer(
    private val store: SignRequestStore,
    private val submit: (SignRequest) -> Unit,
    private val settings: () -> Settings,
) {
    private var server: EmbeddedServer<*, *>? = null

    /** Throws if the port cannot be bound. */
    @Synchronized
    fun start(port: Int) {
        stop()
        val newServer = embeddedServer(CIO, port = port, host = "127.0.0.1") { signingApi(store, submit, settings) }
        newServer.start(wait = false)
        try {
            // CIO binds asynchronously; wait for it so that "port in use" surfaces here.
            runBlocking { newServer.engine.resolvedConnectors() }
        } catch (e: Exception) {
            newServer.stop(0, 0)
            throw e
        }
        server = newServer
    }

    @Synchronized
    fun stop() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 2_000)
        server = null
    }
}
