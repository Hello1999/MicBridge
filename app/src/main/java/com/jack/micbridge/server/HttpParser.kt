package com.jack.micbridge.server

import java.io.InputStream

object HttpParser {
    private const val MAX_REQUEST_LINE_BYTES = 2_048
    private const val MAX_HEADER_BYTES = 8_192
    private const val MAX_HEADER_COUNT = 64
    private val METHOD_PATTERN = Regex("[A-Z]{1,16}")
    private val HEADER_NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    private val DUPLICATE_CRITICAL_HEADERS = setOf(
        "host",
        "content-length",
        "content-type",
        "transfer-encoding",
        "x-micbridge-token",
        "x-request-id",
    )

    fun parse(input: InputStream, maxDurationMs: Long = 3_000L): HttpRequest {
        val deadlineNanos = System.nanoTime() + maxDurationMs * 1_000_000L
        val requestLine = readCrlfLine(input, MAX_REQUEST_LINE_BYTES, deadlineNanos)
            ?: throw HttpParseException(400, "Empty request")
        val parts = requestLine.split(' ')
        if (parts.size != 3 || parts.any { it.isEmpty() }) {
            throw HttpParseException(400, "Malformed request line")
        }
        val (method, target, version) = parts
        if (!METHOD_PATTERN.matches(method)) throw HttpParseException(400, "Invalid method")
        if (version != "HTTP/1.1") throw HttpParseException(400, "HTTP/1.1 required")
        // MicBridge exposes a fixed, query-free API surface. Accepting controls, fragments,
        // percent-encoded aliases or a query and canonicalizing it later creates multiple wire
        // targets for the same mutation and makes request-id conflict handling ambiguous.
        if (!TARGET_PATTERN.matches(target)) {
            throw HttpParseException(400, "Invalid request target")
        }

        val headers = linkedMapOf<String, String>()
        var headerBytes = 0
        var headerCount = 0
        while (true) {
            val line = readCrlfLine(input, MAX_HEADER_BYTES - headerBytes, deadlineNanos)
                ?: throw HttpParseException(400, "Unexpected end of headers")
            headerBytes += line.toByteArray(Charsets.ISO_8859_1).size + 2
            if (headerBytes > MAX_HEADER_BYTES) throw HttpParseException(413, "Headers too large")
            if (line.isEmpty()) break
            headerCount += 1
            if (headerCount > MAX_HEADER_COUNT) throw HttpParseException(413, "Too many headers")
            if (line.firstOrNull()?.isWhitespace() == true) {
                throw HttpParseException(400, "Obsolete folded headers are forbidden")
            }
            val separator = line.indexOf(':')
            if (separator <= 0) throw HttpParseException(400, "Malformed header")
            val name = line.substring(0, separator)
            if (!HEADER_NAME_PATTERN.matches(name)) throw HttpParseException(400, "Invalid header name")
            val normalizedName = name.lowercase()
            val value = line.substring(separator + 1).trim()
            if (value.any { it.code < 0x20 && it != '\t' }) {
                throw HttpParseException(400, "Invalid header value")
            }
            if (normalizedName in headers && normalizedName in DUPLICATE_CRITICAL_HEADERS) {
                throw HttpParseException(400, "Duplicate critical header")
            }
            if (normalizedName in headers) {
                headers[normalizedName] = headers.getValue(normalizedName) + "," + value
            } else {
                headers[normalizedName] = value
            }
        }

        if (headers["host"].isNullOrBlank()) throw HttpParseException(400, "Host required")
        if (headers.containsKey("transfer-encoding")) {
            throw HttpParseException(400, "Transfer-Encoding is not supported")
        }
        val contentLength = headers["content-length"]?.let {
            if (!CONTENT_LENGTH_PATTERN.matches(it)) {
                throw HttpParseException(400, "Invalid Content-Length")
            }
            it.toLongOrNull() ?: throw HttpParseException(400, "Invalid Content-Length")
        } ?: 0L
        if (contentLength < 0) throw HttpParseException(400, "Invalid Content-Length")
        if (contentLength > MAX_BODY_BYTES) throw HttpParseException(413, "Request body is too large")
        val body = readBody(input, contentLength.toInt(), deadlineNanos)
        if (body.isNotEmpty()) {
            val contentType = headers["content-type"].orEmpty()
                .substringBefore(';')
                .trim()
                .lowercase()
            if (contentType != "application/json") {
                throw HttpParseException(400, "Only an empty JSON object is accepted")
            }
            val bodyText = body.toString(Charsets.US_ASCII)
            if (!body.contentEquals(bodyText.toByteArray(Charsets.US_ASCII)) || bodyText.trim() != "{}") {
                throw HttpParseException(400, "Only an empty JSON object is accepted")
            }
        }

        return HttpRequest(
            method = method,
            target = target,
            version = version,
            headers = headers,
            body = body,
        )
    }

    private fun readBody(input: InputStream, size: Int, deadlineNanos: Long): ByteArray {
        if (size == 0) return ByteArray(0)
        val body = ByteArray(size)
        var offset = 0
        while (offset < size) {
            if (System.nanoTime() > deadlineNanos) {
                throw HttpParseException(408, "Request deadline exceeded")
            }
            val count = input.read(body, offset, size - offset)
            if (count < 0) throw HttpParseException(400, "Unexpected end of request body")
            offset += count
        }
        return body
    }

    private fun readCrlfLine(input: InputStream, maxBytes: Int, deadlineNanos: Long): String? {
        if (maxBytes <= 0) throw HttpParseException(413, "Line too large")
        val buffer = ArrayList<Byte>(minOf(maxBytes, 256))
        var previousWasCr = false
        while (buffer.size <= maxBytes) {
            if (System.nanoTime() > deadlineNanos) {
                throw HttpParseException(408, "Request deadline exceeded")
            }
            val value = input.read()
            if (value == -1) {
                if (buffer.isEmpty() && !previousWasCr) return null
                throw HttpParseException(400, "Line must end with CRLF")
            }
            if (previousWasCr) {
                if (value == '\n'.code) {
                    return buffer.toByteArray().toString(Charsets.ISO_8859_1)
                }
                throw HttpParseException(400, "Bare CR is forbidden")
            }
            if (value == '\r'.code) {
                previousWasCr = true
            } else {
                if (value == '\n'.code) throw HttpParseException(400, "Bare LF is forbidden")
                buffer += value.toByte()
            }
        }
        throw HttpParseException(413, "Line too large")
    }

    private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }

    private const val MAX_BODY_BYTES = 64L
    private val CONTENT_LENGTH_PATTERN = Regex("[0-9]+")
    private val TARGET_PATTERN = Regex("/[A-Za-z0-9._/-]{0,2047}")
}
