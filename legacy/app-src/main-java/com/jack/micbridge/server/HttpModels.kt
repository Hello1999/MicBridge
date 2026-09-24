package com.jack.micbridge.server

data class HttpRequest(
    val method: String,
    val target: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray = ByteArray(0),
)

data class HttpResponse(
    val status: Int,
    val body: String,
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * Internal transport policy, never serialized. Only an authenticated mutation may request
     * a fail-closed service boundary when its response is lost.
     */
    val failClosedOnWriteFailure: Boolean = false,
    /** A mutation crashed after admission, so block/rebind even when its 500 response is sent. */
    val failClosedAfterResponse: Boolean = false,
) {
    val reason: String
        get() = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            413 -> "Content Too Large"
            423 -> "Locked"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }
}

fun interface HttpRequestRouter {
    suspend fun route(request: HttpRequest): HttpResponse
}

class HttpParseException(
    val status: Int,
    message: String,
) : Exception(message)
