package com.printeragentapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

data class PrintJob(
    val id: String,
    val backendJobId: Int = 0,
    val agentPrinterId: Int = 0,
    val zpl: String = ""
)

data class SseEvent(
    val id: String = "",
    val event: String = "",
    val data: String = ""
)

class AgentEngine private constructor(private val appContext: Context) {

    interface AgentListener {
        fun onLog(line: String)
        fun onStateChanged(snapshot: JSONObject)
        fun onEvent(type: String, data: Any?)
    }

    companion object {
        @Volatile
        private var instance: AgentEngine? = null

        fun getInstance(context: Context): AgentEngine {
            return instance ?: synchronized(this) {
                instance ?: AgentEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mu = ReentrantLock()
    private val stateMu = ReentrantLock()
    private val reloadMu = ReentrantLock()

    private var cfg: AgentConfigData = AgentConfigStore.load(appContext)
    private val running = AtomicBoolean(false)
    private val streamSignal = Object()
    private val printerPollSignal = Object()

    private val jobsQueue = LinkedBlockingQueue<PrintJob>(256)
    private var seq = 0
    private var paired = cfg.token.isNotEmpty()
    private var agentId = cfg.agentId
    private var agentName = cfg.agentName
    private var backendConnected = false
    private var lastJob: JobReport? = null
    private var lastFault = ""
    private var paperStatus = ""
    private var printerReady = false
    private var lastPrinterInfo = PrinterInfo(ip = cfg.printerIp)
    private var lastPostedBody = ""
    private var lastPostedTime = 0L
    private var lastReload = 0L

    private val printersInfo = ConcurrentHashMap<String, PrinterInfo>()
    private val activeJobs = ConcurrentHashMap<Int, Boolean>()
    private val cancelledJobs = ConcurrentHashMap<Int, Long>()
    private val completedJobs = ConcurrentHashMap<Int, Long>()

    private val logs = ArrayDeque<String>(1000)
    private val listeners = CopyOnWriteArrayList<AgentListener>()

    private var monitorServer: LocalMonitorServer? = null
    private val currentStreamConn = AtomicReference<HttpURLConnection?>(null)
    private val workerReset = AtomicBoolean(false)

    init {
        BlePrinterManager.init(appContext)
    }

    fun addListener(listener: AgentListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: AgentListener) {
        listeners.remove(listener)
    }

    fun isRunning(): Boolean = running.get()

    private fun nowTimeStr(): String {
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)
        return sdf.format(Date())
    }

    private fun isoNow(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun logf(msg: String) {
        val line = "${nowTimeStr()} $msg"
        synchronized(logs) {
            if (logs.size >= 1000) {
                logs.removeFirst()
            }
            logs.addLast(line)
        }
        for (l in listeners) {
            try {
                l.onLog(line)
            } catch (_: Exception) {
            }
        }
    }

    fun getLogs(): List<String> {
        synchronized(logs) {
            return logs.toList()
        }
    }

    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
        }
    }

    private fun broadcastEvent(type: String, data: Any?) {
        monitorServer?.broadcast(type, data)
        val snap = getSnapshot()
        for (l in listeners) {
            try {
                l.onEvent(type, data)
                l.onStateChanged(snap)
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyStateChanged() {
        val snap = getSnapshot()
        for (l in listeners) {
            try {
                l.onStateChanged(snap)
            } catch (_: Exception) {
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) {
            return
        }
        mu.lock()
        cfg = AgentConfigStore.load(appContext)
        paired = cfg.token.isNotEmpty()
        agentId = cfg.agentId
        agentName = cfg.agentName
        mu.unlock()

        logf("ZebraAgent starting on Android (ApiUrl=\"${cfg.apiUrl}\", ZplPrinters=${cfg.zplPrinters}, Port=\"${cfg.port}\")")

        // 1. Start Local Monitor Server
        val portNum = cfg.port.toIntOrNull() ?: 41783
        monitorServer = LocalMonitorServer(portNum, this).also { it.start() }

        // 2. Start Printer Poller Thread
        Thread({ printerPollerLoop() }, "ZebraPrinterPoller").apply {
            isDaemon = true
            start()
        }

        // 3. Start Job Worker Thread
        Thread({ workerLoop() }, "ZebraJobWorker").apply {
            isDaemon = true
            start()
        }

        // 4. Start Upstream SSE Loop Thread
        Thread({ sseMainLoop() }, "ZebraSseLoop").apply {
            isDaemon = true
            start()
        }

        notifyStateChanged()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        logf("ZebraAgent shutting down...")
        currentStreamConn.getAndSet(null)?.disconnect()
        BlePrinterManager.disconnect()
        synchronized(streamSignal) { streamSignal.notifyAll() }
        synchronized(printerPollSignal) { printerPollSignal.notifyAll() }
        monitorServer?.stop()
        monitorServer = null
        mu.lock()
        backendConnected = false
        mu.unlock()
        notifyStateChanged()
    }

    fun getConfigSnapshot(): AgentConfigData {
        mu.lock()
        return try {
            cfg.copy(zplPrinters = ArrayList(cfg.zplPrinters))
        } finally {
            mu.unlock()
        }
    }

    fun getPrinters(): List<String> {
        mu.lock()
        return try {
            if (cfg.zplPrinters.isNotEmpty()) {
                ArrayList(cfg.zplPrinters)
            } else if (cfg.printerIp.isNotEmpty()) {
                listOf(cfg.printerIp)
            } else {
                emptyList()
            }
        } finally {
            mu.unlock()
        }
    }

    fun isPaired(): Boolean {
        mu.lock()
        return try {
            paired && cfg.token.isNotEmpty()
        } finally {
            mu.unlock()
        }
    }

    fun isBackendConnected(): Boolean {
        mu.lock()
        return try {
            backendConnected
        } finally {
            mu.unlock()
        }
    }

    fun setApiUrl(rawUrl: String) {
        val clean = AgentConfigStore.cleanApiUrl(rawUrl)
        if (clean.isEmpty()) throw IllegalArgumentException("empty apiUrl")

        var changed = false
        mu.lock()
        try {
            changed = cfg.apiUrl != clean
            cfg.apiUrl = clean
        } finally {
            mu.unlock()
        }

        AgentConfigStore.saveApiUrl(appContext, clean)
        logf("Updated ApiUrl: $clean")
        broadcastEvent("config", JSONObject().put("apiUrl", clean))

        if (changed) {
            wakeStream()
        }
    }

    fun setPrinterIp(ip: String) {
        val clean = ip.trim()
        if (clean.isNotEmpty() && !PrinterProtocol.isValidPrinterTarget(clean)) {
            throw IllegalArgumentException("invalid printer IP or target")
        }

        mu.lock()
        try {
            cfg.printerIp = clean
            if (clean.isNotEmpty() && !cfg.zplPrinters.contains(clean)) {
                val updated = ArrayList(cfg.zplPrinters)
                updated.add(0, clean)
                cfg.zplPrinters = updated
            }
        } finally {
            mu.unlock()
        }

        AgentConfigStore.savePrinterIp(appContext, clean)
        logf("Updated PrinterIp: $clean")
        broadcastEvent("config", JSONObject().put("printerIp", clean))
        reconnectPrinters()
    }

    fun saveAndReloadConfig(apiUrl: String, zplPrinters: List<String>, port: String?) {
        AgentConfigStore.saveConfig(appContext, apiUrl, zplPrinters, port)
        reloadFromStorage()
    }

    fun reloadFromStorage() {
        reloadMu.lock()
        try {
            val now = System.currentTimeMillis()
            if (now - lastReload < 150L) return
            lastReload = now

            logf("Reload signal received; reloading configuration...")
            val newCfg = AgentConfigStore.load(appContext)

            var apiChanged = false
            mu.lock()
            try {
                apiChanged = cfg.apiUrl != newCfg.apiUrl
                cfg.apiUrl = newCfg.apiUrl
                cfg.printerIp = newCfg.printerIp
                cfg.zplPrinters = newCfg.zplPrinters
                cfg.port = newCfg.port
                if (newCfg.token.isNotEmpty()) {
                    cfg.token = newCfg.token
                    paired = true
                }
                if (newCfg.agentName.isNotEmpty()) {
                    cfg.agentName = newCfg.agentName
                    agentName = newCfg.agentName
                }
            } finally {
                mu.unlock()
            }

            logf("Reloaded configuration: ApiUrl=\"${newCfg.apiUrl}\", PrinterIp=\"${newCfg.printerIp}\", ZplPrinters=${newCfg.zplPrinters}")

            if (apiChanged) {
                logf("ApiUrl changed; reconnecting backend stream...")
                wakeStream()
            }

            logf("Reconnecting to printers: ${newCfg.zplPrinters}...")
            reconnectPrinters()

            val cfgPayload = JSONObject().apply {
                put("apiUrl", newCfg.apiUrl)
                put("printerIp", newCfg.printerIp)
                put("zplPrinters", JSONArray(newCfg.zplPrinters))
            }
            broadcastEvent("config", cfgPayload)
        } finally {
            reloadMu.unlock()
        }
    }

    fun reconnectPrinters() {
        BlePrinterManager.disconnect()
        mu.lock()
        lastPrinterInfo = PrinterInfo(
            ip = cfg.printerIp,
            type = "zpl",
            status = "connecting"
        )
        printerReady = false
        lastFault = ""
        val infoSnap = lastPrinterInfo
        mu.unlock()

        broadcastEvent("printer", infoSnap.toJson())
        synchronized(printerPollSignal) {
            printerPollSignal.notifyAll()
        }
    }

    private fun wakeStream() {
        workerReset.set(true)
        currentStreamConn.getAndSet(null)?.disconnect()
        synchronized(streamSignal) {
            streamSignal.notifyAll()
        }
    }

    fun pair(pin: String, optionalApiUrl: String? = null): JSONObject {
        val cleanPin = pin.trim()
        if (cleanPin.isEmpty()) {
            throw IllegalArgumentException("missing pairingpin")
        }
        if (!optionalApiUrl.isNullOrBlank()) {
            setApiUrl(optionalApiUrl)
        }

        val cfgSnap = getConfigSnapshot()
        if (cfgSnap.apiUrl.isEmpty()) {
            throw IllegalStateException("cannot pair: ApiUrl is not configured")
        }

        val reqUrl = cfgSnap.endpoint("/pair")
        val reqBody = JSONObject().apply {
            put("pairingpin", cleanPin)
            put("clientserial", cfgSnap.clientSerial)
            put("clientmodel", cfgSnap.clientModel)
            put("clienttype", "zpl")
            put("agentversion", AgentConfigStore.AGENT_VERSION)
        }

        logf("Pairing with provided pairingpin: $cleanPin at $reqUrl...")

        val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 15000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }

        return try {
            conn.outputStream.use { os ->
                os.write(reqBody.toString().toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

            if (code != 200) {
                throw IllegalStateException("pairing failed (HTTP $code): ${respText.trim()}")
            }

            val json = JSONObject(respText)
            val token = json.optString("token", "")
            val id = json.optInt("id", 0)
            val name = json.optString("name", "android-agent")
            val companyId = json.optInt("companyId", 0)

            if (token.isEmpty()) {
                throw IllegalStateException("pairing response did not contain token")
            }

            AgentConfigStore.savePairing(appContext, token, name, id)

            mu.lock()
            cfg.token = token
            cfg.agentName = name
            cfg.agentId = id
            paired = true
            agentId = id
            agentName = name
            mu.unlock()

            wakeStream()

            val resultObj = JSONObject().apply {
                put("success", true)
                put("agentId", id)
                put("name", name)
                put("companyId", companyId)
            }
            broadcastEvent("paired", JSONObject().put("agentId", id).put("name", name))
            logf("Successfully paired: Agent \"$name\" (ID: $id)")
            resultObj
        } finally {
            conn.disconnect()
        }
    }

    fun unpair() {
        mu.lock()
        paired = false
        cfg.token = ""
        cfg.agentName = ""
        cfg.agentId = 0
        agentId = 0
        agentName = ""
        backendConnected = false
        mu.unlock()

        AgentConfigStore.savePairing(appContext, "", "", 0)
        wakeStream()

        broadcastEvent("unpaired", JSONObject().put("reason", "unauthorized"))
        logf("Agent unpaired (token cleared). Waiting for pairing PIN...")
    }

    fun scanPrinters(): List<PrinterInfo> {
        logf("Scanning for ZPL printers (BLE & TCP port 9100)...")
        val found = PrinterProtocol.scanLocalZPLPrinters()
        for (p in found) {
            printersInfo[p.ip] = p
        }
        logf("Scan complete: found ${found.size} ZPL printer(s)")
        notifyStateChanged()
        return found
    }

    fun enqueueJob(job: PrintJob) {
        reportState(job.id, "queued", "")
        if (!jobsQueue.offer(job)) {
            reportState(job.id, "error", "agent queue full")
        }
    }

    fun getSnapshot(): JSONObject {
        mu.lock()
        val cfgSnap = cfg.copy(zplPrinters = ArrayList(cfg.zplPrinters))
        val isPairedNow = paired && cfg.token.isNotEmpty()
        val idNow = agentId
        val nameNow = agentName
        val connectedNow = backendConnected
        val primaryInfo = lastPrinterInfo
        val readyNow = printerReady
        val faultNow = lastFault
        val jobNow = lastJob
        mu.unlock()

        val st = primaryInfo.status.ifEmpty { "unpolled" }
        val configuredPrinters = if (cfgSnap.zplPrinters.isNotEmpty()) {
            cfgSnap.zplPrinters
        } else if (cfgSnap.printerIp.isNotEmpty()) {
            listOf(cfgSnap.printerIp)
        } else {
            emptyList()
        }

        val printersArr = JSONArray()
        for (p in configuredPrinters) {
            val pInfo = printersInfo[p] ?: if (p == primaryInfo.ip && primaryInfo.ip.isNotEmpty()) {
                primaryInfo
            } else {
                PrinterInfo(ip = p, type = "zpl", status = "unpolled")
            }
            printersArr.put(pInfo.toJson())
        }

        return JSONObject().apply {
            put("running", running.get())
            put("agentVersion", AgentConfigStore.AGENT_VERSION)
            put("apiUrl", cfgSnap.apiUrl)
            put("port", cfgSnap.port)
            put("paired", isPairedNow)
            put("agentId", idNow)
            put("agentName", nameNow)
            put("backendConnected", connectedNow)
            put("printerIp", cfgSnap.printerIp)
            put("printerSerial", primaryInfo.serial)
            put("printerModel", primaryInfo.model)
            put("printerType", "zpl")
            put("printerReady", readyNow)
            put("printerStatus", st)
            if (primaryInfo.labelLength != null) {
                put("labelLength", primaryInfo.labelLength)
            } else {
                put("labelLength", JSONObject.NULL)
            }
            put("errors", primaryInfo.errors.toJson())
            put("fault", faultNow)
            if (jobNow != null) {
                put("lastJob", jobNow.toJson())
            }
            put("printers", printersArr)
        }
    }

    fun getNotificationSummary(): Pair<String, String> {
        val snap = getSnapshot()
        val isPairedNow = snap.optBoolean("paired", false)
        val isBackendConn = snap.optBoolean("backendConnected", false)
        val agentLabel = snap.optString("agentName", "").ifEmpty { "Nicht gekoppelt" }
        val printersArr = snap.optJSONArray("printers") ?: JSONArray()

        val title = when {
            !isPairedNow -> "QuixPos Agent • Nicht gekoppelt"
            isBackendConn -> "QuixPos Agent • Verbunden ($agentLabel)"
            else -> "QuixPos Agent • Verbindungsaufbau ($agentLabel)"
        }

        if (printersArr.length() == 0) {
            return Pair(title, "Keine Drucker konfiguriert")
        }

        var onlineCount = 0
        val parts = ArrayList<String>()
        for (i in 0 until printersArr.length()) {
            val p = printersArr.optJSONObject(i) ?: continue
            val ip = p.optString("ip", "")
            val model = p.optString("model", "")
            val status = p.optString("status", "unpolled")
            val ready = p.optBoolean("ready", false)
            if (ready || status == "online") onlineCount++
            val label = if (model.isNotEmpty()) "$model ($ip)" else ip
            parts.add("$label: ${status.uppercase()}")
        }

        val summary = "Drucker ($onlineCount/${printersArr.length()} online): ${parts.joinToString(" • ")}"
        return Pair(title, summary)
    }

    // =========================================================================
    // Upstream SSE Event Stream (replicates service/sse.go & service/main.go)
    // =========================================================================
    private fun sseMainLoop() {
        var lastId = ""
        var backoffMs = 1000L
        var everConnected = false

        while (running.get()) {
            val cfgSnap = getConfigSnapshot()
            if (cfgSnap.apiUrl.isEmpty()) {
                logf("ApiUrl not set. Waiting for ApiUrl configuration...")
                synchronized(streamSignal) {
                    try {
                        streamSignal.wait(5000)
                    } catch (_: InterruptedException) {
                    }
                }
                continue
            }

            if (!isPaired()) {
                logf("ZebraAgent waiting for pairing PIN...")
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
                logf("SSE reconnecting to $baseReqUrl...")
            } else {
                logf("SSE connecting to $baseReqUrl...")
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
                        mu.lock()
                        backendConnected = connState
                        mu.unlock()
                        broadcastEvent("connection", JSONObject().put("connected", connState))
                    }
                )
            } catch (u: UnauthorizedException) {
                logf("SSE authentication failed (${u.message}). Token invalid or revoked.")
                unpair()
                continue
            } catch (e: Exception) {
                if (running.get() && !workerReset.get()) {
                    if (!connectedOnce && !everConnected) {
                        logf("SSE initial connection failed: ${e.message} (retrying in ${backoffMs / 1000}s)")
                    } else {
                        logf("SSE disconnected: ${e.message} (reconnecting in ${backoffMs / 1000}s)")
                    }
                }
            } finally {
                mu.lock()
                backendConnected = false
                mu.unlock()
                broadcastEvent("connection", JSONObject().put("connected", false))
            }

            if (!running.get()) break
            if (workerReset.get() || !isPaired()) continue

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

    private class UnauthorizedException(msg: String) : Exception(msg)

    private fun readSseStream(
        cfgSnap: AgentConfigData,
        lastIdRef: () -> String,
        setLastId: (String) -> Unit,
        onConnected: (Boolean) -> Unit
    ) {
        val base = cfgSnap.endpoint("/events")
        val sep = if (base.contains("?")) "&" else "?"
        val fullUrl = "${base}${sep}token=${URLEncoder.encode(cfgSnap.token, "UTF-8")}&agentversion=${AgentConfigStore.AGENT_VERSION}"

        val conn = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            // 35s heartbeat timeout matching HeartbeatTimeout in service/sse.go
            readTimeout = 35_000
            setRequestProperty("Authorization", "Bearer ${cfgSnap.token}")
            setRequestProperty("agentversion", AgentConfigStore.AGENT_VERSION)
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Cache-Control", "no-cache")
            val lid = lastIdRef()
            if (lid.isNotEmpty()) {
                setRequestProperty("Last-Event-ID", lid)
            }
        }

        currentStreamConn.set(conn)
        try {
            val code = conn.responseCode
            if (code == 401) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw UnauthorizedException("HTTP 401: ${errBody.trim()}")
            }
            if (code != 200) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $code: ${errBody.trim()}")
            }

            logf("SSE connected to $base")
            onConnected(true)

            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8), 64 * 1024)
            var evId = ""
            var evType = ""
            val dataLines = ArrayList<String>()

            while (running.get() && !workerReset.get()) {
                val rawLine = reader.readLine() ?: break
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
                        // Heartbeat comment frame ": heartbeat"
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
        } finally {
            currentStreamConn.compareAndSet(conn, null)
            conn.disconnect()
        }
    }

    private fun handleSseEvent(ev: SseEvent) {
        if (ev.event == "connected") {
            logf("Backend SSE event: connected, ${ev.data}")
            try {
                val obj = JSONObject(ev.data)
                val aid = obj.optInt("agentId", 0)
                val aname = obj.optString("name", "")
                mu.lock()
                if (aid != 0) {
                    agentId = aid
                    cfg.agentId = aid
                }
                if (aname.isNotEmpty()) {
                    agentName = aname
                    cfg.agentName = aname
                }
                val tokenToSave = cfg.token
                val nameToSave = agentName
                val idToSave = agentId
                mu.unlock()
                if (nameToSave.isNotEmpty()) {
                    AgentConfigStore.savePairing(appContext, tokenToSave, nameToSave, idToSave)
                }
            } catch (_: Exception) {
            }

            Thread { sendPrinterStateUpstream() }.start()
            Thread { drainBacklog() }.start()
            notifyStateChanged()
            return
        }

        // Check cancel event
        val cancelParsed = parseCancelEvent(ev)
        if (cancelParsed != null) {
            val (model, serial, pType) = cancelParsed
            logf("Backend SSE event: cancel (model=\"$model\" serial=\"$serial\" type=\"$pType\")")
            Thread { handleCancel(model, serial, pType) }.start()
            return
        }

        // Check real-time job notifications ("job", "printerjob", or payload with "action")
        val jobEv = parseJobEvent(ev)
        if (jobEv != null) {
            val action = jobEv.optString("action", "")
            val jobId = jobEv.optInt("jobId", 0)
            val agentPrinterId = jobEv.optInt("agentprinterId", 0)
            val jobType = jobEv.optString("type", "")

            if (action.equals("cancelled", ignoreCase = true)) {
                logf("Backend SSE event: job cancelled (jobId=$jobId agentprinterId=$agentPrinterId)")
                cancelJob(jobId)
                return
            }
            if (action.equals("created", ignoreCase = true)) {
                logf("Backend SSE event: job created (jobId=$jobId agentprinterId=$agentPrinterId type=\"$jobType\")")
                handleJobCreated(jobId, agentPrinterId, "")
                return
            }
        }

        val trimmed = ev.data.trim()
        if (trimmed.isEmpty()) return

        // Parse "print <zpl>"
        val spaceIdx = trimmed.indexOf(' ')
        val cmd = if (spaceIdx != -1) trimmed.substring(0, spaceIdx) else trimmed
        val arg = if (spaceIdx != -1) trimmed.substring(spaceIdx + 1) else ""
        if (cmd.equals("print", ignoreCase = true)) {
            mu.lock()
            val id = ev.id.ifEmpty {
                seq++
                "local-${System.currentTimeMillis() / 1000}-$seq"
            }
            mu.unlock()
            enqueueJob(PrintJob(id = id, zpl = arg))
            return
        }

        logf("unknown message: ${ev.data.take(80)}")
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
        val printers = getPrinters()
        val ip = printers.firstOrNull() ?: ""
        if (ip.isEmpty()) {
            logf("Cancel ignored: no printer IP configured")
            return
        }

        mu.lock()
        var currentModel = lastPrinterInfo.model
        var currentSerial = lastPrinterInfo.serial
        mu.unlock()

        if (currentModel.isEmpty() && currentSerial.isEmpty()) {
            val info = PrinterProtocol.queryPrinterInfo(ip)
            mu.lock()
            lastPrinterInfo = info
            currentModel = info.model
            currentSerial = info.serial
            mu.unlock()
        }

        if (reqType.isNotEmpty() && !reqType.equals("zpl", ignoreCase = true)) {
            logf("Cancel ignored: type mismatch (event requested \"$reqType\", printer has \"zpl\")")
            return
        }
        if (reqSerial.isNotEmpty() && currentSerial.isNotEmpty() && !reqSerial.equals(currentSerial, ignoreCase = true)) {
            logf("Cancel ignored: serial mismatch (event requested \"$reqSerial\", printer has \"$currentSerial\")")
            return
        }
        if (reqModel.isNotEmpty() && currentModel.isNotEmpty() && !reqModel.equals(currentModel, ignoreCase = true)) {
            logf("Cancel ignored: model mismatch (event requested \"$reqModel\", printer has \"$currentModel\")")
            return
        }

        logf("Executing cancel (model=\"$reqModel\" serial=\"$reqSerial\" type=\"zpl\"): sending ~JP to printer $ip")
        try {
            PrinterProtocol.sendZPL(ip, "~JP")
            logf("Successfully sent ~JP to printer $ip")
            broadcastEvent(
                "cancel",
                JSONObject().apply {
                    put("model", reqModel)
                    put("serial", reqSerial)
                    put("type", "zpl")
                    put("status", "cancelled")
                }
            )
        } catch (e: Exception) {
            logf("Failed to send ~JP to printer $ip: ${e.message}")
        }
    }

    // =========================================================================
    // Print Job Worker & Lifecycle (replicates worker, runJob, runBackendJob)
    // =========================================================================
    private fun workerLoop() {
        while (running.get()) {
            val job = try {
                jobsQueue.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            if (job.backendJobId > 0) {
                runBackendJob(job)
            } else {
                runDirectJob(job)
            }
        }
    }

    private fun runDirectJob(j: PrintJob) {
        val ip = getPrinters().firstOrNull() ?: ""
        if (ip.isEmpty()) {
            reportState(j.id, "error", "no printer IP configured")
            return
        }
        try {
            PrinterProtocol.sendZPL(ip, j.zpl)
        } catch (e: Exception) {
            reportState(j.id, "error", "send failed: ${e.message}")
            return
        }
        reportState(j.id, "sent", "")

        var last = ""
        var failures = 0
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L
        var sawBusy = false
        var idleSince = 0L

        while (running.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(1000)
            } catch (_: InterruptedException) {
                return
            }

            val st = try {
                PrinterProtocol.queryStatus(ip)
            } catch (e: Exception) {
                failures++
                if (failures >= 5) {
                    reportState(j.id, "error", "status poll failed: ${e.message}")
                    return
                }
                continue
            }
            failures = 0

            mu.lock()
            paperStatus = if (st.paperOut) "out" else "ok"
            lastFault = st.fault()
            printerReady = true
            mu.unlock()

            val f = st.fault()
            if (f.isNotEmpty()) {
                if (last != "fault:$f") {
                    reportState(j.id, "fault", f)
                    last = "fault:$f"
                }
                continue
            }
            if (st.busy()) {
                sawBusy = true
                idleSince = 0L
                if (last != "printing") {
                    reportState(j.id, "printing", "formats=${st.formatsInBuffer} labelsRemaining=${st.labelsRemaining}")
                    last = "printing"
                }
                continue
            }
            if (idleSince == 0L) {
                idleSince = System.currentTimeMillis()
            }
            if (sawBusy || (System.currentTimeMillis() - idleSince) >= 2000L) {
                reportState(j.id, "done", "")
                return
            }
        }
        reportState(j.id, "error", "timeout waiting for printer")
    }

    private fun runBackendJob(j: PrintJob) {
        val jobId = j.backendJobId
        if (cancelledJobs.containsKey(jobId)) {
            logf("Job $jobId was cancelled before execution, discarding.")
            finishJob(jobId)
            return
        }

        var zpl = j.zpl
        if (zpl.isEmpty()) {
            zpl = try {
                fetchJobPayload(jobId)
            } catch (e: Exception) {
                logf("Failed to fetch payload for job $jobId: ${e.message}")
                patchJobFailed(jobId)
                reportState(j.id, "error", "fetch failed: ${e.message}")
                finishJob(jobId)
                return
            }
        }

        if (cancelledJobs.containsKey(jobId)) {
            logf("Job $jobId was cancelled during fetch, discarding.")
            finishJob(jobId)
            return
        }

        markJobProcessing(jobId)
        reportState(j.id, "processing", "")

        val ip = getPrinters().firstOrNull() ?: ""
        if (ip.isEmpty()) {
            logf("Failed to execute job $jobId: no printer IP configured")
            patchJobFailed(jobId)
            reportState(j.id, "error", "no printer IP configured")
            finishJob(jobId)
            return
        }

        logf("Writing print job $jobId (${zpl.length} bytes) to printer $ip...")
        try {
            PrinterProtocol.sendZPL(ip, zpl)
        } catch (e: Exception) {
            logf("Failed to execute job $jobId: ${e.message}")
            patchJobFailed(jobId)
            reportState(j.id, "error", "send failed: ${e.message}")
            finishJob(jobId)
            return
        }

        markJobCompleted(jobId)
        reportState(j.id, "completed", "")
        logf("Job $jobId successfully completed.")
        finishJob(jobId)

        try {
            Thread.sleep(300)
        } catch (_: InterruptedException) {
        }
    }

    private fun handleJobCreated(jobId: Int, agentPrinterId: Int, data: String) {
        if (jobId <= 0) return
        if (activeJobs.containsKey(jobId) || completedJobs.containsKey(jobId)) return
        if (cancelledJobs.containsKey(jobId)) {
            logf("Job $jobId was cancelled, skipping creation.")
            return
        }
        activeJobs[jobId] = true

        val job = PrintJob(
            id = jobId.toString(),
            backendJobId = jobId,
            agentPrinterId = agentPrinterId,
            zpl = data
        )
        if (!jobsQueue.offer(job)) {
            logf("Agent job queue full, cannot enqueue job $jobId")
            patchJobFailed(jobId)
            finishJob(jobId)
        }
    }

    private fun cancelJob(jobId: Int) {
        val now = System.currentTimeMillis()
        cancelledJobs[jobId] = now
        activeJobs.remove(jobId)
        cleanupJobMaps(now)
        logf("Job $jobId cancelled by user.")
        broadcastEvent("job", JSONObject().put("id", jobId.toString()).put("state", "cancelled"))
    }

    private fun finishJob(jobId: Int) {
        val now = System.currentTimeMillis()
        activeJobs.remove(jobId)
        completedJobs[jobId] = now
        cleanupJobMaps(now)
    }

    private fun cleanupJobMaps(now: Long) {
        val expiry = 10 * 60 * 1000L
        cancelledJobs.entries.removeIf { now - it.value > expiry }
        completedJobs.entries.removeIf { now - it.value > expiry }
    }

    private fun fetchJobPayload(jobId: Int): String {
        val cfgSnap = getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) throw Exception("no auth token")
        val reqUrl = cfgSnap.endpoint("jobs/$jobId")
        val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer ${cfgSnap.token}")
            setRequestProperty("agentversion", AgentConfigStore.AGENT_VERSION)
        }
        return try {
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP $code: ${err.trim()}")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            json.optJSONObject("job")?.optString("data", "") ?: ""
        } finally {
            conn.disconnect()
        }
    }

    private fun markJobProcessing(jobId: Int) {
        postJobEndpoint("jobs/$jobId/processing", "POST", null)
    }

    private fun markJobCompleted(jobId: Int) {
        postJobEndpoint("jobs/$jobId/completed", "POST", null)
    }

    private fun patchJobFailed(jobId: Int) {
        postJobEndpoint("jobs/$jobId", "PATCH", JSONObject().put("state", "failed").toString())
    }

    private fun postJobEndpoint(subpath: String, method: String, body: String?) {
        val cfgSnap = getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) return
        val reqUrl = cfgSnap.endpoint(subpath)
        val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = if (method == "PATCH") "POST" else method
            if (method == "PATCH") {
                setRequestProperty("X-HTTP-Method-Override", "PATCH")
            }
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer ${cfgSnap.token}")
            setRequestProperty("agentversion", AgentConfigStore.AGENT_VERSION)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            if (code >= 300) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                logf("$subpath HTTP $code: ${err.trim()}")
            }
        } catch (e: Exception) {
            logf("$subpath error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    private fun drainBacklog() {
        val cfgSnap = getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) return
        val reqUrl = cfgSnap.endpoint("jobs?state=pending")
        val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("Authorization", "Bearer ${cfgSnap.token}")
            setRequestProperty("agentversion", AgentConfigStore.AGENT_VERSION)
        }
        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                logf("drainBacklog HTTP $code: ${err.trim()}")
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val jobsArr = json.optJSONArray("jobs") ?: return
            if (jobsArr.length() > 0) {
                logf("Processing ${jobsArr.length()} pending jobs from backlog...")
                for (i in 0 until jobsArr.length()) {
                    val j = jobsArr.optJSONObject(i) ?: continue
                    handleJobCreated(
                        jobId = j.optInt("id", 0),
                        agentPrinterId = j.optInt("agentprinterId", 0),
                        data = j.optString("data", "")
                    )
                }
            }
        } catch (e: Exception) {
            logf("drainBacklog error: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    // =========================================================================
    // Printer Poller & Upstream State Reporting
    // =========================================================================
    private fun printerPollerLoop() {
        var lastInfo = PrinterInfo(ip = "")
        var hasPolledOnce = false

        while (running.get()) {
            try {
                checkAndReportPrinter(
                    lastInfo = lastInfo,
                    hasPolledOnce = hasPolledOnce,
                    updateTrackers = { newLast, polled ->
                        lastInfo = newLast
                        hasPolledOnce = polled
                    }
                )
            } catch (e: Exception) {
                logf("Printer poller error: ${e.message}")
            }

            synchronized(printerPollSignal) {
                try {
                    printerPollSignal.wait(5000L)
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    private fun checkAndReportPrinter(
        lastInfo: PrinterInfo,
        hasPolledOnce: Boolean,
        updateTrackers: (PrinterInfo, Boolean) -> Unit
    ) {
        val printers = getPrinters()
        if (printers.isEmpty()) return

        val primaryIp = printers[0]
        val (info, ok) = PrinterProtocol.tryQueryPrinterInfo(primaryIp)
        if (!ok) return

        mu.lock()
        lastPrinterInfo = info
        printerReady = info.ready
        printersInfo[primaryIp] = info
        mu.unlock()

        for (secIp in printers.drop(1)) {
            val (secInfo, secOk) = PrinterProtocol.tryQueryPrinterInfo(secIp)
            if (secOk) {
                val prevSec = printersInfo.put(secIp, secInfo)
                val secChanged = prevSec == null || prevSec != secInfo
                if (secChanged) {
                    broadcastEvent("printer", secInfo.toJson())
                }
            }
        }

        mu.lock()
        lastFault = when {
            info.errors.headOpen -> "head open"
            info.errors.pause -> "paused"
            info.errors.other -> "printer error"
            info.errors.unreachable -> "unreachable"
            else -> ""
        }
        mu.unlock()

        val isInitialPoll = !hasPolledOnce
        val changed = isInitialPoll || lastInfo != info

        if (changed) {
            updateTrackers(info, true)
            broadcastEvent("printer", info.toJson())

            val labelLenStr = info.labelLength?.toString() ?: "null"
            logf(
                "Printer [${info.ip}] status: ${info.status} (model=\"${info.model}\" serial=\"${info.serial}\" labelLength=$labelLenStr headOpen=${info.errors.headOpen} pause=${info.errors.pause} other=${info.errors.other} unreachable=${info.errors.unreachable})"
            )

            if (isPaired() && isBackendConnected()) {
                Thread { postStatePayload("", "", "", isInitialPoll) }.start()
            }
        }
    }

    private fun sendPrinterStateUpstream() {
        val ip = getPrinters().firstOrNull() ?: return
        mu.lock()
        val hasInfo = lastPrinterInfo.ip.isNotEmpty()
        mu.unlock()

        if (!hasInfo) {
            val info = PrinterProtocol.queryPrinterInfo(ip)
            mu.lock()
            lastPrinterInfo = info
            printerReady = info.ready
            printersInfo[ip] = info
            mu.unlock()
        }

        logf("Sending printer state upstream after SSE connection...")
        postStatePayload("", "", "", true)
    }

    private fun reportState(jobId: String, jobState: String, detail: String) {
        logf("job $jobId: $jobState $detail")
        mu.lock()
        lastJob = JobReport(id = jobId, state = jobState, detail = detail, updatedAt = isoNow())
        mu.unlock()

        broadcastEvent(
            "job",
            JSONObject().apply {
                put("jobId", jobId)
                put("state", jobState)
                put("detail", detail)
            }
        )

        Thread { postStatePayload(jobId, jobState, detail, true) }.start()
    }

    private fun postStatePayload(jobId: String, jobState: String, detail: String, force: Boolean) {
        stateMu.lock()
        try {
            mu.lock()
            val cfgSnap = cfg.copy(zplPrinters = ArrayList(cfg.zplPrinters))
            val connected = backendConnected
            val fault = lastFault
            val info = lastPrinterInfo
            val jobCopy = lastJob
            mu.unlock()

            if (cfgSnap.token.isEmpty() || !connected) return

            val printers = getPrinters()
            val printersList = JSONArray()
            for (p in printers) {
                val pInfo = printersInfo[p] ?: info
                printersList.put(
                    JSONObject().apply {
                        put("serial", pInfo.serial)
                        put("model", pInfo.model)
                        put("type", "zpl")
                        if (pInfo.labelLength != null) {
                            put("labelLength", pInfo.labelLength)
                        } else {
                            put("labelLength", JSONObject.NULL)
                        }
                        put("errors", pInfo.errors.toJson())
                    }
                )
            }
            if (printersList.length() == 0) {
                printersList.put(
                    JSONObject().apply {
                        put("serial", info.serial)
                        put("model", info.model)
                        put("type", "zpl")
                        if (info.labelLength != null) {
                            put("labelLength", info.labelLength)
                        } else {
                            put("labelLength", JSONObject.NULL)
                        }
                        put("errors", info.errors.toJson())
                    }
                )
            }

            val stateMap = JSONObject().apply {
                put("printers", printersList)
                if (fault.isNotEmpty()) {
                    put("fault", fault)
                }
                if (jobId.isNotEmpty()) {
                    put(
                        "job",
                        JSONObject().apply {
                            put("id", jobId)
                            put("state", jobState)
                            put("detail", detail)
                        }
                    )
                } else if (jobCopy != null) {
                    put(
                        "job",
                        JSONObject().apply {
                            put("id", jobCopy.id)
                            put("state", jobCopy.state)
                            put("detail", jobCopy.detail)
                        }
                    )
                }
            }

            val bodyObj = JSONObject().put("state", stateMap)
            val bodyStr = bodyObj.toString()

            mu.lock()
            val now = System.currentTimeMillis()
            if (!force && bodyStr == lastPostedBody && (now - lastPostedTime) < 3000L) {
                mu.unlock()
                return
            }
            lastPostedBody = bodyStr
            lastPostedTime = now
            mu.unlock()

            val reqUrl = cfgSnap.endpoint("/state") + "?agentversion=${AgentConfigStore.AGENT_VERSION}"
            val conn = (URL(reqUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                setRequestProperty("Authorization", "Bearer ${cfgSnap.token}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("agentversion", AgentConfigStore.AGENT_VERSION)
            }

            try {
                conn.outputStream.use { os ->
                    os.write(bodyStr.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                val code = conn.responseCode
                if (code >= 300) {
                    val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    logf("postState HTTP $code: ${errText.trim()}")
                } else {
                    val labelLenStr = info.labelLength?.toString() ?: "null"
                    logf("Posted printer state upstream: HTTP $code (model=\"${info.model}\" serial=\"${info.serial}\" type=\"zpl\" labelLength=$labelLenStr)")
                }
            } catch (e: Exception) {
                logf("postState failed: ${e.message}")
            } finally {
                conn.disconnect()
            }
        } finally {
            stateMu.unlock()
        }
    }
}
