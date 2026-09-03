package com.jack.micbridge.server

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHttpServerLifecycleTest {
    @Test
    fun `slow partial request is closed with 408`() {
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpServer(port, HttpRequestRouter { HttpResponse(200, "{\"ok\":true}") })
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        assertEquals(listOf("127.0.0.1:$port"), server.start(listOf(address)))

        val client = Socket(address, port).apply { soTimeout = 5_000 }
        try {
            client.getOutputStream().apply {
                write("GET /healthz HTTP/1.1\r\nHost:".toByteArray())
                flush()
            }

            val response = client.getInputStream().bufferedReader().readText()

            assertTrue(response.startsWith("HTTP/1.1 408 Request Timeout"))
            assertTrue(response.contains("HTTP_PARSE_ERROR"))
        } finally {
            runCatching { client.close() }
            server.close()
        }
    }

    @Test
    fun `one peer cannot occupy every worker with simultaneous requests`() {
        val twoEntered = CountDownLatch(2)
        val twoFinished = CountDownLatch(2)
        val release = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpServer(
            port,
            HttpRequestRouter {
                twoEntered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                twoFinished.countDown()
                HttpResponse(200, "{\"ok\":true}")
            },
        )
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        assertEquals(listOf("127.0.0.1:$port"), server.start(listOf(address)))
        val clients = mutableListOf<Socket>()
        try {
            repeat(2) {
                clients += Socket(address, port).apply {
                    soTimeout = 5_000
                    getOutputStream().apply {
                        write(
                            "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        flush()
                    }
                }
            }
            assertTrue(twoEntered.await(3, TimeUnit.SECONDS))
            val thirdSamePeer = Socket(address, port).apply { soTimeout = 3_000 }
            clients += thirdSamePeer
            val read = try {
                thirdSamePeer.getInputStream().read()
            } catch (_: SocketTimeoutException) {
                Int.MIN_VALUE
            }
            assertEquals("same-peer over-capacity connection should close promptly", -1, read)

            release.countDown()
            assertTrue(twoFinished.await(3, TimeUnit.SECONDS))
            clients.take(2).forEach { runCatching { it.close() } }
            val recoveredResponse = Socket(address, port).use { recovered ->
                recovered.soTimeout = 3_000
                recovered.getOutputStream().apply {
                    write("GET /healthz HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    flush()
                }
                recovered.getInputStream().bufferedReader().readText()
            }
            assertTrue(recoveredResponse.startsWith("HTTP/1.1 200 OK"))

        } finally {
            release.countDown()
            clients.forEach { runCatching { it.close() } }
            server.close()
        }
    }

    @Test
    fun `close terminates an accepted socket before a delayed success can be written`() {
        val routeEntered = CountDownLatch(1)
        val finishRoute = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpServer(
            port = port,
            router = HttpRequestRouter {
                routeEntered.countDown()
                check(finishRoute.await(3, TimeUnit.SECONDS))
                HttpResponse(200, "{\"ok\":true,\"mic_access\":\"open\"}")
            },
        )
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        assertEquals(listOf("127.0.0.1:$port"), server.start(listOf(address)))

        val client = Socket(address, port)
        client.soTimeout = 3_000
        val response = StringBuilder()
        val reader = thread(start = true, isDaemon = true) {
            runCatching {
                client.getInputStream().bufferedReader().use { input ->
                    while (true) response.append(input.readLine() ?: break).append('\n')
                }
            }
        }
        try {
            client.getOutputStream().write(
                "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            client.getOutputStream().flush()
            assertTrue(routeEntered.await(3, TimeUnit.SECONDS))

            server.close()
            finishRoute.countDown()
            reader.join(3_000L)

            assertTrue("client reader should terminate after close", !reader.isAlive)
            assertTrue("stale OPEN success must not reach the client", !response.contains("open"))
        } finally {
            finishRoute.countDown()
            runCatching { client.close() }
            server.close()
        }
    }

    @Test
    fun `unclassified router exception returns internal error without fail closed callback`() {
        val failureReported = CountDownLatch(1)
        val port = ServerSocket(0).use { it.localPort }
        val server = LocalHttpServer(
            port = port,
            router = HttpRequestRouter { error("boom") },
            onError = { failureReported.countDown() },
        )
        val address = InetAddress.getByName("127.0.0.1") as Inet4Address
        assertEquals(listOf("127.0.0.1:$port"), server.start(listOf(address)))
        try {
            val response = Socket(address, port).use { client ->
                client.soTimeout = 3_000
                client.getOutputStream().apply {
                    write("GET /healthz HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                    flush()
                }
                client.getInputStream().bufferedReader().readText()
            }

            assertTrue(response.startsWith("HTTP/1.1 500 Internal Server Error"))
            assertTrue(response.contains("INTERNAL_ERROR"))
            assertTrue(!response.contains("HTTP_PARSE_ERROR"))
            assertTrue(!failureReported.await(100, TimeUnit.MILLISECONDS))
        } finally {
            server.close()
        }
    }

    @Test
    fun `authenticated mutation response write failure reports fail closed callback`() {
        var routed = 0
        val failures = mutableListOf<String>()
        val server = LocalHttpServer(
            port = 8787,
            router = HttpRequestRouter {
                routed++
                HttpResponse(
                    status = 200,
                    body = "{\"ok\":true,\"mic_access\":\"open\"}",
                    failClosedOnWriteFailure = true,
                )
            },
            onError = failures::add,
        )

        server.handle(
            StubSocket(
                request = validRequest(),
                response = FailingOutputStream(),
            ),
        )

        assertEquals(1, routed)
        assertEquals(1, failures.size)
        assertTrue(failures.single().startsWith("修改响应写入失败："))
        assertTrue(failures.single().contains("SocketException"))
    }

    @Test
    fun `parse error response write failure cannot trigger fail closed callback`() {
        var routed = 0
        val failures = mutableListOf<String>()
        val server = LocalHttpServer(
            port = 8787,
            router = HttpRequestRouter {
                routed++
                HttpResponse(200, "{\"ok\":true}")
            },
            onError = failures::add,
        )

        server.handle(
            StubSocket(
                request = "not-http\r\n\r\n",
                response = FailingOutputStream(),
            ),
        )

        assertEquals(0, routed)
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `ordinary response write failure cannot trigger fail closed callback`() {
        val failures = mutableListOf<String>()
        val server = LocalHttpServer(
            port = 8787,
            router = HttpRequestRouter { HttpResponse(401, "{\"ok\":false}") },
            onError = failures::add,
        )

        server.handle(
            StubSocket(
                request = validRequest(),
                response = FailingOutputStream(),
            ),
        )

        assertTrue(failures.isEmpty())
    }

    @Test
    fun `admitted mutation exception reports fail closed only after envelope is written`() {
        val failures = mutableListOf<String>()
        val response = ByteArrayOutputStream()
        val server = LocalHttpServer(
            port = 8787,
            router = HttpRequestRouter {
                HttpResponse(
                    status = 200,
                    body = "{\"ok\":false,\"mic_access\":\"unknown\"}",
                    failClosedOnWriteFailure = true,
                    failClosedAfterResponse = true,
                )
            },
            onError = failures::add,
        )

        server.handle(StubSocket(validRequest(), response))

        assertTrue(response.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200"))
        assertEquals(listOf("修改请求内部失败；执行安全边界"), failures)
    }

    @Test
    fun `successful response write does not report a failure`() {
        val failures = mutableListOf<String>()
        val response = ByteArrayOutputStream()
        val server = LocalHttpServer(
            port = 8787,
            router = HttpRequestRouter { HttpResponse(200, "{\"ok\":true}") },
            onError = failures::add,
        )

        server.handle(StubSocket(validRequest(), response))

        assertTrue(failures.isEmpty())
        assertTrue(response.toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200 OK"))
    }

    private class StubSocket(
        request: String,
        private val response: OutputStream,
    ) : Socket() {
        private val requestBytes = ByteArrayInputStream(request.toByteArray(Charsets.US_ASCII))

        override fun getInputStream(): InputStream = requestBytes

        override fun getOutputStream(): OutputStream = response

        override fun setSoTimeout(timeout: Int) = Unit

        override fun close() = Unit
    }

    private class FailingOutputStream : OutputStream() {
        override fun write(value: Int) {
            throw SocketException("Broken pipe")
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            throw SocketException("Broken pipe")
        }
    }

    private fun validRequest(): String =
        "GET /healthz HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
}
