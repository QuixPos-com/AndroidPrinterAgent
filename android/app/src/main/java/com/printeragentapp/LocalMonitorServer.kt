package com.printeragentapp

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LocalMonitorServer(private val preferredPort: Int, private val engine: AgentEngine) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val subscribers = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()
    var boundPort: Int = preferredPort
        private set

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        Thread({
            var sock: ServerSocket? = null
            val loopback = InetAddress.getByName("127.0.0.1")
            try {
                sock = ServerSocket(preferredPort, 50, loopback)
                boundPort = preferredPort
            } catch (_: Exception) {
                for (p in 41782..41799) {
                    try {
                        sock = ServerSocket(p, 50, loopback)
                        boundPort = p
                        break
                    } catch (_: Exception) {
                    }
                }
                if (sock == null) {
                    try {
                        sock = ServerSocket(0, 50, loopback)
                        boundPort = sock.localPort
                    } catch (e: Exception) {
                        engine.logf("Local monitor server failed to bind: ${e.message}")
                        return@Thread
                    }
                }
            }

            serverSocket = sock
            engine.logf("Local monitor listening on http://127.0.0.1:$boundPort")

            while (running.get()) {
                try {
                    val client = sock?.accept() ?: break
                    Thread { handleClient(client) }.apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!running.get()) break
                }
            }
        }, "LocalMonitorHttp").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        subscribers.clear()
    }

    fun broadcast(type: String, data: Any?) {
        val msg = JSONObject().apply {
            put("type", type)
            put("timestamp", isoNow())
            if (data != null) put("data", data)
        }.toString()

        for (q in subscribers) {
            q.offer(msg)
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 10000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val out = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val qIdx = fullPath.indexOf('?')
            val path = if (qIdx != -1) fullPath.substring(0, qIdx) else fullPath
            val queryStr = if (qIdx != -1) fullPath.substring(qIdx + 1) else ""

            var contentLength = 0
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) {
                    contentLength = h.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            val body = if (contentLength > 0) {
                val buf = CharArray(contentLength.coerceAtMost(65536))
                var readTotal = 0
                while (readTotal < buf.size) {
                    val r = reader.read(buf, readTotal, buf.size - readTotal)
                    if (r == -1) break
                    readTotal += r
                }
                String(buf, 0, readTotal)
            } else {
                ""
            }

            if (method == "OPTIONS") {
                writeJsonResponse(out, 204, "")
                return
            }

            when (path) {
                "/events" -> handleSseClient(socket, out)
                "/state" -> {
                    writeJsonResponse(out, 200, engine.getSnapshot().toString())
                }
                "/pair" -> {
                    val qParams = parseQuery(queryStr)
                    var pin = qParams["pairingpin"] ?: ""
                    var apiUrl = qParams["apiurl"] ?: ""
                    if (body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            if (json.has("pairingpin")) pin = json.optString("pairingpin", pin)
                            if (json.has("apiurl")) apiUrl = json.optString("apiurl", apiUrl)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        val res = engine.pair(pin, apiUrl.ifEmpty { null })
                        writeJsonResponse(out, 200, res.toString())
                    } catch (e: Exception) {
                        writeJsonResponse(out, 400, JSONObject().put("success", false).put("error", e.message).toString())
                    }
                }
                "/unpair" -> {
                    engine.unpair()
                    writeJsonResponse(out, 200, JSONObject().put("success", true).put("paired", false).toString())
                }
                "/config" -> {
                    val qParams = parseQuery(queryStr)
                    var apiUrl = qParams["apiurl"] ?: ""
                    var printerIp = qParams["printerip"] ?: ""
                    if (body.isNotEmpty()) {
                        try {
                            val json = JSONObject(body)
                            if (json.has("apiurl")) apiUrl = json.optString("apiurl", apiUrl)
                            if (json.has("printerip")) printerIp = json.optString("printerip", printerIp)
                        } catch (_: Exception) {
                        }
                    }
                    try {
                        if (apiUrl.isNotEmpty()) engine.setApiUrl(apiUrl)
                        if (printerIp.isNotEmpty()) engine.setPrinterIp(printerIp)
                        val snap = engine.getSnapshot()
                        writeJsonResponse(
                            out,
                            200,
                            JSONObject()
                                .put("success", true)
                                .put("apiUrl", snap.optString("apiUrl"))
                                .put("printerIp", snap.optString("printerIp"))
                                .toString()
                        )
                    } catch (e: Exception) {
                        writeJsonResponse(out, 400, JSONObject().put("success", false).put("error", e.message).toString())
                    }
                }
                "/reload" -> {
                    engine.reloadFromStorage()
                    val snap = engine.getSnapshot()
                    writeJsonResponse(
                        out,
                        200,
                        JSONObject()
                            .put("success", true)
                            .put("apiUrl", snap.optString("apiUrl"))
                            .put("printerIp", snap.optString("printerIp"))
                            .put("zplPrinters", JSONArray(engine.getPrinters()))
                            .toString()
                    )
                }
                "/print" -> {
                    try {
                        val json = JSONObject(body)
                        val zpl = json.optString("zpl", "")
                        if (zpl.isEmpty()) {
                            writeJsonResponse(out, 400, JSONObject().put("error", "missing zpl").toString())
                        } else {
                            val id = "test-${System.currentTimeMillis() / 1000}"
                            engine.enqueueJob(PrintJob(id = id, zpl = zpl))
                            writeJsonResponse(out, 200, JSONObject().put("success", true).put("jobId", id).toString())
                        }
                    } catch (e: Exception) {
                        writeJsonResponse(out, 400, JSONObject().put("error", e.message).toString())
                    }
                }
                "/scan" -> {
                    val found = engine.scanPrinters()
                    val arr = JSONArray()
                    for (p in found) arr.put(p.toJson())
                    writeJsonResponse(out, 200, JSONObject().put("success", true).put("printers", arr).toString())
                }
                else -> {
                    writeJsonResponse(out, 404, JSONObject().put("error", "not found").toString())
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun handleSseClient(socket: Socket, out: OutputStream) {
        socket.soTimeout = 0
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Connection: keep-alive\r\n")
            append("Access-Control-Allow-Origin: *\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.flush()

        val q = LinkedBlockingQueue<String>(32)
        subscribers.add(q)

        try {
            val initMsg = JSONObject().apply {
                put("type", "snapshot")
                put("timestamp", isoNow())
                put("data", engine.getSnapshot())
            }.toString()
            out.write("data: $initMsg\n\n".toByteArray(Charsets.UTF_8))
            out.flush()

            while (running.get() && !socket.isClosed) {
                val msg = q.poll(15, TimeUnit.SECONDS)
                if (msg != null) {
                    out.write("data: $msg\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                } else {
                    out.write(": heartbeat\n\n".toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            }
        } catch (_: Exception) {
        } finally {
            subscribers.remove(q)
        }
    }

    private fun parseQuery(queryStr: String): Map<String, String> {
        if (queryStr.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in queryStr.split("&")) {
            val kv = pair.split("=", limit = 2)
            if (kv.size == 2) {
                map[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv[1], "UTF-8")
            }
        }
        return map
    }

    private fun writeJsonResponse(out: OutputStream, status: Int, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            else -> "Server Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, PUT, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization, agentversion\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
    }
}
