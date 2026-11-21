package dev.thathunky.meteora.companion

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Where a paired PC can be reached. */
data class Endpoint(val host: String, val port: Int = DEFAULT_PORT) {
    val baseUrl: String
        get() = if (':' in host && !host.startsWith('[')) "http://[$host]:$port" else "http://$host:$port"

    companion object {
        const val DEFAULT_PORT = 9863
    }
}

class CompanionException(
    val httpStatus: Int,
    val code: String?,
    message: String?,
) : Exception(message ?: code ?: "HTTP $httpStatus") {
    val isUnauthorized get() = httpStatus == 401 || code == "UNAUTHORIZED"
    val isRateLimited get() = httpStatus == 429
    val isAuthorizationDisabled get() = code == "AUTHORIZATION_DISABLED"
    val isAuthorizationDenied get() = code == "AUTHORIZATION_DENIED" || code == "AUTHORIZATION_TIME_OUT"
}

class HostNotAllowedException(host: String) : Exception("Refusing non-local companion host: $host")

val CompanionJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

/** REST side of the YTMDesktop Companion Server v1. Stateless: callers pass endpoint and token. */
class CompanionClient(private val http: HttpClient) {

    suspend fun metadata(endpoint: Endpoint, timeoutMs: Long = 2_000): MetadataDto {
        checkHost(endpoint)
        return http.get("${endpoint.baseUrl}/metadata") {
            timeout { requestTimeoutMillis = timeoutMs; connectTimeoutMillis = timeoutMs }
        }.orThrow().body()
    }

    suspend fun requestCode(endpoint: Endpoint, appVersion: String): String {
        checkHost(endpoint)
        return http.post("${endpoint.baseUrl}/api/v1/auth/requestcode") {
            contentType(ContentType.Application.Json)
            setBody(CodeRequestDto(APP_ID, APP_NAME, appVersion))
        }.orThrow().body<CodeResponseDto>().code
    }

    /** Blocks until the user answers the dialog on the PC (server times out after 30 s). */
    suspend fun requestToken(endpoint: Endpoint, code: String): String {
        checkHost(endpoint)
        return http.post("${endpoint.baseUrl}/api/v1/auth/request") {
            contentType(ContentType.Application.Json)
            setBody(TokenRequestDto(APP_ID, code))
            timeout { requestTimeoutMillis = 45_000 }
        }.orThrow().body<TokenResponseDto>().token
    }

    suspend fun state(endpoint: Endpoint, token: String): StateDto {
        checkHost(endpoint)
        return http.get("${endpoint.baseUrl}/api/v1/state") {
            header(HttpHeaders.Authorization, token)
        }.orThrow().body()
    }

    suspend fun send(endpoint: Endpoint, token: String, command: Command) {
        checkHost(endpoint)
        http.post("${endpoint.baseUrl}/api/v1/command") {
            header(HttpHeaders.Authorization, token)
            contentType(ContentType.Application.Json)
            setBody(command.toJson().toString())
        }.orThrow()
    }

    private fun checkHost(endpoint: Endpoint) {
        if (!HostPolicy.isAllowed(endpoint.host)) throw HostNotAllowedException(endpoint.host)
    }

    private suspend fun HttpResponse.orThrow(): HttpResponse {
        if (status.isSuccess()) return this
        val err = runCatching { CompanionJson.decodeFromString<ErrorDto>(bodyAsText()) }.getOrNull()
        throw CompanionException(status.value, err?.code ?: err?.error, err?.message)
    }

    companion object {
        const val APP_ID = "meteora"
        const val APP_NAME = "Meteora"

        fun defaultHttpClient(engine: io.ktor.client.engine.HttpClientEngine) = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(CompanionJson) }
            install(HttpTimeout) {
                connectTimeoutMillis = 3_000
                requestTimeoutMillis = 8_000
            }
        }
    }
}
