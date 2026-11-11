package com.printeragentapp

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

internal class AgentJobWorker(private val engine: AgentEngine) {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }

    private val jobsQueue = LinkedBlockingQueue<PrintJob>(256)
    private val activeJobs = ConcurrentHashMap<Int, Boolean>()
    private val cancelledJobs = ConcurrentHashMap<Int, Long>()
    private val completedJobs = ConcurrentHashMap<Int, Long>()

    private val prefetchExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ZebraJobPrefetch").apply { isDaemon = true }
    }
    private val jobStatusExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ZebraJobStatus").apply { isDaemon = true }
    }

    fun hasPendingOrActiveJobs(): Boolean {
        return jobsQueue.isNotEmpty() || activeJobs.isNotEmpty()
    }

    fun enqueueJob(job: PrintJob) {
        engine.reportJobState(job.id, "queued", "")
        if (!jobsQueue.offer(job)) {
            engine.reportJobState(job.id, "error", "agent queue full")
        }
    }

    fun start() {
        Thread({ workerLoop() }, "ZebraJobWorker").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun workerLoop() {
        while (engine.isRunning()) {
            val job = try {
                jobsQueue.poll(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                null
            } ?: continue

            engine.setPrinting(true)
            PrinterProtocol.setPrinting(true)
            engine.markPrintActivity()
            try {
                if (job.backendJobId > 0) {
                    runBackendJob(job)
                } else {
                    runDirectJob(job)
                }
            } finally {
                engine.markPrintActivity()
                engine.setPrinting(false)
                PrinterProtocol.setPrinting(false)
            }
        }
    }

    private fun runDirectJob(j: PrintJob) {
        val ip = engine.getPrinters().firstOrNull() ?: ""
        if (ip.isEmpty()) {
            engine.reportJobState(j.id, "error", "no printer IP configured")
            return
        }
        engine.logf("Writing print job ${j.id} (${j.zpl.length} bytes) to printer $ip...")
        val startTime = System.currentTimeMillis()
        try {
            PrinterProtocol.sendZPL(ip, j.zpl)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            engine.logf("Failed to send job ${j.id} to printer $ip after ${elapsed}ms: ${e.message}")
            engine.reportJobState(j.id, "error", "send failed: ${e.message}")
            return
        }
        val elapsed = System.currentTimeMillis() - startTime
        engine.logf("Time spent sending job ${j.id} to printer $ip: ${elapsed}ms")
        engine.reportJobState(j.id, "sent", "")

        try {
            Thread.sleep(2000L)
        } catch (_: InterruptedException) {
            return
        }
        engine.reportJobState(j.id, "done", "")
    }

    private fun runBackendJob(j: PrintJob) {
        val jobId = j.backendJobId
        if (cancelledJobs.containsKey(jobId)) {
            engine.logf("Job $jobId was cancelled before execution, discarding.")
            finishJob(jobId)
            return
        }

        BlePrinterManager.abortActiveQuery()

        var zpl = j.zpl
        if (zpl.isEmpty()) {
            zpl = try {
                val prefetched = j.payloadFuture
                if (prefetched != null) {
                    try {
                        prefetched.get(10, TimeUnit.SECONDS)
                    } catch (e: Exception) {
                        throw (e.cause ?: e)
                    }
                } else {
                    fetchJobPayload(jobId)
                }
            } catch (e: Throwable) {
                engine.logf("Failed to fetch payload for job $jobId: ${e.message}")
                jobStatusExecutor.execute { patchJobFailed(jobId) }
                engine.reportJobState(j.id, "error", "fetch failed: ${e.message}")
                finishJob(jobId)
                return
            }
        }

        if (cancelledJobs.containsKey(jobId)) {
            engine.logf("Job $jobId was cancelled during fetch, discarding.")
            finishJob(jobId)
            return
        }

        val ip = engine.getPrinters().firstOrNull() ?: ""
        if (ip.isEmpty()) {
            engine.logf("Failed to execute job $jobId: no printer IP configured")
            jobStatusExecutor.execute { patchJobFailed(jobId) }
            engine.reportJobState(j.id, "error", "no printer IP configured")
            finishJob(jobId)
            return
        }

        jobStatusExecutor.execute { markJobProcessing(jobId) }
        engine.reportJobState(j.id, "processing", "")

        engine.logf("Writing print job $jobId (${zpl.length} bytes) to printer $ip...")
        val startTime = System.currentTimeMillis()
        try {
            PrinterProtocol.sendZPL(ip, zpl)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            engine.logf("Failed to send job $jobId to printer $ip after ${elapsed}ms: ${e.message}")
            jobStatusExecutor.execute { patchJobFailed(jobId) }
            engine.reportJobState(j.id, "error", "send failed: ${e.message}")
            finishJob(jobId)
            return
        }
        val elapsed = System.currentTimeMillis() - startTime
        engine.logf("Time spent sending job $jobId to printer $ip: ${elapsed}ms")

        jobStatusExecutor.execute { markJobCompleted(jobId) }
        engine.reportJobState(j.id, "completed", "")
        engine.logf("Job $jobId successfully completed.")
        finishJob(jobId)
    }

    fun handleJobCreated(jobId: Int, agentPrinterId: Int, data: String) {
        if (jobId <= 0) return
        if (activeJobs.containsKey(jobId) || completedJobs.containsKey(jobId)) return
        if (cancelledJobs.containsKey(jobId)) {
            engine.logf("Job $jobId was cancelled, skipping creation.")
            return
        }
        activeJobs[jobId] = true
        engine.markPrintActivity()
        BlePrinterManager.abortActiveQuery()

        val future = if (data.isEmpty()) {
            CompletableFuture.supplyAsync({ fetchJobPayload(jobId) }, prefetchExecutor)
        } else {
            null
        }

        val job = PrintJob(
            id = jobId.toString(),
            backendJobId = jobId,
            agentPrinterId = agentPrinterId,
            zpl = data,
            payloadFuture = future
        )
        if (!jobsQueue.offer(job)) {
            engine.logf("Agent job queue full, cannot enqueue job $jobId")
            future?.cancel(true)
            jobStatusExecutor.execute { patchJobFailed(jobId) }
            finishJob(jobId)
        }
    }

    fun cancelJob(jobId: Int) {
        val now = System.currentTimeMillis()
        cancelledJobs[jobId] = now
        activeJobs.remove(jobId)
        cleanupJobMaps(now)
        engine.logf("Job $jobId cancelled by user.")
        engine.broadcastEvent("job", JSONObject().put("id", jobId.toString()).put("state", "cancelled"))
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
        val cfgSnap = engine.getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) throw Exception("no auth token")
        val reqUrl = cfgSnap.endpoint("jobs/$jobId")
        val request = Request.Builder()
            .url(reqUrl)
            .get()
            .header("Authorization", "Bearer ${cfgSnap.token}")
            .header("agentversion", AgentConfigStore.AGENT_VERSION)
            .build()

        engine.httpClient.newCall(request).execute().use { resp ->
            val code = resp.code
            val text = resp.body?.string() ?: ""
            if (code != 200) {
                throw Exception("HTTP $code: ${text.trim()}")
            }
            val json = JSONObject(text)
            return json.optJSONObject("job")?.optString("data", "") ?: ""
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
        val cfgSnap = engine.getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) return
        val reqUrl = cfgSnap.endpoint(subpath)
        val reqBody = body?.toRequestBody(JSON_MEDIA_TYPE) ?: EMPTY_BODY
        val reqBuilder = Request.Builder()
            .url(reqUrl)
            .header("Authorization", "Bearer ${cfgSnap.token}")
            .header("agentversion", AgentConfigStore.AGENT_VERSION)

        if (method == "PATCH") {
            reqBuilder.patch(reqBody)
        } else {
            reqBuilder.method(method, reqBody)
        }

        try {
            engine.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                if (code >= 300) {
                    val err = resp.body?.string() ?: ""
                    engine.logf("$subpath HTTP $code: ${err.trim()}")
                }
            }
        } catch (e: Exception) {
            engine.logf("$subpath error: ${e.message}")
        }
    }

    fun drainBacklog() {
        val cfgSnap = engine.getConfigSnapshot()
        if (cfgSnap.token.isEmpty()) return
        val reqUrl = cfgSnap.endpoint("jobs?state=pending")
        val request = Request.Builder()
            .url(reqUrl)
            .get()
            .header("Authorization", "Bearer ${cfgSnap.token}")
            .header("agentversion", AgentConfigStore.AGENT_VERSION)
            .build()

        try {
            engine.httpClient.newCall(request).execute().use { resp ->
                val code = resp.code
                val body = resp.body?.string() ?: ""
                if (code != 200) {
                    engine.logf("drainBacklog HTTP $code: ${body.trim()}")
                    return
                }
                val json = JSONObject(body)
                val jobsArr = json.optJSONArray("jobs") ?: return
                if (jobsArr.length() > 0) {
                    engine.logf("Processing ${jobsArr.length()} pending jobs from backlog...")
                    for (i in 0 until jobsArr.length()) {
                        val j = jobsArr.optJSONObject(i) ?: continue
                        handleJobCreated(
                            jobId = j.optInt("id", 0),
                            agentPrinterId = j.optInt("agentprinterId", 0),
                            data = j.optString("data", "")
                        )
                    }
                }
            }
        } catch (e: Exception) {
            engine.logf("drainBacklog error: ${e.message}")
        }
    }
}

