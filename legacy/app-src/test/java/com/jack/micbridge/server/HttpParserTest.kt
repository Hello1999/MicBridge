package com.jack.micbridge.server

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpParserTest {
    @Test
    fun `parses valid post with lowercase header lookup`() {
        val request = parse(
            "POST /v1/mic/toggle HTTP/1.1\r\n" +
                "Host: 192.168.43.1:8787\r\n" +
                "X-MicBridge-Token: secret\r\n" +
                "X-Request-Id: 1234567890abcdef\r\n\r\n",
        )

        assertEquals("POST", request.method)
        assertEquals("/v1/mic/toggle", request.target)
        assertEquals("secret", request.headers["x-micbridge-token"])
    }

    @Test
    fun `rejects bare LF`() {
        val error = assertThrows(HttpParseException::class.java) {
            parse("GET /healthz HTTP/1.1\nHost: x\n\n")
        }
        assertEquals(400, error.status)
    }

    @Test
    fun `rejects duplicate token`() {
        val error = assertThrows(HttpParseException::class.java) {
            parse(
                "GET /v1/status HTTP/1.1\r\nHost: x\r\n" +
                    "X-MicBridge-Token: a\r\nX-MicBridge-Token: b\r\n\r\n",
            )
        }
        assertEquals(400, error.status)
    }

    @Test
    fun `rejects transfer encoding`() {
        val error = assertThrows(HttpParseException::class.java) {
            parse("POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n")
        }
        assertEquals(400, error.status)
    }

    @Test
    fun `accepts only a tiny empty JSON object body`() {
        val accepted = parse(
            "POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\n" +
                "Content-Type: application/json\r\nContent-Length: 2\r\n\r\n{}",
        )
        assertEquals("{}", accepted.body.toString(Charsets.US_ASCII))
        assertEquals(
            400,
            assertThrows(HttpParseException::class.java) {
                parse(
                    "POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\n" +
                        "Content-Type: application/json\r\nContent-Length: 7\r\n\r\n{\"x\":1}",
                )
            }.status,
        )
        assertEquals(
            400,
            assertThrows(HttpParseException::class.java) {
                parse(
                    "POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\n" +
                        "Content-Type: application/jsonevil\r\nContent-Length: 2\r\n\r\n{}",
                )
            }.status,
        )
    }

    @Test
    fun `rejects invalid or oversized content length`() {
        assertEquals(
            400,
            assertThrows(HttpParseException::class.java) {
                parse("POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\nContent-Length: nope\r\n\r\n")
            }.status,
        )
        assertEquals(
            413,
            assertThrows(HttpParseException::class.java) {
                parse("POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\nContent-Length: 65\r\n\r\n")
            }.status,
        )
        assertEquals(
            400,
            assertThrows(HttpParseException::class.java) {
                parse("POST /v1/mic/toggle HTTP/1.1\r\nHost: x\r\nContent-Length: +2\r\n\r\n{}")
            }.status,
        )
    }

    @Test
    fun `rejects non canonical request targets`() {
        listOf(
            "/v1/mic/toggle?ignored=true",
            "/v1/mic/%74oggle",
            "/v1/mic/toggle\u0000ignored",
        ).forEach { target ->
            assertEquals(
                target,
                400,
                assertThrows(HttpParseException::class.java) {
                    parse("POST $target HTTP/1.1\r\nHost: x\r\n\r\n")
                }.status,
            )
        }
    }

    @Test
    fun `rejects oversized request line and headers`() {
        assertEquals(
            413,
            assertThrows(HttpParseException::class.java) {
                parse("GET /${"a".repeat(2_100)} HTTP/1.1\r\nHost: x\r\n\r\n")
            }.status,
        )
        assertEquals(
            413,
            assertThrows(HttpParseException::class.java) {
                parse("GET /healthz HTTP/1.1\r\nHost: x\r\nX-Fill: ${"a".repeat(8_300)}\r\n\r\n")
            }.status,
        )
    }

    private fun parse(value: String): HttpRequest = HttpParser.parse(
        ByteArrayInputStream(value.toByteArray(Charsets.ISO_8859_1)),
    )
}
