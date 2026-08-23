package com.cardrhyme.motionserver

import android.content.Context
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class LocalHttpServer(private val context: Context) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(4)

    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress("0.0.0.0", RuntimeState.PORT))
        serverSocket = socket
        running = true

        acceptThread = Thread({
            while (running) {
                try {
                    val client = socket.accept()
                    workers.execute { handle(client) }
                } catch (_: Exception) {
                    if (running) RuntimeState.lastError = "HTTP accept loop stopped unexpectedly"
                }
            }
        }, "motion-http-accept").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        workers.shutdownNow()
        acceptThread = null
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = 1_500
                val reader = client.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ')
                if (parts.size < 2) {
                    respond(client, 400, "Bad Request", "{\"error\":\"bad request\"}")
                    return
                }

                val method = parts[0]
                val path = parts[1].substringBefore('?')

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                if (method == "OPTIONS") {
                    respond(client, 204, "No Content", "")
                    return
                }

                if (method != "GET") {
                    respond(client, 405, "Method Not Allowed", "{\"error\":\"GET only\"}")
                    return
                }

                when (path) {
                    "/", "/api/state", "/api/v1/state" ->
                        respond(client, 200, "OK", RuntimeState.stateJson(context))
                    else ->
                        respond(client, 404, "Not Found", "{\"error\":\"not found\"}")
                }
            } catch (_: Exception) {
                // Short polling means clients disconnect frequently; per-request failures are non-fatal.
            }
        }
    }

    private fun respond(socket: Socket, code: Int, reason: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Private-Network: true\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)

        BufferedOutputStream(socket.getOutputStream()).use { out ->
            out.write(headers)
            if (bytes.isNotEmpty()) out.write(bytes)
            out.flush()
        }
    }
}
