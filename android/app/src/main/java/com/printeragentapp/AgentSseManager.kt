package com.printeragentapp

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class AgentSseManager(private val engine: AgentEngine) {

    private class UnauthorizedException(msg: String) : Exception(msg)

    private val sseHttpClient: OkHttpClient = engine.httpClient.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val streamSignal = Object()
    private val currentStreamCall = AtomicReference<Call?>(null)
    private val workerReset = AtomicBoolean(false)
    private var seq = 0

    fun wake() {
        workerReset.set(true)
        currentStreamCall.getAndSet(null)?.cancel()
        synchronized(streamSignal) {
            streamSignal.notifyAll()
        }
    }

    fun stop() {
        currentStreamCall.getAndSet(null)?.cancel()
        synchronized(streamSignal) {
            streamSignal.notifyAll()
        }
    }

    fun start() {
        Thread({ sseMainLoop() }, "ZebraSseLoop").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun sseMainLoop() {
        var lastId = ""
        var backoffMs = 1000L
        var everConnected = false

        while (engine.isRunning()) {
            val cfgSnap = engine.getConfigSnapshot()
            if (cfgSnap.apiUrl.isEmpty()) {
                engine.logf("ApiUrl not set. Waiting for ApiUrl configuration...")
                synchronized(streamSignal) {
                    try {
                        streamSignal.wait(5000)
                    } catch (_: InterruptedException) {
                    }
                }
                continue
            }

            if (!engine.isPaired()) {
                engine.logf("ZebraAgent waiting for pairing PIN...")
                synchronized(streamSignal) {
                    try {
                        streamSignal.wait(5000)
                    } catch (_: InterruptedException) {
                    }
                }
                continue
            }

            workerReset.set(false)
            val baseReqUrl = cfgSnap.endpoint("/events")
            if (everConnected) {
                engine.logf("SSE reconnecting to $baseReqUrl...")
            } else {
                engine.logf("SSE connecting to $baseReqUrl...")
            }

            val startTime = System.currentTimeMillis()
            var connectedOnce = false

            try {
                readSseStream(
                    cfgSnap = cfgSnap,
                    lastIdRef = { lastId },
                    setLastId = { lastId = it },
                    onConnected = { connState ->
                        if (connState) {
                            connectedOnce = true
                            everConnected = true
                            backoffMs = 1000L
                        }
                        engine.setBackendConnected(connState)
                        engine.broadcastEvent("connection", JSONObject().put("connected", connState))
                    }
                )
            } catch (u: UnauthorizedException) {
                engine.logf("SSE authentication failed (${u.message}). Token invalid or revoked.")
                engine.unpair()
                continue
            } catch (e: Exception) {
                if (engine.isRunning() && !workerReset.get()) {
                    if (!connectedOnce && !everConnected) {
                        engine.logf("SSE initial connection failed: ${e.message} (retrying in ${backoffMs / 1000}s)")
                    } else {
                        engine.logf("SSE disconnected: ${e.message} (reconnecting in ${backoffMs / 1000}s)")
                    }
                }
            } finally {
                engine.setBackendConnected(false)
                engine.broadcastEvent("connection", JSONObject().put("connected", false))
            }

            if (!engine.isRunning()) break
            if (workerReset.get() || !engine.isPaired()) continue

            if (connectedOnce && (System.currentTimeMillis() - startTime) > 35_000L) {
                backoffMs = 1000L
            }

            synchronized(streamSignal) {
                try {
                    streamSignal.wait(backoffMs)
                } catch (_: InterruptedException) {
                }
            }
            if (backoffMs < 30_000L) {
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun readSseStream(
        cfgSnap: AgentConfigData,
        lastIdRef: () -> String,
        setLastId: (String) -> Unit,
        onConnected: (Boolean) -> Unit
    ) {
        val base = cfgSnap.endpoint("/events")
        val sep = if (base.contains("?")) "&" else "?"
        val fullUrl = "${base}${sep}token=${URLEncoder.encode(cfgSnap.token, "UTF-8")}&agentversion=${AgentConfigStore.AGENT_VERSION}"

        val reqBuilder = Request.Builder()
            .url(fullUrl)
            .get()
            .header("Authorization", "Bearer ${cfgSnap.token}")
            .header("agentversion", AgentConfigStore.AGENT_VERSION)
            .header("Accept", "text/event-stream")
            .header("Cache-Control", "no-cache")

        val lid = lastIdRef()
        if (lid.isNotEmpty()) {
            reqBuilder.header("Last-Event-ID", lid)
        }

        val call = sseHttpClient.newCall(reqBuilder.build())
        currentStreamCall.set(call)
        try {
            call.execute().use { resp ->
                val code = resp.code
                if (code == 401) {
                    val errBody = resp.body?.string() ?: ""
                    throw UnauthorizedException("HTTP 401: ${errBody.trim()}")
                }
                if (code != 200) {
                    val errBody = resp.body?.string() ?: ""
                    throw Exception("HTTP $code: ${errBody.trim()}")
                }

                val body = resp.body ?: throw Exception("empty SSE response body")
                engine.logf("SSE connected to $base")
                onConnected(true)

                val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8), 1024)
                var evId = ""
                var evType = ""
                val dataLines = ArrayList<String>()

                while (engine.isRunning() && !workerReset.get()) {
                    val rawLine = reader.readLine()
                    if (rawLine == null) {
                        engine.logf("SSE stream closed by server (EOF).")
                        break
                    }
                    val line = rawLine.trimEnd('\r', '\n')

                    when {
                        line.isEmpty() -> {
                            if (dataLines.isNotEmpty() || evType.isNotEmpty()) {
                                val dataStr = dataLines.joinToString("\n")
                                if (evId.isNotEmpty()) {
                                    setLastId(evId)
                                }
                                handleSseEvent(SseEvent(id = evId, event = evType, data = dataStr))
                            }
                            evId = ""
                            evType = ""
                            dataLines.clear()
                        }
                        line.startsWith(":") -> {
                            // Heartbeat frame comment ": heartbeat"
                        }
                        else -> {
                            val colonIdx = line.indexOf(':')
                            val field = if (colonIdx != -1) line.substring(0, colonIdx) else line
                            var value = if (colonIdx != -1) line.substring(colonIdx + 1) else ""
                            if (value.startsWith(" ")) {
                                value = value.substring(1)
                            }
                            when (field) {
                                "data" -> dataLines.add(value)
                                "id" -> evId = value
                                "event" -> evType = value
                            }
                        }
                    }
                }
            }
        } finally {
            currentStreamCall.compareAndSet(call, null)
            call.cancel()
        }
    }

    private fun handleSseEvent(ev: SseEvent) {
        if (ev.event == "connected") {
            engine.logf("Backend SSE event: connected, ${ev.data}")
            try {
                val obj = JSONObject(ev.data)
                val aid = obj.optInt("agentId", 0)
                val aname = obj.optString("name", "")
                engine.updateAgentIdentity(aid, aname)
            } catch (_: Exception) {
            }

            Thread { engine.sendPrinterStateUpstream() }.start()
            Thread { engine.drainBacklog() }.start()
            engine.notifyStateChanged()
            return
        }

        val cancelParsed = parseCancelEvent(ev)
        if (cancelParsed != null) {
            val (model, serial, pType) = cancelParsed
            engine.logf("Backend SSE event: cancel (model=\"$model\" serial=\"$serial\" type=\"$pType\")")
            Thread { handleCancel(model, serial, pType) }.start()
            return
        }

        val jobEv = parseJobEvent(ev)
        if (jobEv != null) {
            val action = jobEv.optString("action", "")
            val jobId = jobEv.optInt("jobId", 0)
            val agentPrinterId = jobEv.optInt("agentprinterId", 0)
            val jobType = jobEv.optString("type", "")

            if (action.equals("cancelled", ignoreCase = true)) {
                engine.logf("Backend SSE event: job cancelled (jobId=$jobId agentprinterId=$agentPrinterId)")
                engine.cancelJob(jobId)
                return
            }
            if (action.equals("created", ignoreCase = true)) {
                val inlineData = jobEv.optString("data", "").ifEmpty { jobEv.optString("zpl", "") }
                engine.logf("Backend SSE event: job created (jobId=$jobId agentprinterId=$agentPrinterId type=\"$jobType\")")
                engine.handleJobCreated(jobId, agentPrinterId, inlineData)
                return
            }
        }

        val trimmed = ev.data.trim()
        if (trimmed.isEmpty()) return

        val spaceIdx = trimmed.indexOf(' ')
        val cmd = if (spaceIdx != -1) trimmed.substring(0, spaceIdx) else trimmed
        val arg = if (spaceIdx != -1) trimmed.substring(spaceIdx + 1) else ""
        if (cmd.equals("print", ignoreCase = true)) {
            val id = ev.id.ifEmpty {
                seq++
                "local-${System.currentTimeMillis() / 1000}-$seq"
            }
            engine.enqueueJob(PrintJob(id = id, zpl = arg))
            return
        }

        engine.logf("unknown message: ${ev.data.take(80)}")
    }

    private fun parseJobEvent(ev: SseEvent): JSONObject? {
        val isJobEvent = ev.event.equals("job", ignoreCase = true) || ev.event.equals("printerjob", ignoreCase = true)
        val trimmed = ev.data.trim()
        if (!isJobEvent && !trimmed.contains("\"action\"")) {
            return null
        }
        return try {
            val obj = JSONObject(trimmed)
            if (obj.optString("action", "").isNotEmpty() && obj.optInt("jobId", 0) > 0) obj else null
        } catch (_: Exception) {
            null
        }
    }

    private fun parseCancelEvent(ev: SseEvent): Triple<String, String, String>? {
        val isCancelEvent = ev.event.equals("cancel", ignoreCase = true)
        val trimmed = ev.data.trim()
        if (!isCancelEvent && !trimmed.lowercase().startsWith("cancel")) {
            return null
        }
        var payload = trimmed
        if (payload.lowercase().startsWith("cancel")) {
            payload = payload.substring(6).trim()
        }
        if (payload.isEmpty()) {
            return Triple("", "", "")
        }

        try {
            val obj = JSONObject(payload)
            val m = obj.optString("model").ifEmpty { obj.optString("clientmodel").ifEmpty { obj.optString("printerModel") } }
            val s = obj.optString("serial").ifEmpty { obj.optString("clientserial").ifEmpty { obj.optString("printerSerial") } }
            val t = obj.optString("type").ifEmpty { obj.optString("clienttype").ifEmpty { obj.optString("printerType") } }
            return Triple(m, s, t)
        } catch (_: Exception) {
        }

        if (payload.contains("=")) {
            val map = HashMap<String, String>()
            for (pair in payload.split("&")) {
                val kv = pair.split("=", limit = 2)
                if (kv.size == 2) {
                    map[URLDecoder.decode(kv[0], "UTF-8")] = URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            if (map.containsKey("model") || map.containsKey("serial") || map.containsKey("type")) {
                return Triple(map["model"] ?: "", map["serial"] ?: "", map["type"] ?: "")
            }
        }

        val parts = payload.split(Regex("\\s+"))
        return when {
            parts.size >= 3 -> Triple(parts[0], parts[1], parts[2])
            parts.size == 2 -> Triple(parts[0], parts[1], "")
            parts.size == 1 -> Triple("", parts[0], "")
            else -> Triple("", "", "")
        }
    }

    private fun handleCancel(reqModel: String, reqSerial: String, reqType: String) {
        val printers = engine.getPrinters()
        val ip = printers.firstOrNull() ?: ""
        if (ip.isEmpty()) {
            engine.logf("Cancel ignored: no printer IP configured")
            return
        }

        var primaryInfo = engine.getPrimaryPrinterInfo()
        var currentModel = primaryInfo.model
        var currentSerial = primaryInfo.serial

        if (currentModel.isEmpty() && currentSerial.isEmpty()) {
            val info = PrinterProtocol.queryPrinterInfo(ip)
            engine.updatePrimaryPrinterInfo(info)
            currentModel = info.model
            currentSerial = info.serial
        }

        if (reqType.isNotEmpty() && !reqType.equals("zpl", ignoreCase = true)) {
            engine.logf("Cancel ignored: type mismatch (event requested \"$reqType\", printer has \"zpl\")")
            return
        }
        if (reqSerial.isNotEmpty() && currentSerial.isNotEmpty() && !reqSerial.equals(currentSerial, ignoreCase = true)) {
            engine.logf("Cancel ignored: serial mismatch (event requested \"$reqSerial\", printer has \"$currentSerial\")")
            return
        }
        if (reqModel.isNotEmpty() && currentModel.isNotEmpty() && !reqModel.equals(currentModel, ignoreCase = true)) {
            engine.logf("Cancel ignored: model mismatch (event requested \"$reqModel\", printer has \"$currentModel\")")
            return
        }

        engine.logf("Executing cancel (model=\"$reqModel\" serial=\"$reqSerial\" type=\"zpl\"): sending ~JP to printer $ip")
        try {
            val startTime = System.currentTimeMillis()
            PrinterProtocol.sendZPL(ip, "~JP")
            val elapsed = System.currentTimeMillis() - startTime
            engine.logf("Successfully sent ~JP to printer $ip in ${elapsed}ms")
            engine.broadcastEvent(
                "cancel",
                JSONObject().apply {
                    put("model", reqModel)
                    put("serial", reqSerial)
                    put("type", "zpl")
                    put("status", "cancelled")
                }
            )
        } catch (e: Exception) {
            engine.logf("Failed to send ~JP to printer $ip: ${e.message}")
        }
    }
}

