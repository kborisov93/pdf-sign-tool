package org.example.pdfsign.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.example.pdfsign.signing.SignRequest
import org.example.pdfsign.signing.SignStatus

@Serializable
data class ServiceStatusDto(
    val application: String,
    val version: String,
    val apiVersion: Int,
    val activeRequests: Int,
)

@Serializable
data class SignRequestDto(
    val id: String,
    /** PENDING, SIGNING, SIGNED, CANCELLED or FAILED. */
    val status: String,
    val fileName: String,
    val pageCount: Int,
    val createdAt: String,
    val updatedAt: String,
    val statusUrl: String,
    /** Present once the status is SIGNED. */
    val documentUrl: String? = null,
    val signer: SignerDto? = null,
    /** Present when the status is FAILED. */
    val error: String? = null,
)

@Serializable
data class SignerDto(val commonName: String, val subject: String, val issuer: String, val serialNumber: String)

@Serializable
data class ErrorDto(val error: String, val message: String)

internal val apiJson = Json {
    encodeDefaults = true
    explicitNulls = false
}

internal fun SignRequest.toDto(baseUrl: String): SignRequestDto {
    val statusUrl = "$baseUrl/api/v1/sign-requests/$id"
    return SignRequestDto(
        id = id,
        status = status.name,
        fileName = fileName,
        pageCount = documentInfo.pageCount,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        statusUrl = statusUrl,
        documentUrl = if (status == SignStatus.SIGNED) "$statusUrl/document" else null,
        signer = signer?.let { SignerDto(it.commonName, it.subject, it.issuer, it.serialNumber) },
        error = error,
    )
}
