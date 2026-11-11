package com.printeragentapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.locks.ReentrantLock

internal class AgentPrinterPoller(private val engine: AgentEngine) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val stateMu = ReentrantLock()
    private val pollSignal = Object()

    @Volatile
    private var lastPostedBody = ""
    @Volatile
    private var lastPostedTime = 0L

    fun wake() {
        synchronized(pollSignal) {
            pollSignal.notifyAll()
        }
    }

    fun start() {
        Thread({ printerPollerLoop() }, "ZebraPrinterPoller").apply {
            isDaemon = true
            start()
        }
    }

    private fun printerPollerLoop() {
        var lastInfo = PrinterInfo(ip = "")
        var hasPolledOnce = false
        var wasPrinting = false

        while (engine.isRunning()) {
            try {
                if (engine.isPrinting()) {
                    if (!wasPrinting) {
                        wasPrinting = true
                        engine.logf("Printer is printing; status polling paused.")
                    }
                } else {
                    if (wasPrinting) {
                        wasPrinting = false
                        engine.logf("Printing finished; resuming status polling.")
                    }
                    checkAndReportPrinter(
                        lastInfo = lastInfo,
                        hasPolledOnce = hasPolledOnce,
                        updateTrackers = { newLast, polled ->
                            lastInfo = newLast
                            hasPolledOnce = polled
                        }
                    )
                }
            } catch (e: Exception) {
                engine.logf("Printer poller error: ${e.message}")
            }

            synchronized(pollSignal) {
                try {
                    val waitMs = if (engine.isPrinting()) 2000L else 5000L
                    pollSignal.wait(waitMs)
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
        if (engine.isPrinting()) return
        if (engine.hasPendingOrActiveJobs() || PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) return
        if (System.currentTimeMillis() - engine.getLastPrintActivityMs() < 5000L) return

        val printers = engine.getPrinters()
        if (printers.isEmpty()) return

        val primaryIp = printers[0]
        val (info, ok) = PrinterProtocol.tryQueryPrinterInfo(primaryIp)
        if (!ok) return

        engine.updatePrimaryPrinterInfo(info)

        for (secIp in printers.drop(1)) {
            if (engine.isPrinting()) break
            if (engine.hasPendingOrActiveJobs() || PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) break
            val (secInfo, secOk) = PrinterProtocol.tryQueryPrinterInfo(secIp)
            if (secOk) {
                val secChanged = engine.updateSecondaryPrinterInfo(secIp, secInfo)
                if (secChanged) {
                    engine.broadcastEvent("printer", secInfo.toJson())
                }
            }
        }

        val fault = when {
            info.errors.headOpen -> "head open"
            info.errors.pause -> "paused"
            info.errors.other -> "printer error"
            info.errors.unreachable -> "unreachable"
            else -> ""
        }
        engine.setLastFault(fault)

        val isInitialPoll = !hasPolledOnce
        val changed = isInitialPoll || lastInfo != info

        if (changed) {
            updateTrackers(info, true)
            engine.broadcastEvent("printer", info.toJson())

            val labelLenStr = info.labelLength?.toString() ?: "null"
            engine.logf(
                "Printer [${info.ip}] status: ${info.status} (model=\"${info.model}\" serial=\"${info.serial}\" labelLength=$labelLenStr headOpen=${info.errors.headOpen} pause=${info.errors.pause} other=${info.errors.other} unreachable=${info.errors.unreachable})"
            )

            if (engine.isPaired() && engine.isBackendConnected()) {
                Thread { postStatePayload("", "", "", isInitialPoll) }.start()
            }
        }
    }

    fun sendPrinterStateUpstream() {
        val ip = engine.getPrinters().firstOrNull() ?: return
        val currentInfo = engine.getPrimaryPrinterInfo()

        if (currentInfo.ip.isEmpty()) {
            val info = PrinterProtocol.queryPrinterInfo(ip)
            engine.updatePrimaryPrinterInfo(info)
        }

        engine.logf("Sending printer state upstream after SSE connection...")
        postStatePayload("", "", "", true)
    }

    fun postStatePayload(jobId: String, jobState: String, detail: String, force: Boolean) {
        stateMu.lock()
        try {
            val cfgSnap = engine.getConfigSnapshot()
            val connected = engine.isBackendConnected()
            val fault = engine.getLastFault()
            val info = engine.getPrimaryPrinterInfo()
            val jobCopy = engine.getLastJob()

            if (cfgSnap.token.isEmpty() || !connected) return

            val printers = engine.getPrinters()
            val printersList = JSONArray()
            for (p in printers) {
                val pInfo = engine.getPrinterInfo(p) ?: info
                printersList.put(
                    JSONObject().apply {
                        put("serial", pInfo.serial)
                        put("model", pInfo.model)
                        put("type", pInfo.type.ifEmpty { "zpl" })
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
                        put("type", info.type.ifEmpty { "zpl" })
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

            val now = System.currentTimeMillis()
            if (!force && bodyStr == lastPostedBody && (now - lastPostedTime) < 3000L) {
                return
            }
            lastPostedBody = bodyStr
            lastPostedTime = now

            val reqUrl = cfgSnap.endpoint("/state") + "?agentversion=${AgentConfigStore.AGENT_VERSION}"
            val request = Request.Builder()
                .url(reqUrl)
                .post(bodyStr.toRequestBody(JSON_MEDIA_TYPE))
                .header("Authorization", "Bearer ${cfgSnap.token}")
                .header("agentversion", AgentConfigStore.AGENT_VERSION)
                .build()

            try {
                engine.httpClient.newCall(request).execute().use { resp ->
                    val code = resp.code
                    if (code >= 300) {
                        val errText = resp.body?.string() ?: ""
                        engine.logf("postState HTTP $code: ${errText.trim()}")
                    } else {
                        val labelLenStr = info.labelLength?.toString() ?: "null"
                        engine.logf("Posted printer state upstream: HTTP $code (model=\"${info.model}\" serial=\"${info.serial}\" type=\"zpl\" labelLength=$labelLenStr)")
                    }
                }
            } catch (e: Exception) {
                engine.logf("postState failed: ${e.message}")
            }
        } finally {
            stateMu.unlock()
        }
    }
}

