package com.ai.agentpro

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BridgeServer(
    private val context: Context
) {

    companion object {
        private const val TAG = "AndroidAgentPro.Bridge"
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 2 * 1024 * 1024
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val GESTURE_TIMEOUT_MS = 5_000L
        private const val SERVER_BACKLOG = 16
        private const val WORKER_COUNT = 4

        @Volatile
        private var instance: BridgeServer? = null

        fun getInstance(context: Context): BridgeServer {
            return instance ?: synchronized(this) {
                instance ?: BridgeServer(
                    context.applicationContext
                ).also {
                    instance = it
                }
            }
        }
    }

    private val running = AtomicBoolean(false)

    @Volatile
    private var workers: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var commandExecutor: java.util.concurrent.ExecutorService? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    private val authenticationToken: String by lazy {
        BridgeProtocol.getOrCreateToken(context)
    }

    fun start() {
        if (running.get()) {
            Log.i(TAG, "Bridge server already running")
            return
        }

        synchronized(this) {
            if (running.get()) {
                return
            }

            try {
                val socket = ServerSocket(
                    BridgeProtocol.SERVER_PORT,
                    SERVER_BACKLOG,
                    InetAddress.getByName(
                        BridgeProtocol.SERVER_HOST
                    )
                )

                socket.soTimeout = 1_000

                workers = Executors.newFixedThreadPool(
                    WORKER_COUNT
                )

                commandExecutor = Executors.newCachedThreadPool()

                serverSocket = socket
                running.set(true)

                val thread = Thread(
                    {
                        acceptLoop()
                    },
                    "AndroidAgentPro-BridgeAccept"
                )

                thread.isDaemon = true
                acceptThread = thread
                thread.start()

                Log.i(
                    TAG,
                    "Bridge server started on " +
                        "${BridgeProtocol.SERVER_HOST}:${BridgeProtocol.SERVER_PORT}"
                )
            } catch (exception: Exception) {
                running.set(false)

                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }

                serverSocket = null

                Log.e(
                    TAG,
                    "Unable to start bridge server",
                    exception
                )

                throw exception
            }
        }
    }

    fun stop() {
        synchronized(this) {
            if (!running.getAndSet(false)) {
                return
            }

            Log.i(TAG, "Stopping bridge server")

            try {
                serverSocket?.close()
            } catch (exception: IOException) {
                Log.w(
                    TAG,
                    "Error while closing bridge socket",
                    exception
                )
            }

            serverSocket = null

            acceptThread?.interrupt()
            acceptThread = null

            workers?.shutdownNow()
            workers = null

            commandExecutor?.shutdownNow()
            commandExecutor = null

            Log.i(TAG, "Bridge server stopped")
        }
    }

    fun isRunning(): Boolean {
        return running.get() &&
            serverSocket?.isClosed == false
    }


    private fun acceptLoop() {
        while (running.get()) {
            try {
                val socket = serverSocket?.accept()
                    ?: break

                socket.soTimeout = SOCKET_TIMEOUT_MS

                val executor = workers

                if (executor == null || executor.isShutdown) {
                    Log.w(
                        TAG,
                        "Rejecting client because worker executor is unavailable"
                    )
                    socket.close()
                    continue
                }

                executor.execute {
                    handleClient(socket)
                }
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (exception: SocketException) {
                if (running.get()) {
                    Log.e(
                        TAG,
                        "Bridge accept loop socket failure",
                        exception
                    )
                }
            } catch (exception: Exception) {
                if (running.get()) {
                    Log.e(
                        TAG,
                        "Bridge accept loop failure",
                        exception
                    )
                }
            }
        }

        Log.i(TAG, "Bridge accept loop stopped")
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            try {
                val request = readHttpRequest(socket)

                if (request.method != "POST") {
                    writeHttpResponse(
                        socket,
                        405,
                        BridgeProtocol.error(
                            request.requestId,
                            "METHOD_NOT_ALLOWED",
                            "Only POST is supported"
                        )
                    )
                    return
                }

                if (request.path != "/v1/command") {
                    writeHttpResponse(
                        socket,
                        404,
                        BridgeProtocol.error(
                            request.requestId,
                            "NOT_FOUND",
                            "Endpoint not found"
                        )
                    )
                    return
                }

                if (!secureEquals(
                        authenticationToken,
                        request.authorizationToken
                    )
                ) {
                    Log.w(TAG, "Unauthorized bridge request")

                    writeHttpResponse(
                        socket,
                        401,
                        BridgeProtocol.error(
                            request.requestId,
                            "UNAUTHORIZED",
                            "Authentication failed"
                        )
                    )
                    return
                }

                val bridgeRequest = try {
                    BridgeProtocol.parseRequest(
                        request.body
                    )
                } catch (exception: BridgeProtocol.BridgeProtocolException) {
                    writeHttpResponse(
                        socket,
                        400,
                        BridgeProtocol.error(
                            request.requestId.ifBlank {
                                BridgeProtocol.newRequestId()
                            },
                            exception.code,
                            exception.message
                        )
                    )
                    return
                }

                val response = executeCommand(
                    bridgeRequest
                )

                writeHttpResponse(
                    socket,
                    if (response.ok) 200 else response.httpCode,
                    response.json
                )
            } catch (exception: Exception) {
                Log.e(
                    TAG,
                    "Client handling failed",
                    exception
                )

                try {
                    writeHttpResponse(
                        socket,
                        500,
                        BridgeProtocol.error(
                            BridgeProtocol.newRequestId(),
                            "INTERNAL_ERROR",
                            "Internal bridge error"
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun executeCommand(
        request: BridgeProtocol.BridgeRequest
    ): CommandResponse {
        return try {
            when (request.command) {
                "health" -> {
                    val accessibility =
                        AgentAccessibilityService.getInstance(
                        )

                    CommandResponse.success(
                        BridgeProtocol.success(
                            request.requestId,
                            JSONObject()
                                .put(
                                    "server_running",
                                    isRunning()
                                )
                                .put(
                                    "accessibility_connected",
                                    accessibility?.isConnected()
                                        == true
                                )
                        )
                    )
                }

                "ui_dump" -> {
                    val accessibility =
                        AgentAccessibilityService.getInstance()

                    if (accessibility == null ||
                        !accessibility.isConnected()
                    ) {
                        return CommandResponse.failure(
                            503,
                            BridgeProtocol.error(
                                request.requestId,
                                "ACCESSIBILITY_NOT_CONNECTED",
                                "Accessibility service is not connected"
                            )
                        )
                    }

                    val result = accessibility.dumpUi()

                    if (!result.success || result.root == null) {
                        CommandResponse.failure(
                            503,
                            BridgeProtocol.error(
                                request.requestId,
                                result.errorCode
                                    ?: "UI_DUMP_FAILED",
                                result.errorMessage
                                    ?: "UI dump failed"
                            )
                        )
                    } else {
                        CommandResponse.success(
                            BridgeProtocol.success(
                                request.requestId,
                                JSONObject()
                                    .put(
                                        "root",
                                        BridgeProtocol.uiNodeToJson(
                                            result.root
                                        )
                                    )
                            )
                        )
                    }
                }

                "tap" -> executeTap(request)

                "back" -> executeBack(request)

                else -> {
                    CommandResponse.failure(
                        400,
                        BridgeProtocol.error(
                            request.requestId,
                            "UNKNOWN_COMMAND",
                            "Unsupported command: ${request.command}"
                        )
                    )
                }
            }
        } catch (exception: Exception) {
            Log.e(
                TAG,
                "Command execution failed: ${request.command}",
                exception
            )

            CommandResponse.failure(
                500,
                BridgeProtocol.error(
                    request.requestId,
                    "COMMAND_EXECUTION_FAILED",
                    exception.message
                        ?: "Command execution failed"
                )
            )
        }
    }

    private fun executeTap(
        request: BridgeProtocol.BridgeRequest
    ): CommandResponse {
        if (!request.args.has("x") ||
            !request.args.has("y")
        ) {
            return CommandResponse.failure(
                400,
                BridgeProtocol.error(
                    request.requestId,
                    "MISSING_COORDINATES",
                    "tap requires x and y"
                )
            )
        }

        val x = request.args.optDouble("x", Double.NaN)
        val y = request.args.optDouble("y", Double.NaN)

        if (!x.isFinite() || !y.isFinite()) {
            return CommandResponse.failure(
                400,
                BridgeProtocol.error(
                    request.requestId,
                    "INVALID_COORDINATES",
                    "x and y must be finite numbers"
                )
            )
        }

        val accessibility =
            AgentAccessibilityService.getInstance()

        if (accessibility == null ||
            !accessibility.isConnected()
        ) {
            return CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    "ACCESSIBILITY_NOT_CONNECTED",
                    "Accessibility service is not connected"
                )
            )
        }

        val executor = commandExecutor
            ?: return CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    "COMMAND_EXECUTOR_UNAVAILABLE",
                    "Bridge command executor is unavailable"
                )
            )

        if (executor.isShutdown) {
            return CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    "COMMAND_EXECUTOR_STOPPED",
                    "Bridge command executor is stopped"
                )
            )
        }

        val future: Future<AgentAccessibilityService.GestureResult> =
            executor.submit<AgentAccessibilityService.GestureResult> {
                val lock = java.util.concurrent.CountDownLatch(1)

                var result: AgentAccessibilityService.GestureResult? =
                    null

                accessibility.tap(
                    x.toFloat(),
                    y.toFloat()
                ) {
                    result = it
                    lock.countDown()
                }

                if (!lock.await(
                        GESTURE_TIMEOUT_MS,
                        TimeUnit.MILLISECONDS
                    )
                ) {
                    AgentAccessibilityService.GestureResult(
                        false,
                        "GESTURE_TIMEOUT",
                        "Gesture confirmation timed out",
                        null
                    )
                } else {
                    result
                        ?: AgentAccessibilityService.GestureResult(
                            false,
                            "GESTURE_NO_RESULT",
                            "Gesture returned no result",
                            null
                        )
                }
            }

        val result = future.get(
            GESTURE_TIMEOUT_MS + 1_000,
            TimeUnit.MILLISECONDS
        )

        return if (result.success) {
            CommandResponse.success(
                BridgeProtocol.success(
                    request.requestId,
                    JSONObject()
                        .put(
                            "operation_id",
                            result.operationId
                        )
                        .put("gesture_completed", true)
                )
            )
        } else {
            CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    result.errorCode
                        ?: "GESTURE_FAILED",
                    result.errorMessage
                        ?: "Gesture failed"
                )
            )
        }
    }

    private fun executeBack(
        request: BridgeProtocol.BridgeRequest
    ): CommandResponse {
        val accessibility =
            AgentAccessibilityService.getInstance()

        if (accessibility == null ||
            !accessibility.isConnected()
        ) {
            return CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    "ACCESSIBILITY_NOT_CONNECTED",
                    "Accessibility service is not connected"
                )
            )
        }

        val lock = java.util.concurrent.CountDownLatch(1)

        var result: AgentAccessibilityService.GestureResult? =
            null

        accessibility.back {
            result = it
            lock.countDown()
        }

        val completed = lock.await(
            GESTURE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS
        )

        if (!completed) {
            return CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    "BACK_TIMEOUT",
                    "Back action confirmation timed out"
                )
            )
        }

        val finalResult = result

        return if (finalResult?.success == true) {
            CommandResponse.success(
                BridgeProtocol.success(
                    request.requestId,
                    JSONObject()
                        .put(
                            "operation_id",
                            finalResult.operationId
                        )
                        .put("dispatched", true)
                )
            )
        } else {
            CommandResponse.failure(
                503,
                BridgeProtocol.error(
                    request.requestId,
                    finalResult?.errorCode
                        ?: "BACK_FAILED",
                    finalResult?.errorMessage
                        ?: "Back action failed"
                )
            )
        }
    }

    private fun readHttpRequest(
        socket: Socket
    ): HttpRequest {
        val input = BufferedReader(
            InputStreamReader(
                socket.getInputStream(),
                StandardCharsets.UTF_8
            )
        )

        var totalHeaderCharacters = 0

        val requestLine = input.readLine()
            ?: throw IOException("Missing HTTP request line")

        totalHeaderCharacters += requestLine.length

        if (totalHeaderCharacters > MAX_HEADER_BYTES) {
            throw IOException("HTTP headers are too large")
        }

        val requestParts = requestLine.split(" ")

        if (requestParts.size != 3) {
            throw IOException("Malformed HTTP request line")
        }

        val method = requestParts[0]
        val path = requestParts[1]
        val version = requestParts[2]

        if (version != "HTTP/1.1" &&
            version != "HTTP/1.0"
        ) {
            throw IOException("Unsupported HTTP version")
        }

        var contentLength = 0
        var authorizationToken = ""
        var requestId = ""

        while (true) {
            val line = input.readLine()
                ?: throw IOException("Unexpected end of HTTP headers")

            totalHeaderCharacters += line.length + 2

            if (totalHeaderCharacters > MAX_HEADER_BYTES) {
                throw IOException("HTTP headers are too large")
            }

            if (line.isEmpty()) {
                break
            }

            val separator = line.indexOf(':')

            if (separator <= 0) {
                throw IOException("Malformed HTTP header")
            }

            val name = line.substring(
                0,
                separator
            ).trim().lowercase()

            val value = line.substring(
                separator + 1
            ).trim()

            when (name) {
                "content-length" -> {
                    contentLength = value.toIntOrNull()
                        ?: throw IOException(
                            "Invalid Content-Length"
                        )
                }

                "authorization" -> {
                    authorizationToken =
                        parseBearerToken(value)
                }

                "x-request-id" -> {
                    requestId = value
                }
            }
        }

        if (contentLength < 0 ||
            contentLength > MAX_BODY_BYTES
        ) {
            throw IOException(
                "Request body exceeds allowed size"
            )
        }

        val body = CharArray(contentLength)

        var offset = 0

        while (offset < contentLength) {
            val read = input.read(
                body,
                offset,
                contentLength - offset
            )

            if (read < 0) {
                throw IOException(
                    "Unexpected end of HTTP request body"
                )
            }

            offset += read
        }

        return HttpRequest(
            method = method,
            path = path,
            body = String(body),
            authorizationToken = authorizationToken,
            requestId = requestId
        )
    }

    private fun parseBearerToken(
        value: String
    ): String {
        val prefix = "Bearer "

        return if (value.startsWith(
                prefix,
                ignoreCase = true
            )
        ) {
            value.substring(prefix.length).trim()
        } else {
            ""
        }
    }

    private fun writeHttpResponse(
        socket: Socket,
        statusCode: Int,
        body: JSONObject
    ) {
        val bytes = body
            .toString()
            .toByteArray(StandardCharsets.UTF_8)

        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "Error"
        }

        val writer = BufferedWriter(
            OutputStreamWriter(
                socket.getOutputStream(),
                StandardCharsets.UTF_8
            )
        )

        writer.write(
            "HTTP/1.1 $statusCode $statusText\r\n"
        )
        writer.write("Content-Type: application/json; charset=utf-8\r\n")
        writer.write("Content-Length: ${bytes.size}\r\n")
        writer.write("Connection: close\r\n")
        writer.write("\r\n")
        writer.flush()

        socket.getOutputStream().write(bytes)
        socket.getOutputStream().flush()
    }

    private fun secureEquals(
        expected: String,
        actual: String
    ): Boolean {
        val expectedBytes =
            expected.toByteArray(StandardCharsets.UTF_8)

        val actualBytes =
            actual.toByteArray(StandardCharsets.UTF_8)

        if (expectedBytes.size != actualBytes.size) {
            return false
        }

        var result = 0

        for (index in expectedBytes.indices) {
            result = result or (
                expectedBytes[index].toInt() xor
                    actualBytes[index].toInt()
                )
        }

        return result == 0
    }

    data class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
        val authorizationToken: String,
        val requestId: String
    )

    data class CommandResponse(
        val ok: Boolean,
        val httpCode: Int,
        val json: JSONObject
    ) {
        companion object {
            fun success(
                json: JSONObject
            ): CommandResponse {
                return CommandResponse(
                    true,
                    200,
                    json
                )
            }

            fun failure(
                httpCode: Int,
                json: JSONObject
            ): CommandResponse {
                return CommandResponse(
                    false,
                    httpCode,
                    json
                )
            }
        }
    }
}
