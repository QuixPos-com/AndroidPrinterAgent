package com.printeragentapp

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

data class HostStatus(
    val paperOut: Boolean = false,
    val paused: Boolean = false,
    val headUp: Boolean = false,
    val ribbonOut: Boolean = false,
    val formatsInBuffer: Int = 0,
    val labelsRemaining: Int = 0,
    val printingFormat: Boolean = false
) {
    fun busy(): Boolean = formatsInBuffer > 0 || labelsRemaining > 0 || printingFormat

    fun fault(): String = when {
        paperOut -> "paper out"
        headUp -> "head open"
        ribbonOut -> "ribbon out"
        paused -> "paused"
        else -> ""
    }
}

object PrinterProtocol {
    const val PRINTER_PORT = 9100
    private val printerLock = ReentrantLock(true)
    private val urgentPrintCount = AtomicInteger(0)
    private val isPrinting = AtomicBoolean(false)

    private data class CachedPrinterMeta(
        val model: String,
        val serial: String,
        val labelLength: Int?,
        val updatedAtMs: Long
    )

    private val cachedMeta = ConcurrentHashMap<String, CachedPrinterMeta>()

    fun isPrinting(): Boolean = isPrinting.get()

    fun setPrinting(printing: Boolean) {
        isPrinting.set(printing)
        if (printing) {
            BlePrinterManager.abortActiveQuery()
        }
    }

    fun isPrintPending(): Boolean = urgentPrintCount.get() > 0 || isPrinting.get()

    fun clearCache() {
        cachedMeta.clear()
    }

    fun isValidPrinterTarget(target: String): Boolean {
        val clean = target.trim()
        if (clean.isEmpty()) return false
        if (BlePrinterManager.isBluetoothTarget(clean)) return true
        val lower = clean.lowercase()
        if (lower == "usb" || lower.startsWith("usb:")) return true
        // IPv4 check
        val parts = clean.split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) {
            return true
        }
        // Hostname / IPv6 / MAC check
        return clean.none { it.isWhitespace() }
    }

    fun tryQueryPrinterInfo(ip: String): Pair<PrinterInfo, Boolean> {
        if (isPrinting() || isPrintPending() || !printerLock.tryLock()) {
            return Pair(PrinterInfo(ip = ip), false)
        }
        return try {
            if (isPrinting() || isPrintPending()) {
                Pair(PrinterInfo(ip = ip), false)
            } else {
                Pair(queryPrinterInfoLocked(ip), true)
            }
        } finally {
            printerLock.unlock()
        }
    }

    fun queryPrinterInfo(ip: String): PrinterInfo {
        if (isPrinting() || isPrintPending()) {
            val cached = cachedMeta[ip]
            return PrinterInfo(
                ip = ip,
                model = cached?.model ?: "",
                serial = cached?.serial ?: "",
                labelLength = cached?.labelLength,
                type = "zpl",
                status = "printing",
                ready = true
            )
        }
        printerLock.lock()
        return try {
            queryPrinterInfoLocked(ip)
        } finally {
            printerLock.unlock()
        }
    }

    private fun parseHostPort(target: String): Pair<String, Int> {
        val trimmed = target.trim()
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx > 0 && !trimmed.startsWith("ble") && !trimmed.startsWith("bt") && !trimmed.startsWith("usb")) {
            val host = trimmed.substring(0, colonIdx)
            val port = trimmed.substring(colonIdx + 1).toIntOrNull() ?: PRINTER_PORT
            return Pair(host, port)
        }
        return Pair(trimmed, PRINTER_PORT)
    }

    private fun queryPrinterInfoLocked(ip: String): PrinterInfo {
        val offlineInfo = PrinterInfo(
            ip = ip,
            type = "zpl",
            status = "offline",
            ready = false,
            errors = PrinterErrors(unreachable = true)
        )
        if (ip.isBlank()) return offlineInfo
        if (BlePrinterManager.isBluetoothTarget(ip)) {
            return BlePrinterManager.queryPrinterInfoLocked(ip)
        }

        val (host, port) = parseHostPort(ip)
        val now = System.currentTimeMillis()
        val cached = cachedMeta[ip]
        val useFastPoll = cached != null &&
            cached.model.isNotEmpty() &&
            cached.serial.isNotEmpty() &&
            (now - cached.updatedAtMs) < 60_000L

        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 1800)
                socket.soTimeout = if (useFastPoll) 300 else 500
                val out = socket.getOutputStream()
                val cmd = if (useFastPoll) {
                    "~HQES\r\n".toByteArray(Charsets.UTF_8)
                } else {
                    "^XA^HH^XZ~HI~HQES! U1 getvar \"device.unique_id\"\r\n".toByteArray(Charsets.UTF_8)
                }
                out.write(cmd)
                out.flush()

                val received = StringBuilder()
                val buf = ByteArray(4096)
                val input = socket.getInputStream()
                val deadline = System.currentTimeMillis() + if (useFastPoll) 900L else 2500L

                while (System.currentTimeMillis() < deadline && !isPrintPending()) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) {
                        received.append(String(buf, 0, n, Charsets.UTF_8))
                        val str = received.toString()
                        val hqesEnd = str.indexOf("WARNINGS:")
                        val hasCompleteHQES = hqesEnd != -1 && str.substring(hqesEnd).contains("\u0003")
                        if (useFastPoll) {
                            if (hasCompleteHQES) break
                        } else {
                            val hasCompleteSGD = hqesEnd != -1 && str.substring(hqesEnd).count { it == '"' } >= 2
                            if (str.contains("LABEL LENGTH") && hasCompleteHQES && hasCompleteSGD) {
                                break
                            }
                        }
                    } else {
                        break
                    }
                }

                val data = received.toString()
                if (data.isEmpty()) {
                    offlineInfo
                } else {
                    val parsed = parsePrinterData(ip, data)
                    val mergedModel = parsed.model.ifEmpty { cached?.model ?: "" }
                    val mergedSerial = parsed.serial.ifEmpty { cached?.serial ?: "" }
                    val mergedLabelLen = parsed.labelLength ?: cached?.labelLength
                    if (mergedModel.isNotEmpty() || mergedSerial.isNotEmpty()) {
                        cachedMeta[ip] = CachedPrinterMeta(
                            model = mergedModel,
                            serial = mergedSerial,
                            labelLength = mergedLabelLen,
                            updatedAtMs = if (useFastPoll) (cached?.updatedAtMs ?: now) else now
                        )
                    }
                    parsed.copy(
                        model = mergedModel,
                        serial = mergedSerial,
                        labelLength = mergedLabelLen
                    )
                }
            }
        } catch (_: Exception) {
            offlineInfo
        }
    }

    fun sendZPL(ip: String, zpl: String) {
        urgentPrintCount.incrementAndGet()
        BlePrinterManager.abortActiveQuery()
        printerLock.lock()
        try {
            if (BlePrinterManager.isBluetoothTarget(ip)) {
                BlePrinterManager.sendZPLLocked(ip, zpl)
                return
            }
            val (host, port) = parseHostPort(ip)
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), 4000)
                socket.soTimeout = 10000
                val out = socket.getOutputStream()
                out.write(zpl.toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } finally {
            urgentPrintCount.decrementAndGet()
            printerLock.unlock()
        }
    }

    fun parseHostStatus(data: String): HostStatus {
        val lines = data.replace("\r\n", "\n").replace("\r", "\n").split("\n")
            .map { it.trim { c -> c == '\u0002' || c == '\u0003' || c == '\r' || c == '\n' || c == ' ' } }

        var paperOut = false
        var paused = false
        var formatsInBuffer = 0
        var headUp = false
        var ribbonOut = false
        var labelsRemaining = 0
        var printingFormat = false

        // 1. Try ~HS lines (f1 with >= 5 items, f2 with >= 10 items)
        for (i in 0 until lines.size - 1) {
            val f1 = lines[i].split(",")
            val f2 = lines[i + 1].split(",")
            if (f1.size >= 5 && f2.size >= 10 && f1[0].all { it.isDigit() } && f2[0].all { it.isDigit() }) {
                paperOut = f1[1].trim() == "1"
                paused = f1[2].trim() == "1"
                formatsInBuffer = f1[4].trim().toIntOrNull() ?: 0
                headUp = f2[2].trim() == "1"
                ribbonOut = f2[3].trim() == "1"
                labelsRemaining = f2[8].trim().toIntOrNull() ?: 0
                printingFormat = f2[9].trim() == "1"
                break
            }
        }

        // 2. Supplement with ~HQES ERRORS:
        for (line in lines) {
            if (line.contains("ERRORS:")) {
                val parts = line.split(Regex("\\s+"))
                for (p in parts.drop(1)) {
                    val clean = p.trim { c -> c == '\u0002' || c == '\u0003' || c == '\r' || c == '\n' || c == ' ' }
                    val v = clean.toULongOrNull(16)
                    if (v != null && v != 0UL) {
                        if ((v and 0x04UL) != 0UL) headUp = true
                        if ((v and 0x10000UL) != 0UL) paused = true
                        if ((v and (0x04UL or 0x10000UL).inv()) != 0UL) paperOut = true
                    }
                }
            }
        }

        // 3. Supplement with SGD head.latch
        if (Regex("\"open\"", RegexOption.IGNORE_CASE).containsMatchIn(data) ||
            data.contains("head.latch: open", ignoreCase = true)
        ) {
            headUp = true
        }

        // 4. Supplement with SGD device.pause
        if (Regex("\"(yes|on)\"", RegexOption.IGNORE_CASE).containsMatchIn(data)) {
            paused = true
        }

        return HostStatus(
            paperOut = paperOut,
            paused = paused,
            headUp = headUp,
            ribbonOut = ribbonOut,
            formatsInBuffer = formatsInBuffer,
            labelsRemaining = labelsRemaining,
            printingFormat = printingFormat
        )
    }

    fun queryStatus(ip: String): HostStatus {
        if (isPrinting() || isPrintPending()) {
            return HostStatus(printingFormat = true)
        }
        printerLock.lock()
        try {
            if (BlePrinterManager.isBluetoothTarget(ip)) {
                return BlePrinterManager.queryStatusLocked(ip)
            }
            val (host, port) = parseHostPort(ip)
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5000)
                socket.soTimeout = 5000
                val out = socket.getOutputStream()
                out.write("~HS~HQES".toByteArray(Charsets.UTF_8))
                out.flush()

                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val sb = StringBuilder()
                val buf = CharArray(1024)
                val deadline = System.currentTimeMillis() + 3000L
                while (System.currentTimeMillis() < deadline) {
                    val n = try { reader.read(buf) } catch (_: Exception) { -1 }
                    if (n > 0) {
                        sb.append(buf, 0, n)
                        if (sb.toString().split(",").size >= 12) break
                    } else break
                }
                val raw = sb.toString()
                if (raw.isBlank()) throw Exception("empty status response")
                return parseHostStatus(raw)
            }
        } finally {
            printerLock.unlock()
        }
    }

    fun isValidModelName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val clean = name.trim().removePrefix("\"").removeSuffix("\"").trim()
        if (clean.length < 2 || clean.length > 50) return false
        // Must contain at least one letter and cannot be pure digits or status codes like "030"
        if (!clean.any { it.isLetter() }) return false
        if (clean.equals("unbekannt", ignoreCase = true) ||
            clean.equals("unknown", ignoreCase = true) ||
            clean.equals("Zebra BLE Printer", ignoreCase = true)
        ) {
            return false
        }
        return true
    }

    fun parsePrinterData(ip: String, data: String): PrinterInfo {
        var serial = ""
        var model = ""
        var labelLength: Int? = null
        var headOpen = false
        var pause = false
        var other = false

        val normalized = data.replace("\r\n", "\n").replace("\r", "\n")

        // 1. Parse Serial from SGD getvar "device.unique_id"
        for (chunk in normalized.split("\"")) {
            val c = chunk.trim()
            if (c.length in 6..24 && !c.contains(" ") && !c.contains("device.")) {
                serial = c
                break
            }
        }

        // 2. Parse ~HI (Host Identification) response for Model (and serial if 7-param format)
        val tokens = data.split('\u0002', '\u0003', '\r', '\n')
        for (rawToken in tokens) {
            val token = rawToken.trim()
            if (token.contains(",") && !token.contains("ERRORS:") && !token.contains("WARNINGS:")) {
                val parts = token.split(",")
                // Skip ~HS status lines where parts are numeric (e.g. 030,0,0,0941,000...)
                val isNumericStatusLine = parts.size >= 5 && parts[0].trim().all { it.isDigit() } && parts[1].trim().all { it.isDigit() }
                if (isNumericStatusLine) {
                    continue
                }

                if (parts.isNotEmpty() && model.isEmpty()) {
                    var modelCandidate = parts[0].trim().removePrefix("\"").removeSuffix("\"").trim()
                    val qIdx = modelCandidate.lastIndexOf('"')
                    if (qIdx != -1) {
                        modelCandidate = modelCandidate.substring(qIdx + 1).trim()
                    }
                    if (isValidModelName(modelCandidate)) {
                        model = modelCandidate
                    }
                }
                if (parts.size >= 7 && serial.isEmpty()) {
                    val candidate = parts[6].trim().removePrefix("+")
                    val upper = candidate.uppercase()
                    if (!upper.endsWith("KB") && !upper.endsWith("MB") && upper != "NONE" && candidate.length in 6..24) {
                        serial = candidate
                    }
                }
            }
        }

        // 2b. Parse Model from SGD getvar "device.product_name"
        if (model.isEmpty()) {
            for (chunk in normalized.split("\"")) {
                val c = chunk.trim()
                if (isValidModelName(c) && !c.contains("device.") && !c.contains("zpl.") && !c.contains("head.")) {
                    if (c.startsWith("ZD", ignoreCase = true) ||
                        c.startsWith("ZQ", ignoreCase = true) ||
                        c.startsWith("ZT", ignoreCase = true) ||
                        c.startsWith("GK", ignoreCase = true) ||
                        c.startsWith("GX", ignoreCase = true) ||
                        c.startsWith("QL", ignoreCase = true) ||
                        c.startsWith("iMZ", ignoreCase = true) ||
                        c.contains("ZTC", ignoreCase = true) ||
                        c.contains("Zebra", ignoreCase = true)
                    ) {
                        model = c
                        break
                    }
                }
            }
        }

        val lines = normalized.split("\n")

        // 3. Parse Label Length from ^HH response
        for (line in lines) {
            if (line.contains("LABEL LENGTH")) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isNotEmpty()) {
                    val n = parts[0].toIntOrNull()
                    if (n != null) {
                        labelLength = n
                        break
                    }
                }
            }
        }
        if (labelLength == null) {
            for (chunk in normalized.split("\"")) {
                val c = chunk.trim()
                val n = c.toIntOrNull()
                if (n != null && n in 1..99999) {
                    labelLength = n
                    break
                }
            }
        }

        // 4. Parse Serial Number from ^HH response if not already parsed
        if (serial.isEmpty()) {
            for (line in lines) {
                val trimmed = line.trim()
                val upper = trimmed.uppercase()
                if (upper.endsWith("SERIAL NUMBER")) {
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        serial = parts[0].removePrefix("+")
                        break
                    }
                } else if (upper.startsWith("SERIAL NUMBER")) {
                    val colonIdx = trimmed.indexOf(':')
                    if (colonIdx != -1) {
                        serial = trimmed.substring(colonIdx + 1).trim().removePrefix("+")
                        break
                    }
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size >= 3) {
                        serial = parts[2].removePrefix("+")
                        break
                    }
                }
            }
        }

        // 5. Parse Model from ^HH response if not already parsed
        if (model.isEmpty()) {
            for (line in lines) {
                val trimmed = line.trim()
                val upper = trimmed.uppercase()
                if (upper.contains("MODEL") && !upper.contains("CLIENTMODEL")) {
                    if (trimmed.contains(":")) {
                        val cand = trimmed.substringAfter(":").trim()
                        if (isValidModelName(cand)) {
                            model = cand
                            break
                        }
                    }
                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size > 1) {
                        val idx = parts.indexOfFirst { it.equals("MODEL", ignoreCase = true) }
                        if (idx > 0) {
                            val cand = parts.subList(0, idx).joinToString(" ")
                            if (isValidModelName(cand)) {
                                model = cand
                                break
                            }
                        }
                    }
                }
            }
        }

        if (model.isEmpty()) {
            for (line in lines) {
                val trimmed = line.trim()
                if ((trimmed.startsWith("ZTC ") || trimmed.startsWith("Zebra ")) && isValidModelName(trimmed)) {
                    model = trimmed
                    break
                }
            }
        }

        // 6. Parse Errors from ~HQES response
        var errorsParsed = false
        var hasError = false
        val knownMask = 0x04UL or 0x10000UL

        for (line in lines) {
            if (line.contains("ERRORS:")) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    var combinedMask = 0UL
                    var parsedGroups = 0

                    for (i in 1 until parts.size) {
                        val p = parts[i].trim { it == '\u0002' || it == '\u0003' || it == '\r' || it == '\n' || it == ' ' }
                        if (p.length == 8) {
                            val v = p.toULongOrNull(16)
                            if (v != null) {
                                combinedMask = combinedMask or v
                                parsedGroups++
                            }
                        }
                    }

                    if (parsedGroups == 0) {
                        val lastToken = parts.last().trim { it == '\u0002' || it == '\u0003' || it == '\r' || it == '\n' || it == ' ' }
                        val v = lastToken.toULongOrNull(16)
                        if (v != null) {
                            combinedMask = v
                            parsedGroups = 1
                        }
                    }

                    if (parsedGroups > 0) {
                        errorsParsed = true
                        if (combinedMask != 0UL) {
                            hasError = true
                            if ((combinedMask and 0x04UL) != 0UL) {
                                headOpen = true
                            }
                            if ((combinedMask and 0x10000UL) != 0UL) {
                                pause = true
                            }
                            if ((combinedMask and knownMask.inv()) != 0UL) {
                                other = true
                            }
                        }
                    }
                }
            }
        }

        // 6b. Parse from ~HS (Host Status)
        val hsTokens = lines.map { it.trim { c -> c == '\u0002' || c == '\u0003' || c == '\r' || c == '\n' || c == ' ' } }
        for (i in 0 until hsTokens.size - 1) {
            val f1 = hsTokens[i].split(",")
            val f2 = hsTokens[i + 1].split(",")
            if (f1.size >= 5 && f2.size >= 10 && f1[0].all { it.isDigit() } && f2[0].all { it.isDigit() }) {
                errorsParsed = true
                if (f1[1].trim() == "1") {
                    other = true
                    hasError = true
                }
                if (f1[2].trim() == "1") {
                    pause = true
                    hasError = true
                }
                if (f2[2].trim() == "1") {
                    headOpen = true
                    hasError = true
                }
                if (f2[3].trim() == "1") {
                    other = true
                    hasError = true
                }
                break
            }
        }

        // 6c. Parse from SGD "head.latch" (returns "open" or "ok")
        if (Regex("\"open\"", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
            normalized.contains("head.latch: open", ignoreCase = true)
        ) {
            headOpen = true
            hasError = true
            errorsParsed = true
        } else if (Regex("\"ok\"", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
            normalized.contains("head.latch: ok", ignoreCase = true)
        ) {
            if (!errorsParsed) {
                errorsParsed = true
            }
        }

        // 6d. Parse from SGD "device.pause" (returns "yes" or "no" or "1" / "0")
        if (Regex("\"(yes|on)\"", RegexOption.IGNORE_CASE).containsMatchIn(normalized)) {
            pause = true
            hasError = true
            errorsParsed = true
        }

        // 6e. If printer responded with valid identification/configuration but no error was flagged, consider it online
        if (!errorsParsed && (serial.isNotEmpty() || model.isNotEmpty() || labelLength != null)) {
            errorsParsed = true
            hasError = false
        }

        val ready: Boolean
        val status: String
        if (!errorsParsed) {
            ready = false
            status = "unknown"
            other = true
        } else if (hasError) {
            ready = false
            status = when {
                headOpen -> "headOpen"
                pause -> "pause"
                else -> "error"
            }
        } else {
            ready = true
            status = "online"
        }

        return PrinterInfo(
            ip = ip,
            serial = serial,
            model = model,
            type = "zpl",
            labelLength = labelLength,
            errors = PrinterErrors(
                headOpen = headOpen,
                pause = pause,
                other = other,
                unreachable = false
            ),
            ready = ready,
            status = status
        )
    }

    fun localIPv4Prefixes(): List<String> {
        val prefixes = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in Collections.list(interfaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val b = addr.address
                        val p0 = b[0].toInt() and 0xFF
                        val p1 = b[1].toInt() and 0xFF
                        val p2 = b[2].toInt() and 0xFF
                        if (p0 != 127) {
                            prefixes.add("$p0.$p1.$p2")
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return prefixes.toList()
    }

    fun probeZPLPrinter(ip: String): PrinterInfo? {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, PRINTER_PORT), 350)
                socket.soTimeout = 400
                val out = socket.getOutputStream()
                val cmd = "~HI~HQES! U1 getvar \"device.unique_id\"\r\n! U1 getvar \"zpl.label_length\"\r\n"
                    .toByteArray(Charsets.UTF_8)
                out.write(cmd)
                out.flush()

                val received = StringBuilder()
                val buf = ByteArray(4096)
                val input = socket.getInputStream()
                val deadline = System.currentTimeMillis() + 1800L

                while (System.currentTimeMillis() < deadline) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) {
                        received.append(String(buf, 0, n, Charsets.UTF_8))
                        val str = received.toString()
                        val hqesEnd = str.indexOf("WARNINGS:")
                        val hasCompleteHQES = hqesEnd != -1 && str.substring(hqesEnd).contains("\u0003")
                        val hasSGD = hqesEnd != -1 && str.substring(hqesEnd).count { it == '"' } >= 2
                        if (hasCompleteHQES && hasSGD) {
                            break
                        }
                    } else {
                        break
                    }
                }

                val data = received.toString()
                if (data.isBlank()) return null

                val info = parsePrinterData(ip, data)
                if (info.model.isEmpty() && info.serial.isEmpty() && !data.contains("ERRORS:") && !data.contains("PRINTER STATUS")) {
                    null
                } else {
                    info
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun scanLocalZPLPrinters(): List<PrinterInfo> {
        val bleResults = Collections.synchronizedList(ArrayList<PrinterInfo>())
        val bleThread = Thread({
            try {
                bleResults.addAll(BlePrinterManager.scanBLEPrinters())
            } catch (_: Exception) {
            }
        }, "ZebraBleScan").apply {
            isDaemon = true
            start()
        }

        val prefixes = localIPv4Prefixes()
        val found = Collections.synchronizedList(ArrayList<PrinterInfo>())

        if (prefixes.isNotEmpty()) {
            val candidateIPs = ArrayList<String>()
            for (p in prefixes) {
                for (host in 1..254) {
                    candidateIPs.add("$p.$host")
                }
            }

            val sem = Semaphore(64)
            val pool = Executors.newFixedThreadPool(64)

            try {
                val tasks = candidateIPs.map { ip ->
                    Callable {
                        sem.acquire()
                        try {
                            val info = probeZPLPrinter(ip)
                            if (info != null) {
                                found.add(info)
                            }
                        } finally {
                            sem.release()
                        }
                    }
                }
                pool.invokeAll(tasks, 15, TimeUnit.SECONDS)
            } catch (_: Exception) {
            } finally {
                pool.shutdownNow()
            }
        }

        try {
            bleThread.join(10_000)
        } catch (_: InterruptedException) {
        }

        return bleResults + found.sortedBy { it.ip }
    }
}
