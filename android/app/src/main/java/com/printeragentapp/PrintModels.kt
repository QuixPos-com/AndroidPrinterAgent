package com.printeragentapp

import java.util.concurrent.CompletableFuture

data class PrintJob(
    val id: String,
    val backendJobId: Int = 0,
    val agentPrinterId: Int = 0,
    val zpl: String = "",
    val payloadFuture: CompletableFuture<String>? = null
)

data class SseEvent(
    val id: String = "",
    val event: String = "",
    val data: String = ""
)

