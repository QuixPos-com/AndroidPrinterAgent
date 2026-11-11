package com.printeragentapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

class AgentEngine private constructor(internal val appContext: Context) {

    interface AgentListener {
        fun onLog(line: String)
        fun onStateChanged(snapshot: JSONObject)
        fun onEvent(type: String, data: Any?)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        @Volatile
        private var instance: AgentEngine? = null

        fun getInstance(context: Context): AgentEngine {
            return instance ?: synchronized(this) {
                instance ?: AgentEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    private val mu = ReentrantLock()
    private val reloadMu = ReentrantLock()

    private val sharedConnectionPool = ConnectionPool(8, 5, TimeUnit.MINUTES)
    internal val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectionPool(sharedConnectionPool)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jobWorker = AgentJobWorker(this)
    private val sseManager = AgentSseManager(this)
    private val printerPoller = AgentPrinterPoller(this)

    private var cfg: AgentConfigData = AgentConfigStore.load(appContext)
    private val running = AtomicBoolean(false)
    private val lastPrintActivityMs = AtomicLong(0L)
    private val isPrinting = AtomicBoolean(false)

    private var paired = cfg.token.isNotEmpty()
    private var agentId = cfg.agentId
    private var agentName = cfg.agentName
    private var backendConnected = false
    private var lastJob: JobReport? = null
    private var lastFault = ""
    private var printerReady = false
    private var lastPrinterInfo = PrinterInfo(ip = cfg.printerIp)
    private var lastReload = 0L

    private val printersInfo = ConcurrentHashMap<String, PrinterInfo>()
    private val logs = ArrayDeque<String>(1000)
    private val listeners = CopyOnWriteArrayList<AgentListener>()

    private var monitorServer: LocalMonitorServer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

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
        println(line)
        android.util.Log.i("ZebraAgent", msg)
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

    internal fun broadcastEvent(type: String, data: Any?) {
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

    internal fun notifyStateChanged() {
        val snap = getSnapshot()
        for (l in listeners) {
            try {
                l.onStateChanged(snap)
            } catch (_: Exception) {
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            if (networkCallback != null) return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (running.get() && isPaired() && !isBackendConnected()) {
                        sseManager.wake()
                    }
                }
            }
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (_: Exception) {
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            val cb = networkCallback ?: return
            networkCallback = null
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
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

        registerNetworkCallback()

        val portNum = cfg.port.toIntOrNull() ?: 41783
        monitorServer = LocalMonitorServer(portNum, this).also { it.start() }

        printerPoller.start()
        jobWorker.start()
        sseManager.start()

        notifyStateChanged()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) {
            return
        }
        logf("ZebraAgent shutting down...")
        unregisterNetworkCallback()
        sseManager.stop()
        BlePrinterManager.disconnect()
        printerPoller.wake()
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

    internal fun setBackendConnected(connected: Boolean) {
        mu.lock()
        backendConnected = connected
        mu.unlock()
    }

    fun isPrinting(): Boolean {
        if (isPrinting.get()) return true
        if (jobWorker.hasPendingOrActiveJobs()) return true
        if (PrinterProtocol.isPrinting() || PrinterProtocol.isPrintPending()) return true
        if (System.currentTimeMillis() - lastPrintActivityMs.get() < 5000L) return true
        return false
    }

    internal fun setPrinting(printing: Boolean) {
        isPrinting.set(printing)
    }

    internal fun markPrintActivity() {
        lastPrintActivityMs.set(System.currentTimeMillis())
    }

    internal fun getLastPrintActivityMs(): Long = lastPrintActivityMs.get()

    internal fun hasPendingOrActiveJobs(): Boolean = jobWorker.hasPendingOrActiveJobs()

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
            sseManager.wake()
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
                sseManager.wake()
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
        PrinterProtocol.clearCache()
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
        printerPoller.wake()
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

        val request = Request.Builder()
            .url(reqUrl)
            .post(reqBody.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(request).execute().use { resp ->
            val code = resp.code
            val respText = resp.body?.string() ?: ""

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

            sseManager.wake()

            val resultObj = JSONObject().apply {
                put("success", true)
                put("agentId", id)
                put("name", name)
                put("companyId", companyId)
            }
            broadcastEvent("paired", JSONObject().put("agentId", id).put("name", name))
            logf("Successfully paired: Agent \"$name\" (ID: $id)")
            return resultObj
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
        sseManager.wake()

        broadcastEvent("unpaired", JSONObject().put("reason", "unauthorized"))
        logf("Agent unpaired (token cleared). Waiting for pairing PIN...")
    }

    fun scanPrinters(): List<PrinterInfo> {
        logf("Scanning for printers (Zebra ZPL, Epson ESC/POS & BLE)...")
        val found = PrinterProtocol.scanLocalPrinters()
        for (p in found) {
            printersInfo[p.ip] = p
        }
        logf("Scan complete: found ${found.size} printer(s)")
        notifyStateChanged()
        return found
    }

    fun enqueueJob(job: PrintJob) {
        jobWorker.enqueueJob(job)
    }

    internal fun handleJobCreated(jobId: Int, agentPrinterId: Int, data: String) {
        jobWorker.handleJobCreated(jobId, agentPrinterId, data)
    }

    internal fun cancelJob(jobId: Int) {
        jobWorker.cancelJob(jobId)
    }

    internal fun drainBacklog() {
        jobWorker.drainBacklog()
    }

    internal fun sendPrinterStateUpstream() {
        printerPoller.sendPrinterStateUpstream()
    }

    internal fun updateAgentIdentity(aid: Int, aname: String) {
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
    }

    internal fun getPrimaryPrinterInfo(): PrinterInfo {
        mu.lock()
        return try {
            lastPrinterInfo
        } finally {
            mu.unlock()
        }
    }

    internal fun updatePrimaryPrinterInfo(info: PrinterInfo) {
        mu.lock()
        lastPrinterInfo = info
        printerReady = info.ready
        printersInfo[info.ip] = info
        mu.unlock()
    }

    internal fun updateSecondaryPrinterInfo(ip: String, info: PrinterInfo): Boolean {
        val prev = printersInfo.put(ip, info)
        return prev == null || prev != info
    }

    internal fun getPrinterInfo(ip: String): PrinterInfo? {
        return printersInfo[ip]
    }

    internal fun setLastFault(fault: String) {
        mu.lock()
        lastFault = fault
        mu.unlock()
    }

    internal fun getLastFault(): String {
        mu.lock()
        return try {
            lastFault
        } finally {
            mu.unlock()
        }
    }

    internal fun getLastJob(): JobReport? {
        mu.lock()
        return try {
            lastJob
        } finally {
            mu.unlock()
        }
    }

    internal fun reportJobState(jobId: String, jobState: String, detail: String) {
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

        Thread { printerPoller.postStatePayload(jobId, jobState, detail, true) }.start()
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

        val summary = parts.joinToString("\n")
        return Pair(title, summary)
    }
}
