package com.printeragentapp

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

@SuppressLint("MissingPermission")
object BlePrinterManager {
    val ZEBRA_SERVICE_UUID: UUID = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b")
    val ZEBRA_READ_CHAR_UUID: UUID = UUID.fromString("38eb4a81-c570-11e3-9507-0002a5d5c51b")
    val ZEBRA_WRITE_CHAR_UUID: UUID = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

    private val ZEBRA_MAC_PREFIXES = listOf(
        "34:14:B5",
        "AC:3F:A4",
        "00:07:4D",
        "D4:F5:47",
        "48:A4:93",
        "F0:5C:19"
    )

    @Volatile
    private var appContext: Context? = null

    private val mu = ReentrantLock()
    private var currentTarget: String = ""
    private var resolvedAddress: String = ""
    private var resolvedName: String = ""
    private var gatt: BluetoothGatt? = null
    private var readChar: BluetoothGattCharacteristic? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var sppSocket: BluetoothSocket? = null
    private var connected: Boolean = false
    private var negotiatedChunkSize: Int = 120

    private val recvLock = Object()
    private val recvBuf = StringBuilder()

    @Volatile
    private var writeLatch: CountDownLatch? = null
    @Volatile
    private var lastWriteStatus: Int = BluetoothGatt.GATT_SUCCESS

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    private fun log(msg: String) {
        val ctx = appContext ?: return
        try {
            AgentEngine.getInstance(ctx).logf(msg)
        } catch (_: Exception) {
        }
    }

    fun hasBluetoothPermissions(context: Context? = appContext): Boolean {
        val ctx = context ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(
                ctx,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            true
        }
    }

    fun isBluetoothTarget(target: String): Boolean {
        val clean = target.trim().lowercase()
        if (clean.isEmpty()) return false
        if (clean == "ble" || clean == "bluetooth" || clean == "auto") return true
        if (clean.startsWith("ble://") || clean.startsWith("ble:") ||
            clean.startsWith("bt://") || clean.startsWith("bt:")
        ) {
            return true
        }
        if (isMacAddress(clean)) return true
        if (clean.length == 12 && isHexString(clean)) return true
        return false
    }

    fun cleanTargetPrefix(target: String): String {
        var s = target.trim()
        val lower = s.lowercase()
        when {
            lower.startsWith("ble://") -> s = s.substring(6)
            lower.startsWith("ble:") -> s = s.substring(4)
            lower.startsWith("bt://") -> s = s.substring(5)
            lower.startsWith("bt:") -> s = s.substring(3)
        }
        return s.trim()
    }

    fun isMacAddress(raw: String): Boolean {
        val s = cleanTargetPrefix(raw).replace('-', ':').uppercase()
        val parts = s.split(":")
        if (parts.size != 6) return false
        return parts.all { it.length == 2 && isHexString(it) }
    }

    fun isHexString(s: String): Boolean {
        if (s.isEmpty()) return false
        for (c in s) {
            val ok = (c in '0'..'9') || (c in 'a'..'f') || (c in 'A'..'F')
            if (!ok) return false
        }
        return true
    }

    private fun normalizeMac(raw: String): String? {
        val clean = cleanTargetPrefix(raw)
        val withColons = clean.replace('-', ':').uppercase()
        if (isMacAddress(withColons)) {
            return withColons
        }
        if (clean.length == 12 && isHexString(clean)) {
            return (0 until 6).joinToString(":") { i ->
                clean.substring(i * 2, i * 2 + 2).uppercase()
            }
        }
        return null
    }

    private fun isLikelyZebraDevice(name: String?, address: String, serviceUuids: List<ParcelUuid>? = null): Boolean {
        if (serviceUuids != null) {
            for (u in serviceUuids) {
                if (u.uuid == ZEBRA_SERVICE_UUID) return true
            }
        }
        val upperAddr = address.uppercase()
        if (ZEBRA_MAC_PREFIXES.any { upperAddr.startsWith(it) }) {
            return true
        }
        val lowerName = (name ?: "").trim().lowercase()
        if (lowerName.isEmpty()) return false
        if (lowerName.contains("zebra") ||
            lowerName.startsWith("zd") ||
            lowerName.startsWith("zq") ||
            lowerName.startsWith("zt") ||
            lowerName.startsWith("gk") ||
            lowerName.startsWith("gx") ||
            lowerName.startsWith("ql") ||
            lowerName.startsWith("imz") ||
            lowerName.startsWith("xx") ||
            (lowerName.length >= 10 && lowerName.startsWith("50j"))
        ) {
            return true
        }
        return false
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        val ctx = appContext ?: return BluetoothAdapter.getDefaultAdapter()
        val mgr = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return mgr?.adapter ?: BluetoothAdapter.getDefaultAdapter()
    }

    private fun resolveBLEDevice(target: String): BluetoothDevice {
        val adapter = getBluetoothAdapter()
            ?: throw IllegalStateException("Bluetooth adapter not available on this device")
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is disabled; please enable Bluetooth")
        }
        if (!hasBluetoothPermissions()) {
            throw IllegalStateException("Bluetooth permissions (BLUETOOTH_SCAN / BLUETOOTH_CONNECT) not granted")
        }

        val clean = cleanTargetPrefix(target)
        val lower = clean.lowercase()

        // 1. Direct MAC address (e.g. 34:14:B5:B3:89:4A or 34-14-B5-B3-89-4A or 3414b5b3894a)
        val mac = normalizeMac(clean)
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
            return adapter.getRemoteDevice(mac)
        }

        val isAuto = lower == "ble" || lower == "bluetooth" || lower == "auto" || lower.isEmpty()

        // 2. Check already paired/bonded Android Bluetooth devices
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val devName = dev.name ?: ""
                val devAddr = dev.address ?: ""
                if (isAuto) {
                    if (isLikelyZebraDevice(devName, devAddr)) {
                        return dev
                    }
                } else {
                    if (devName.equals(clean, ignoreCase = true) || devAddr.equals(clean, ignoreCase = true)) {
                        return dev
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 3. Perform active BLE scan
        val scanner = adapter.bluetoothLeScanner
            ?: throw IllegalStateException("BLE scanner unavailable")

        var foundDevice: BluetoothDevice? = null
        val latch = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val scanName = result.scanRecord?.deviceName ?: dev.name ?: ""
                val addr = dev.address ?: ""
                val uuids = result.scanRecord?.serviceUuids

                val match = if (isAuto) {
                    isLikelyZebraDevice(scanName, addr, uuids)
                } else {
                    scanName.equals(clean, ignoreCase = true) ||
                        addr.equals(clean, ignoreCase = true) ||
                        scanName.lowercase().contains(lower)
                }

                if (match && foundDevice == null) {
                    foundDevice = dev
                    resolvedName = scanName
                    latch.countDown()
                }
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(null, settings, callback)
            latch.await(3500, TimeUnit.MILLISECONDS)
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }

        return foundDevice ?: throw IllegalStateException("No Zebra BLE printer found matching \"$target\"")
    }

    fun disconnect() {
        mu.lock()
        try {
            disconnectLocked()
        } finally {
            mu.unlock()
        }
    }

    private fun disconnectLocked() {
        connected = false
        readChar = null
        writeChar = null
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null

        try {
            sppSocket?.close()
        } catch (_: Exception) {
        }
        sppSocket = null
    }

    private fun ensureConnectedLocked(target: String) {
        if (connected && currentTarget == target && (gatt != null || sppSocket?.isConnected == true)) {
            return
        }

        disconnectLocked()

        val ctx = appContext ?: throw IllegalStateException("BlePrinterManager context not initialized")
        val device = resolveBLEDevice(target)
        resolvedAddress = device.address ?: ""
        if (resolvedName.isEmpty()) {
            resolvedName = try {
                device.name ?: ""
            } catch (_: Exception) {
                ""
            }
        }

        // Try BLE GATT connection first (Zebra BLE service 38eb4a80-c570-11e3-9507-0002a5d5c51b)
        val gattErr = tryConnectGattLocked(ctx, device, target)
        if (gattErr == null) {
            return
        }

        // Fallback to Bluetooth Classic SPP RFCOMM if BLE GATT service wasn't found (e.g. Classic BT Zebra)
        val sppErr = tryConnectSppLocked(device, target)
        if (sppErr == null) {
            return
        }

        throw IllegalStateException("BLE connect to ${device.address} failed: ${gattErr.message}")
    }

    private fun tryConnectGattLocked(ctx: Context, device: BluetoothDevice, target: String): Exception? {
        val readyLatch = CountDownLatch(1)
        var connectError: Exception? = null
        negotiatedChunkSize = 120

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    try {
                        // Request larger MTU if supported, or discover services immediately
                        val mtuRequested = g.requestMtu(256)
                        if (!mtuRequested) {
                            g.discoverServices()
                        }
                    } catch (_: Exception) {
                        g.discoverServices()
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    if (readyLatch.count > 0) {
                        connectError = IllegalStateException("GATT disconnected (status=$status)")
                        readyLatch.countDown()
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu > 23) {
                    negotiatedChunkSize = (mtu - 3).coerceIn(20, 180)
                }
                g.discoverServices()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectError = IllegalStateException("GATT service discovery failed ($status)")
                    readyLatch.countDown()
                    return
                }

                var foundRead: BluetoothGattCharacteristic? = null
                var foundWrite: BluetoothGattCharacteristic? = null

                val zebraSvc = g.getService(ZEBRA_SERVICE_UUID)
                val servicesToCheck = if (zebraSvc != null) listOf(zebraSvc) else g.services

                for (svc in servicesToCheck) {
                    for (c in svc.characteristics) {
                        val uStr = c.uuid.toString().lowercase()
                        if (uStr.startsWith("38eb4a81")) {
                            foundRead = c
                        } else if (uStr.startsWith("38eb4a82")) {
                            foundWrite = c
                        }
                    }
                }

                if (foundRead == null || foundWrite == null) {
                    connectError = IllegalStateException("Zebra BLE read/write characteristics (38eb4a81/38eb4a82) not found")
                    readyLatch.countDown()
                    return
                }

                readChar = foundRead
                writeChar = foundWrite

                g.setCharacteristicNotification(foundRead, true)
                val cccd = foundRead.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val props = foundRead.properties
                    val enableVal = if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        val res = g.writeDescriptor(cccd, enableVal)
                        if (res != BluetoothGatt.GATT_SUCCESS) {
                            readyLatch.countDown()
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = enableVal
                        @Suppress("DEPRECATION")
                        if (!g.writeDescriptor(cccd)) {
                            readyLatch.countDown()
                        }
                    }
                } else {
                    readyLatch.countDown()
                }
            }

            override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                readyLatch.countDown()
            }

            override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                lastWriteStatus = status
                writeLatch?.countDown()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                appendReceivedBytes(value)
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value
                    if (bytes != null) {
                        appendReceivedBytes(bytes)
                    }
                }
            }
        }

        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, callback)
        } ?: return IllegalStateException("connectGatt returned null")

        gatt = g
        val completed = readyLatch.await(8500, TimeUnit.MILLISECONDS)
        if (!completed || connectError != null || readChar == null || writeChar == null) {
            val err = connectError ?: IllegalStateException("timeout connecting to Zebra BLE GATT")
            disconnectLocked()
            return err
        }

        currentTarget = target
        connected = true
        return null
    }

    private fun tryConnectSppLocked(device: BluetoothDevice, target: String): Exception? {
        return try {
            val sock = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect()
            sppSocket = sock
            currentTarget = target
            connected = true
            // Start background reader thread for SPP socket
            Thread({
                val buf = ByteArray(1024)
                val input = try {
                    sock.inputStream
                } catch (_: Exception) {
                    null
                }
                while (connected && sppSocket == sock && input != null) {
                    val n = try {
                        input.read(buf)
                    } catch (_: Exception) {
                        break
                    }
                    if (n > 0) {
                        appendReceivedBytes(buf.copyOfRange(0, n))
                    } else if (n < 0) {
                        break
                    }
                }
            }, "ZebraSppReader").apply {
                isDaemon = true
                start()
            }
            null
        } catch (e: Exception) {
            disconnectLocked()
            e
        }
    }

    private fun appendReceivedBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val chunk = String(bytes, Charsets.UTF_8)
        synchronized(recvLock) {
            recvBuf.append(chunk)
            recvLock.notifyAll()
        }
    }

    private fun writeChunksLocked(data: ByteArray) {
        val spp = sppSocket
        if (spp != null && spp.isConnected) {
            val out = spp.outputStream
            out.write(data)
            out.flush()
            return
        }

        val g = gatt ?: throw IllegalStateException("BLE GATT not connected")
        val wc = writeChar ?: throw IllegalStateException("BLE write characteristic not initialized")
        val chunkSize = negotiatedChunkSize.coerceIn(20, 180)

        var offset = 0
        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val slice = data.copyOfRange(offset, end)
            val latch = CountDownLatch(1)
            writeLatch = latch
            lastWriteStatus = BluetoothGatt.GATT_SUCCESS

            val props = wc.properties
            val writeType = if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(wc, slice, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                wc.writeType = writeType
                @Suppress("DEPRECATION")
                wc.value = slice
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }

            if (!started) {
                disconnectLocked()
                throw IllegalStateException("BLE writeCharacteristic failed to initiate")
            }

            if (!latch.await(5000, TimeUnit.MILLISECONDS) || lastWriteStatus != BluetoothGatt.GATT_SUCCESS) {
                disconnectLocked()
                throw IllegalStateException("BLE writeCharacteristic timed out or failed (status=$lastWriteStatus)")
            }

            offset = end
        }
    }

    private fun queryLocked(
        target: String,
        cmd: String,
        timeoutMs: Long,
        until: (String) -> Boolean
    ): String {
        ensureConnectedLocked(target)

        synchronized(recvLock) {
            recvBuf.setLength(0)
        }

        writeChunksLocked(cmd.toByteArray(Charsets.UTF_8))

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val currentStr = synchronized(recvLock) { recvBuf.toString() }
            if (until(currentStr)) {
                return currentStr
            }
            val remain = deadline - System.currentTimeMillis()
            if (remain <= 0) {
                return currentStr
            }
            synchronized(recvLock) {
                try {
                    recvLock.wait(remain.coerceAtMost(250L))
                } catch (_: InterruptedException) {
                }
            }
        }
    }

    fun queryPrinterInfoLocked(target: String): PrinterInfo {
        val offlineInfo = PrinterInfo(
            ip = target,
            type = "zpl",
            status = "offline",
            ready = false,
            errors = PrinterErrors(unreachable = true)
        )

        mu.lock()
        try {
            // Query Model (~HI), Errors (~HQES), Serial (device.unique_id), and Label Length (zpl.label_length)
            val cmd = "~HI~HQES! U1 getvar \"device.unique_id\"\r\n! U1 getvar \"zpl.label_length\"\r\n"
            val data = queryLocked(target, cmd, 3500L) { str ->
                val hqesEnd = str.indexOf("WARNINGS:")
                val hasCompleteHQES = hqesEnd != -1 && str.substring(hqesEnd).contains("\u0003")
                val hasSGD = str.count { it == '"' } >= 4
                hasCompleteHQES && hasSGD
            }

            if (data.isBlank()) {
                // If connected over BLE GATT, even if ZPL status response timed out, check if we have device identity
                if (connected && resolvedAddress.isNotEmpty()) {
                    val fallbackSerial = if (resolvedName.length >= 6 && !resolvedName.contains(" ")) {
                        resolvedName
                    } else {
                        resolvedAddress.replace(":", "")
                    }
                    return PrinterInfo(
                        ip = target,
                        serial = fallbackSerial,
                        model = resolvedName.ifEmpty { "Zebra BLE Printer" },
                        type = "zpl",
                        ready = true,
                        status = "online",
                        errors = PrinterErrors(unreachable = false)
                    )
                }
                return offlineInfo
            }

            val parsed = PrinterProtocol.parsePrinterData(target, data)
            val finalSerial = parsed.serial.ifEmpty {
                if (resolvedName.length in 6..24 && !resolvedName.contains(" ")) resolvedName else ""
            }
            val finalModel = parsed.model.ifEmpty {
                resolvedName.ifEmpty { "Zebra BLE" }
            }
            return parsed.copy(serial = finalSerial, model = finalModel)
        } catch (e: Exception) {
            log("BLE query failed for $target: ${e.message}")
            disconnectLocked()
            return offlineInfo
        } finally {
            mu.unlock()
        }
    }

    fun sendZPLLocked(target: String, zpl: String) {
        mu.lock()
        try {
            ensureConnectedLocked(target)
            writeChunksLocked(zpl.toByteArray(Charsets.UTF_8))
        } finally {
            mu.unlock()
        }
    }

    fun queryStatusLocked(target: String): HostStatus {
        mu.lock()
        try {
            val data = queryLocked(target, "~HS\r\n", 3000L) { str ->
                str.count { it == '\n' } >= 3 && str.count { it == ',' } >= 20
            }

            if (data.isBlank()) {
                throw IllegalStateException("empty ~HS response via BLE")
            }

            val lines = data.split("\n")
                .map { it.trim { ch -> ch == '\u0002' || ch == '\u0003' || ch == '\r' || ch == '\n' || ch == ' ' } }
                .filter { it.isNotEmpty() && it.contains(",") }

            if (lines.size < 2) {
                throw IllegalStateException("unexpected ~HS response via BLE: $data")
            }

            val f1 = lines[0].split(",")
            val f2 = lines[1].split(",")
            if (f1.size < 5 || f2.size < 10) {
                throw IllegalStateException("malformed ~HS fields via BLE")
            }

            return HostStatus(
                paperOut = f1[1].trim() == "1",
                paused = f1[2].trim() == "1",
                formatsInBuffer = f1[4].trim().toIntOrNull() ?: 0,
                headUp = f2[2].trim() == "1",
                ribbonOut = f2[3].trim() == "1",
                labelsRemaining = f2[8].trim().toIntOrNull() ?: 0,
                printingFormat = f2[9].trim() == "1"
            )
        } finally {
            mu.unlock()
        }
    }

    fun scanBLEPrinters(): List<PrinterInfo> {
        val adapter = getBluetoothAdapter() ?: return emptyList()
        if (!adapter.isEnabled || !hasBluetoothPermissions()) {
            return emptyList()
        }

        val discoveredByMac = LinkedHashMap<String, String>()

        // 1. Inspect bonded devices
        try {
            val bonded = adapter.bondedDevices ?: emptySet()
            for (dev in bonded) {
                val addr = dev.address ?: continue
                val name = dev.name ?: ""
                if (isLikelyZebraDevice(name, addr)) {
                    discoveredByMac[addr] = name
                }
            }
        } catch (_: Exception) {
        }

        // 2. Active BLE scan for 3 seconds
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    val addr = dev.address ?: return
                    val name = result.scanRecord?.deviceName ?: dev.name ?: ""
                    val uuids = result.scanRecord?.serviceUuids
                    if (isLikelyZebraDevice(name, addr, uuids)) {
                        synchronized(discoveredByMac) {
                            if (!discoveredByMac.containsKey(addr) || discoveredByMac[addr].isNullOrEmpty()) {
                                discoveredByMac[addr] = name
                            }
                        }
                    }
                }
            }

            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(null, settings, callback)
                Thread.sleep(3000)
            } catch (_: Exception) {
            } finally {
                try {
                    scanner.stopScan(callback)
                } catch (_: Exception) {
                }
            }
        }

        val results = ArrayList<PrinterInfo>()
        for ((mac, advName) in discoveredByMac) {
            val bleTarget = "ble:$mac"
            val probed = queryPrinterInfoLocked(bleTarget)
            if (!probed.errors.unreachable) {
                results.add(probed)
            } else {
                // Still surface the discovered BLE printer so the user can tap to add it
                val serialGuess = if (advName.length in 6..24 && !advName.contains(" ")) advName else ""
                results.add(
                    PrinterInfo(
                        ip = bleTarget,
                        serial = serialGuess,
                        model = advName.ifEmpty { "Zebra BLE Printer" },
                        type = "zpl",
                        ready = false,
                        status = "discovered",
                        errors = PrinterErrors(unreachable = false)
                    )
                )
            }
        }
        return results
    }
}

