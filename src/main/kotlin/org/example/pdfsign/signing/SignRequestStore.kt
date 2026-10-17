package org.example.pdfsign.signing

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** In-memory registry of signing requests. Finished requests are kept for [retention] so the client can download them. */
class SignRequestStore(private val retention: Duration = Duration.ofMinutes(30)) {
    private val requests = ConcurrentHashMap<String, SignRequest>()

    fun add(request: SignRequest) {
        purgeExpired()
        requests[request.id] = request
    }

    operator fun get(id: String): SignRequest? = requests[id]

    fun remove(id: String): SignRequest? = requests.remove(id)

    /** Requests that are still waiting for the user or being signed. */
    fun activeCount(): Int = requests.values.count { !it.status.isFinal }

    fun purgeExpired(now: Instant = Instant.now()) {
        val cutoff = now.minus(retention)
        requests.values.removeIf { it.status.isFinal && it.updatedAt.isBefore(cutoff) }
    }
}
