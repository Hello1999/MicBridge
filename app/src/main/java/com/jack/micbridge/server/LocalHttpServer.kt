package com.jack.micbridge.server

import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

class LocalHttpServer(
    private val port: Int,
    private val router: HttpRequestRouter,
    private val onError: (String) -> Unit = {},
) : AutoCloseable {
    private val running = AtomicBoolean(false)
    private val sockets = mutableListOf<ServerSocket>()
    private val acceptors = mutableListOf<Thread>()
    private val activeClients = mutableSetOf<Socket>()
    private val workers = Executors.newFixedThreadPool(MAX_CONNECTIONS) { runnable ->
        Thread(runnable, "MicBridge-http-worker").apply { isDaemon = true }
    }
    private val permits = Semaphore(MAX_CONNECTIONS)

    @Synchronized
    fun start(addresses: List<Inet4Address>): List<String> {
        check(!running.get()) { "Server already running" }
        val bound = mutableListOf<String>()
        addresses.forEach { address ->
            runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, port), ACCEPT_BACKLOG)
                }
            }.onSuccess { serverSocket ->
                sockets += serverSocket
                bound += "${address.hostAddress}:$port"
            }.onFailure { error ->
                onError("无法绑定 ${address.hostAddress}:$port：${error.message}")
            }
        }
        if (sockets.isEmpty()) return emptyList()
        running.set(true)
        sockets.forEach { socket ->
            acceptors += Thread({ acceptLoop(socket) }, "MicBridge-http-${socket.localPort}").apply {
                isDaemon = true
                start()
            }
        }
        return bound
    }

    private fun acceptLoop(serverSocket: ServerSocket) {
        while (running.get()) {
            val client = try {
                serverSocket.accept()
            } catch (error: SocketException) {
                if (running.get()) onError("HTTP listener 异常停止：${error.message}")
                break
            } catch (error: Exception) {
                if (running.get()) onError("HTTP accept 失败：${error.message}")
                continue
            }
            if (!permits.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            val registered = synchronized(this) {
                val samePeer = activeClients.count { it.inetAddress == client.inetAddress }
                if (running.get() && samePeer < MAX_CONNECTIONS_PER_PEER) {
                    activeClients.add(client)
                } else {
                    false
                }
            }
            if (!registered) {
                runCatching { client.close() }
                permits.release()
                if (running.get()) continue else break
            }
            try {
                workers.execute {
                    try {
                        handle(client)
                    } finally {
                        synchronized(this) { activeClients.remove(client) }
                        permits.release()
                    }
                }
            } catch (_: RejectedExecutionException) {
                synchronized(this) { activeClients.remove(client) }
                runCatching { client.close() }
                permits.release()
            }
        }
    }

    internal fun handle(socket: Socket) {
        socket.use { client ->
            client.soTimeout = READ_TIMEOUT_MS
            val response = try {
                val request = HttpParser.parse(client.getInputStream())
                runBlocking { router.route(request) }
            } catch (error: HttpParseException) {
                errorResponse(error.status, "HTTP_PARSE_ERROR", error.message.orEmpty())
            } catch (error: java.net.SocketTimeoutException) {
                errorResponse(408, "HTTP_PARSE_ERROR", "请求读取超时")
            } catch (error: Exception) {
                // A generic router/read failure carries no proof that an authenticated mutation
                // was admitted. Closing this connection is sufficient; ApiRouter converts
                // admitted mutation exceptions into a response with explicit safety metadata.
                errorResponse(500, "INTERNAL_ERROR", "HTTP 请求处理失败")
            }
            // Give the caller a deterministic error before the service revokes this listener.
            // The failure callback then performs the fail-closed boundary and safe rebind.
            val writeFailure = writeResponse(client, response)
            if (writeFailure != null) {
                if (response.failClosedOnWriteFailure) {
                    // The authenticated mutation may already have committed. Losing its
                    // response means the remote cannot know the result, so revoke and BLOCK.
                    onError("修改响应写入失败：${writeFailure.javaClass.simpleName}")
                }
                return
            }
            if (response.failClosedAfterResponse) {
                onError("修改请求内部失败；执行安全边界")
            }
        }
    }

    private fun writeResponse(socket: Socket, response: HttpResponse): Throwable? {
        val body = response.body.toByteArray(StandardCharsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ${response.status} ${response.reason}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            response.extraHeaders.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        return runCatching {
            BufferedOutputStream(socket.getOutputStream()).use { output ->
                output.write(head)
                output.write(body)
                output.flush()
            }
        }.exceptionOrNull()
    }

    private fun errorResponse(status: Int, code: String, message: String) = HttpResponse(
        status,
        Json.objectOf(
            "ok" to false,
            "mic_access" to "unknown",
            "verified" to false,
            "error" to Json.error(code, message),
        ),
    )

    @Synchronized
    override fun close() {
        running.set(false)
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        // Close accepted sockets before the service performs its network-boundary BLOCK.
        // A worker whose mutation already completed may otherwise publish a cached OPEN
        // success after that BLOCK and give the remote an incorrect one-pulse response.
        activeClients.forEach { runCatching { it.close() } }
        activeClients.clear()
        acceptors.forEach { it.interrupt() }
        acceptors.clear()
        // Stop accepting new work but let an authenticated in-flight mutation reach its
        // durable ledger completion. Interrupting it between a controller mutation and readback
        // would create an avoidable UNKNOWN transition (the pre-armed lease is still the
        // final safety net if the process itself dies).
        workers.shutdown()
    }

    companion object {
        private const val READ_TIMEOUT_MS = 3_000
        private const val MAX_CONNECTIONS = 4
        private const val MAX_CONNECTIONS_PER_PEER = 2
        private const val ACCEPT_BACKLOG = 8
    }
}
