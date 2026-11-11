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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Zebra Bluetooth LE Printer Manager based on:
 * - Zebra Link-OS Bluetooth Low Energy Application Note (AppNote-BlueToothLE-v4.pdf)
 * - zebra_ble_printer / universal_ble implementation (tested on Zebra ZD410)
 *
 * Uses Zebra BLE Parser Service (38eb4a80-c570-11e3-9507-0002a5d5c51b) and writes ZPL
 * to "To Printer Data" (38eb4a82-c570-11e3-9507-0002a5d5c51b) in MTU-sized chunks
 * with response (WRITE_TYPE_DEFAULT). Reads Model (0x2A24) and Serial (0x2A25) from
 * standard BLE Device Information Service (0x180A).
 */
@SuppressLint("MissingPermission")
object BlePrinterManager {
    // Zebra BLE Parser Service & Characteristics
    val ZEBRA_PARSER_SERVICE_UUID: UUID = UUID.fromString("38eb4a80-c570-11e3-9507-0002a5d5c51b")
    val ZEBRA_FROM_PRINTER_UUID: UUID = UUID.fromString("38eb4a81-c570-11e3-9507-0002a5d5c51b")
    val ZEBRA_TO_PRINTER_UUID: UUID = UUID.fromString("38eb4a82-c570-11e3-9507-0002a5d5c51b")

    // 16-bit service UUID (0xFE79) that Zebra printers advertise
    val ZEBRA_ADVERTISED_SERVICE_UUID: UUID = UUID.fromString("0000fe79-0000-1000-8000-00805f9b34fb")

    // Standard BLE Device Information Service (DIS 0x180A) & GAP (0x1800) & CCCD (0x2902)
    val DIS_SERVICE_UUID: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DIS_MODEL_CHAR_UUID: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DIS_SERIAL_CHAR_UUID: UUID = UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val GAP_SERVICE_UUID: UUID = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
    val GAP_DEVICE_NAME_UUID: UUID = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private const val DEFAULT_MTU = 23

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

    private val mu = ReentrantLock(true)
    private var currentTarget: String = ""
    private var resolvedAddress: String = ""
    private var resolvedName: String = ""
    private var deviceModel: String = ""
    private var deviceSerial: String = ""
    private var cachedLabelLength: Int? = null
    private var hhLabelLengthQueried: Boolean = false
    private var lastFullBlePollMs: Long = 0L
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var fromPrinterChar: BluetoothGattCharacteristic? = null

    @Volatile
    private var connected: Boolean = false
    @Volatile
    private var negotiatedMtu: Int = DEFAULT_MTU

    // Per-operation synchronization latches (populated before issuing GATT calls)
    @Volatile
    private var connectLatch: CountDownLatch? = null
    @Volatile
    private var connectStatus: Int = BluetoothGatt.GATT_SUCCESS

    @Volatile
    private var mtuLatch: CountDownLatch? = null

    @Volatile
    private var discoverLatch: CountDownLatch? = null
    @Volatile
    private var discoverStatus: Int = BluetoothGatt.GATT_SUCCESS

    @Volatile
    private var descriptorLatch: CountDownLatch? = null

    @Volatile
    private var readLatch: CountDownLatch? = null
    @Volatile
    private var lastReadBytes: ByteArray? = null

    @Volatile
    private var writeLatch: CountDownLatch? = null
    @Volatile
    private var lastWriteStatus: Int = BluetoothGatt.GATT_SUCCESS

    private val incomingLock = Object()
    private val incomingBuffer = ByteArrayOutputStream()
    @Volatile
    private var incomingLatch: CountDownLatch? = null
    @Volatile
    private var lastIncomingPacketTime: Long = 0L
    @Volatile
    private var expectSgdForCurrentQuery: Boolean = false

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun abortActiveQuery() {
        synchronized(incomingLock) {
            incomingLatch?.countDown()
        }
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

    /**
     * Matches ZebraBle.looksLikeZebra from zebra_ble_printer plus known Zebra OUIs & serial prefixes.
     */
    private fun looksLikeZebra(
        name: String?,
        address: String,
        serviceUuids: List<ParcelUuid>? = null
    ): Boolean {
        if (serviceUuids != null) {
            for (u in serviceUuids) {
                if (u.uuid == ZEBRA_PARSER_SERVICE_UUID || u.uuid == ZEBRA_ADVERTISED_SERVICE_UUID) {
                    return true
                }
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

    private fun getBluetoothManager(): BluetoothManager? {
        val ctx = appContext ?: return null
        return ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        return getBluetoothManager()?.adapter
    }

    /**
     * Scans BLE for up to [timeoutMs] until a matching device is found.
     */
    private fun scanForDevice(matcher: (BluetoothDevice, String, List<ParcelUuid>?) -> Boolean, timeoutMs: Long): Pair<BluetoothDevice, String>? {
        val adapter = getBluetoothAdapter() ?: return null
        val scanner = adapter.bluetoothLeScanner ?: return null

        var found: Pair<BluetoothDevice, String>? = null
        val latch = CountDownLatch(1)

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val scanName = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                    ?: try { dev.name ?: "" } catch (_: Exception) { "" }
                val uuids = result.scanRecord?.serviceUuids
                if (matcher(dev, scanName, uuids) && found == null) {
                    found = Pair(dev, scanName)
                    latch.countDown()
                }
            }
        }

        val settingsBuilder = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= 26) {
            settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            settingsBuilder.setLegacy(false)
        }

        try {
            scanner.startScan(null, settingsBuilder.build(), callback)
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
        } finally {
            try {
                scanner.stopScan(callback)
            } catch (_: Exception) {
            }
        }
        return found
    }

    private fun resolveBLEDevice(target: String, forceScan: Boolean = false): BluetoothDevice {
        val adapter = getBluetoothAdapter()
            ?: throw IllegalStateException("Bluetooth adapter not available")
        if (!adapter.isEnabled) {
            throw IllegalStateException("Bluetooth is turned off")
        }
        if (!hasBluetoothPermissions()) {
            throw IllegalStateException("Bluetooth permissions not granted")
        }

        val clean = cleanTargetPrefix(target)
        val lower = clean.lowercase()
        val mac = normalizeMac(clean)

        // 1. Direct MAC address
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
            if (forceScan) {
                val scanned = scanForDevice({ dev, _, _ -> dev.address.equals(mac, ignoreCase = true) }, 4000L)
                if (scanned != null) {
                    if (scanned.second.isNotEmpty()) resolvedName = scanned.second
                    return scanned.first
                }
            }
            return adapter.getRemoteDevice(mac)
        }

        val isAuto = lower == "ble" || lower == "bluetooth" || lower == "auto" || lower.isEmpty()

        // 2. Check already connected GATT devices and bonded devices
        val candidates = ArrayList<BluetoothDevice>()
        try {
            getBluetoothManager()?.getConnectedDevices(BluetoothProfile.GATT)?.let { candidates.addAll(it) }
        } catch (_: Exception) {
        }
        try {
            adapter.bondedDevices?.let { candidates.addAll(it) }
        } catch (_: Exception) {
        }

        for (dev in candidates) {
            val devName = try { dev.name ?: "" } catch (_: Exception) { "" }
            val devAddr = dev.address ?: ""
            if (isAuto) {
                if (looksLikeZebra(devName, devAddr)) {
                    if (devName.isNotEmpty()) resolvedName = devName
                    return dev
                }
            } else if (devName.equals(clean, ignoreCase = true) || devAddr.equals(clean, ignoreCase = true)) {
                if (devName.isNotEmpty()) resolvedName = devName
                return dev
            }
        }

        // 3. Scan for BLE advertisements
        val scanned = scanForDevice({ dev, scanName, uuids ->
            val addr = dev.address ?: ""
            if (isAuto) {
                looksLikeZebra(scanName, addr, uuids)
            } else {
                scanName.equals(clean, ignoreCase = true) ||
                    addr.equals(clean, ignoreCase = true) ||
                    (scanName.isNotEmpty() && scanName.lowercase().contains(lower))
            }
        }, 5000L)

        if (scanned != null) {
            if (scanned.second.isNotEmpty()) resolvedName = scanned.second
            return scanned.first
        }

        throw IllegalStateException("No Zebra BLE printer found matching \"$target\"")
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
        writeChar = null
        fromPrinterChar = null
        descriptorLatch?.countDown()
        descriptorLatch = null
        synchronized(incomingLock) {
            incomingLatch?.countDown()
            incomingLatch = null
            incomingBuffer.reset()
        }
        negotiatedMtu = DEFAULT_MTU
        val g = gatt
        gatt = null
        if (g != null) {
            try {
                g.disconnect()
            } catch (_: Exception) {
            }
            try {
                g.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun ensureConnectedLocked(target: String) {
        if (connected && currentTarget == target && gatt != null && writeChar != null) {
            return
        }

        disconnectLocked()

        val ctx = appContext ?: throw IllegalStateException("BlePrinterManager context not initialized")

        // Try direct connect first; if the device hasn't been seen in BLE scan cache yet, scan & retry once
        var device = resolveBLEDevice(target, forceScan = false)
        var err = connectAndDiscoverLocked(ctx, device, target)
        if (err != null && normalizeMac(target) != null) {
            device = resolveBLEDevice(target, forceScan = true)
            err = connectAndDiscoverLocked(ctx, device, target)
        }

        if (err != null) {
            throw err
        }
    }

    private fun connectAndDiscoverLocked(ctx: Context, device: BluetoothDevice, target: String): Exception? {
        val prevAddr = resolvedAddress
        resolvedAddress = device.address ?: ""
        if (!prevAddr.equals(resolvedAddress, ignoreCase = true)) {
            deviceModel = ""
            deviceSerial = ""
            cachedLabelLength = null
            hhLabelLengthQueried = false
            lastFullBlePollMs = 0L
        }
        val initialName = try { device.name ?: "" } catch (_: Exception) { "" }
        if (initialName.isNotEmpty()) {
            resolvedName = initialName
        }

        val cLatch = CountDownLatch(1)
        connectLatch = cLatch
        connectStatus = BluetoothGatt.GATT_FAILURE
        negotiatedMtu = DEFAULT_MTU

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectStatus = BluetoothGatt.GATT_SUCCESS
                    connected = true
                    try {
                        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Exception) {
                    }
                    connectLatch?.countDown()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connected = false
                    connectStatus = if (status == BluetoothGatt.GATT_SUCCESS) -1 else status
                    connectLatch?.countDown()
                    mtuLatch?.countDown()
                    discoverLatch?.countDown()
                    readLatch?.countDown()
                    writeLatch?.countDown()
                    if (gatt === g) {
                        try {
                            g.close()
                        } catch (_: Exception) {
                        }
                        gatt = null
                        writeChar = null
                    }
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && mtu >= DEFAULT_MTU) {
                    negotiatedMtu = mtu
                }
                mtuLatch?.countDown()
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                discoverStatus = status
                discoverLatch?.countDown()
            }

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                lastReadBytes = if (status == BluetoothGatt.GATT_SUCCESS) value else null
                readLatch?.countDown()
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    @Suppress("DEPRECATION")
                    lastReadBytes = if (status == BluetoothGatt.GATT_SUCCESS) characteristic.value else null
                    readLatch?.countDown()
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                lastWriteStatus = status
                writeLatch?.countDown()
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                descriptorLatch?.countDown()
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                if (characteristic.uuid == ZEBRA_FROM_PRINTER_UUID) {
                    onFromPrinterBytesReceived(value)
                }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    if (characteristic.uuid == ZEBRA_FROM_PRINTER_UUID) {
                        @Suppress("DEPRECATION")
                        val v = characteristic.value
                        if (v != null) {
                            onFromPrinterBytesReceived(v)
                        }
                    }
                }
            }
        }

        // 1. Connect GATT (matching UniversalBle.connect)
        val g = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(ctx, false, callback)
        } ?: return IllegalStateException("connectGatt returned null")

        gatt = g

        if (!cLatch.await(10_000, TimeUnit.MILLISECONDS) || !connected || connectStatus != BluetoothGatt.GATT_SUCCESS) {
            disconnectLocked()
            return IllegalStateException("BLE connection to ${device.address} failed (status=$connectStatus)")
        }

        // 2. Negotiate MTU (matching ZebraBlePrinter._negotiateMtu: request 512, fallback to 23)
        val mLatch = CountDownLatch(1)
        mtuLatch = mLatch
        try {
            if (g.requestMtu(512)) {
                mLatch.await(3000, TimeUnit.MILLISECONDS)
            }
        } catch (_: Exception) {
        } finally {
            mtuLatch = null
        }

        if (!connected) {
            disconnectLocked()
            return IllegalStateException("BLE disconnected during MTU negotiation")
        }

        // 3. Discover Services (matching UniversalBle.discoverServices)
        val dLatch = CountDownLatch(1)
        discoverLatch = dLatch
        discoverStatus = BluetoothGatt.GATT_FAILURE
        val startedDiscover = try {
            g.discoverServices()
        } catch (_: Exception) {
            false
        }
        if (!startedDiscover || !dLatch.await(8000, TimeUnit.MILLISECONDS) || discoverStatus != BluetoothGatt.GATT_SUCCESS) {
            discoverLatch = null
            disconnectLocked()
            return IllegalStateException("BLE service discovery failed on ${device.address} (status=$discoverStatus)")
        }
        discoverLatch = null

        // 4. Find writable "To Printer Data" (38eb4a82) & indicating "From Printer Data" (38eb4a81)
        var foundWrite: BluetoothGattCharacteristic? = null
        var foundRead: BluetoothGattCharacteristic? = null
        for (svc in g.services) {
            for (c in svc.characteristics) {
                if (c.uuid == ZEBRA_TO_PRINTER_UUID &&
                    (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0
                ) {
                    foundWrite = c
                }
                if (c.uuid == ZEBRA_FROM_PRINTER_UUID &&
                    (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE or BluetoothGattCharacteristic.PROPERTY_READ)) != 0
                ) {
                    foundRead = c
                }
            }
            if (foundWrite != null && foundRead != null) break
        }

        if (foundWrite == null) {
            disconnectLocked()
            return IllegalStateException("Zebra print characteristic (38eb4a82) not found on ${device.address}")
        }

        writeChar = foundWrite
        fromPrinterChar = foundRead
        currentTarget = target

        // Enable indications/notifications on From Printer Data (38eb4a81)
        if (foundRead != null) {
            try {
                g.setCharacteristicNotification(foundRead, true)
                val cccd = foundRead.getDescriptor(CCCD_UUID)
                if (cccd != null) {
                    val isIndicate = (foundRead.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                    val cccdVal = if (isIndicate) {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    }
                    val dLatchDesc = CountDownLatch(1)
                    descriptorLatch = dLatchDesc
                    val writeOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, cccdVal) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = cccdVal
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                    if (writeOk) {
                        dLatchDesc.await(3000, TimeUnit.MILLISECONDS)
                    }
                    descriptorLatch = null
                }
            } catch (e: Exception) {
                log("Warning: Failed to enable CCCD on 38eb4a81: ${e.message}")
            }
        }

        // 5. Read Model Name (0x2A24) and Serial Number (0x2A25) from BLE Device Information Service (0x180A)
        // as documented on Page 4 of Zebra's AppNote-BlueToothLE-v4.pdf
        val disService = g.getService(DIS_SERVICE_UUID)
        if (disService != null) {
            if (deviceModel.isEmpty()) {
                val modelChar = disService.getCharacteristic(DIS_MODEL_CHAR_UUID)
                if (modelChar != null) {
                    readCharacteristicStringLocked(g, modelChar)?.takeIf { it.isNotEmpty() }?.let {
                        deviceModel = it
                    }
                }
            }
            if (deviceSerial.isEmpty()) {
                val serialChar = disService.getCharacteristic(DIS_SERIAL_CHAR_UUID)
                if (serialChar != null) {
                    readCharacteristicStringLocked(g, serialChar)?.takeIf { it.isNotEmpty() }?.let {
                        deviceSerial = it
                    }
                }
            }
        }

        if (deviceSerial.isEmpty()) {
            val gapService = g.getService(GAP_SERVICE_UUID)
            val nameChar = gapService?.getCharacteristic(GAP_DEVICE_NAME_UUID)
            if (nameChar != null) {
                readCharacteristicStringLocked(g, nameChar)?.takeIf { it.isNotEmpty() }?.let {
                    resolvedName = it
                }
            }
        }

        if (deviceSerial.isEmpty()) {
            deviceSerial = if (resolvedName.length in 6..24 && !resolvedName.contains(" ")) {
                resolvedName
            } else {
                resolvedAddress.replace(":", "")
            }
        }
        if (deviceModel.isEmpty()) {
            deviceModel = if (resolvedName.isNotEmpty() && !resolvedName.equals(deviceSerial, ignoreCase = true)) {
                resolvedName
            } else {
                "Zebra BLE Printer"
            }
        }

        log("BLE connected to ${device.address} (model=\"$deviceModel\" serial=\"$deviceSerial\" mtu=$negotiatedMtu)")
        return null
    }

    private fun readCharacteristicStringLocked(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): String? {
        if ((characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
            return null
        }
        val rLatch = CountDownLatch(1)
        readLatch = rLatch
        lastReadBytes = null
        return try {
            if (!g.readCharacteristic(characteristic)) {
                return null
            }
            if (!rLatch.await(2500, TimeUnit.MILLISECONDS)) {
                return null
            }
            val bytes = lastReadBytes ?: return null
            String(bytes, Charsets.UTF_8).trim { it <= ' ' || it == '\u0000' }
        } catch (_: Exception) {
            null
        } finally {
            readLatch = null
        }
    }

    /**
     * Replicates chunkedWrite from zebra_ble_printer/lib/src/chunked_write.dart:
     * Splits [data] into blocks of min(mtu - 3, 512) bytes and writes each chunk
     * with response (WRITE_TYPE_DEFAULT) so the printer buffer never overflows.
     * If the printer rejects a larger chunk (e.g. status 134 / 0x86 when attribute
     * length is capped at 20 bytes despite a higher L2CAP MTU), automatically falls
     * back to 20-byte chunks (DEFAULT_MTU - 3).
     */
    private fun writeChunksLocked(data: ByteArray) {
        val g = gatt ?: throw IllegalStateException("BLE GATT not connected")
        val wc = writeChar ?: throw IllegalStateException("BLE write characteristic not initialized")

        var chunkSize = (negotiatedMtu - 3).coerceIn(1, 512)
        var offset = 0

        while (offset < data.size) {
            val end = (offset + chunkSize).coerceAtMost(data.size)
            val slice = data.copyOfRange(offset, end)

            val status = writeSingleChunkLocked(g, wc, slice)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                // If chunk > 20 bytes failed (e.g. status=134 on ZD410), downgrade to 20-byte chunks and retry
                if (chunkSize > 20) {
                    log("BLE write chunk ($chunkSize bytes) returned status=$status; falling back to 20-byte chunks")
                    negotiatedMtu = DEFAULT_MTU
                    chunkSize = 20
                    continue
                }
                disconnectLocked()
                throw IllegalStateException("BLE writeCharacteristic failed (status=$status)")
            }

            offset = end
        }
    }

    private fun writeSingleChunkLocked(
        g: BluetoothGatt,
        wc: BluetoothGattCharacteristic,
        slice: ByteArray
    ): Int {
        val latch = CountDownLatch(1)
        writeLatch = latch
        lastWriteStatus = BluetoothGatt.GATT_FAILURE

        val writeType = if ((wc.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }

        val initiated = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(wc, slice, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                wc.value = slice
                @Suppress("DEPRECATION")
                wc.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(wc)
            }
        } catch (_: Exception) {
            false
        }

        if (!initiated) {
            writeLatch = null
            return BluetoothGatt.GATT_FAILURE
        }

        val completed = latch.await(15_000, TimeUnit.MILLISECONDS)
        writeLatch = null
        return if (completed) lastWriteStatus else -1
    }

    private fun onFromPrinterBytesReceived(bytes: ByteArray) {
        synchronized(incomingLock) {
            incomingBuffer.write(bytes)
            lastIncomingPacketTime = System.currentTimeMillis()
            val str = incomingBuffer.toString(Charsets.UTF_8.name())

            val hasHqes = str.contains("WARNINGS:") && str.contains("\u0003")
            val hasHs = str.contains("\n") && str.split(",").size >= 12
            val hasHh = str.contains("LABEL LENGTH") && str.contains("\u0003")
            val hasSgd = str.count { it == '"' } >= 2
            if (expectSgdForCurrentQuery) {
                if ((hasHqes || hasHs) && hasSgd) {
                    incomingLatch?.countDown()
                }
            } else {
                if (hasHqes || hasHs || hasHh) {
                    incomingLatch?.countDown()
                }
            }
        }
    }

    fun queryBleResponseLocked(
        cmd: String,
        timeoutMs: Long = 1800L,
        expectSgd: Boolean = false
    ): String {
        if (PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) return ""
        val g = gatt ?: throw IllegalStateException("BLE not connected")
        synchronized(incomingLock) {
            incomingBuffer.reset()
            lastIncomingPacketTime = 0L
            expectSgdForCurrentQuery = expectSgd
            incomingLatch = CountDownLatch(1)
        }

        try {
            writeChunksLocked(cmd.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            log("BLE write query failed: ${e.message}")
            return ""
        }

        val latch = incomingLatch
        val matched = latch?.await(timeoutMs, TimeUnit.MILLISECONDS) ?: false

        if (!PrinterProtocol.isPrintPending() && !PrinterProtocol.isPrinting() && (expectSgd || !matched)) {
            val graceDeadline = System.currentTimeMillis() + if (matched) 40L else 80L
            while (System.currentTimeMillis() < graceDeadline && !PrinterProtocol.isPrintPending() && !PrinterProtocol.isPrinting()) {
                try {
                    Thread.sleep(20L)
                } catch (_: InterruptedException) {
                    break
                }
                val elapsed = System.currentTimeMillis() - lastIncomingPacketTime
                if (lastIncomingPacketTime > 0 && elapsed > 45L) {
                    break
                }
            }
        }

        return synchronized(incomingLock) {
            incomingLatch = null
            val s = incomingBuffer.toString(Charsets.UTF_8.name())
            incomingBuffer.reset()
            s
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

        if (PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) return offlineInfo

        mu.lock()
        try {
            if (PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) return offlineInfo
            ensureConnectedLocked(target)
            if (PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) return offlineInfo

            val now = System.currentTimeMillis()
            val hasStaticInfo = deviceModel.isNotEmpty() &&
                deviceSerial.isNotEmpty() &&
                (cachedLabelLength != null || hhLabelLengthQueried) &&
                (now - lastFullBlePollMs) < 60_000L

            val response = if (hasStaticInfo) {
                queryBleResponseLocked("~HQES\r\n~HS\r\n", timeoutMs = 600L, expectSgd = false)
            } else {
                lastFullBlePollMs = now
                val queryCmd = "~HQES\r\n~HS\r\n! U1 getvar \"zpl.label_length\"\r\n! U1 getvar \"head.latch\"\r\n! U1 getvar \"device.pause\"\r\n! U1 getvar \"device.unique_id\"\r\n! U1 getvar \"device.product_name\"\r\n"
                queryBleResponseLocked(queryCmd, timeoutMs = 1500L, expectSgd = true)
            }

            val parsed = if (response.isNotBlank()) {
                PrinterProtocol.parsePrinterData(target, response)
            } else {
                PrinterInfo(
                    ip = target,
                    type = "zpl",
                    status = "online",
                    ready = true,
                    errors = PrinterErrors(unreachable = false)
                )
            }

            if (parsed.labelLength != null) {
                cachedLabelLength = parsed.labelLength
            }

            var finalLabelLength = parsed.labelLength ?: cachedLabelLength
            if (finalLabelLength == null && connected && !hhLabelLengthQueried && !PrinterProtocol.isPrintPending() && !PrinterProtocol.isPrinting()) {
                hhLabelLengthQueried = true
                val hhResp = queryBleResponseLocked("^XA^HH^XZ\r\n", timeoutMs = 1200L, expectSgd = false)
                if (hhResp.isNotBlank()) {
                    val hhParsed = PrinterProtocol.parsePrinterData(target, hhResp)
                    if (hhParsed.labelLength != null) {
                        finalLabelLength = hhParsed.labelLength
                        cachedLabelLength = hhParsed.labelLength
                    }
                }
            }

            if (deviceModel.isEmpty() && PrinterProtocol.isValidModelName(parsed.model)) {
                deviceModel = parsed.model
            }
            if (deviceSerial.isEmpty() && parsed.serial.isNotEmpty()) {
                deviceSerial = parsed.serial
            }

            val finalModel = when {
                PrinterProtocol.isValidModelName(deviceModel) -> deviceModel
                PrinterProtocol.isValidModelName(parsed.model) -> parsed.model
                PrinterProtocol.isValidModelName(resolvedName) -> resolvedName
                else -> "Zebra BLE Printer"
            }
            val finalSerial = when {
                deviceSerial.isNotEmpty() -> deviceSerial
                parsed.serial.isNotEmpty() -> parsed.serial
                else -> ""
            }

            return parsed.copy(
                model = finalModel,
                serial = finalSerial,
                labelLength = finalLabelLength
            )
        } catch (e: Exception) {
            log("BLE connect/status check failed for $target: ${e.message}")
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
            try {
                gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
            } catch (_: Exception) {
            }
            writeChunksLocked(zpl.toByteArray(Charsets.UTF_8))
        } finally {
            mu.unlock()
        }
    }

    fun queryStatusLocked(target: String): HostStatus {
        if (PrinterProtocol.isPrintPending() || PrinterProtocol.isPrinting()) {
            return HostStatus(printingFormat = true)
        }
        mu.lock()
        try {
            ensureConnectedLocked(target)
            val resp = queryBleResponseLocked(
                "~HS~HQES\r\n! U1 getvar \"head.latch\"\r\n! U1 getvar \"device.pause\"\r\n",
                timeoutMs = 1000L,
                expectSgd = true
            )
            if (resp.isBlank()) {
                return HostStatus()
            }
            return PrinterProtocol.parseHostStatus(resp)
        } catch (e: Exception) {
            log("BLE queryStatus failed for $target: ${e.message}")
            return HostStatus()
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

        // 0. If already connected to a BLE printer, include it first (connected BLE devices stop advertising)
        mu.lock()
        val activeAddr = if (connected) resolvedAddress else ""
        val activeModel = deviceModel
        val activeSerial = deviceSerial
        mu.unlock()
        if (activeAddr.isNotEmpty()) {
            discoveredByMac[activeAddr] = activeModel.ifEmpty { activeSerial }
        }

        // 1. Check connected GATT devices and bonded devices
        try {
            getBluetoothManager()?.getConnectedDevices(BluetoothProfile.GATT)?.forEach { dev ->
                val addr = dev.address ?: return@forEach
                val name = try { dev.name ?: "" } catch (_: Exception) { "" }
                if (looksLikeZebra(name, addr)) {
                    discoveredByMac[addr] = name
                }
            }
        } catch (_: Exception) {
        }

        try {
            adapter.bondedDevices?.forEach { dev ->
                val addr = dev.address ?: return@forEach
                val name = try { dev.name ?: "" } catch (_: Exception) { "" }
                if (looksLikeZebra(name, addr) && !discoveredByMac.containsKey(addr)) {
                    discoveredByMac[addr] = name
                }
            }
        } catch (_: Exception) {
        }

        // 2. Active BLE scan (5 seconds, matching zebra_ble_printer.scan)
        val scanner = adapter.bluetoothLeScanner
        if (scanner != null) {
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val dev = result.device ?: return
                    val addr = dev.address ?: return
                    val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                        ?: try { dev.name ?: "" } catch (_: Exception) { "" }
                    val uuids = result.scanRecord?.serviceUuids
                    if (looksLikeZebra(name, addr, uuids)) {
                        synchronized(discoveredByMac) {
                            if (!discoveredByMac.containsKey(addr) || discoveredByMac[addr].isNullOrEmpty()) {
                                discoveredByMac[addr] = name
                            }
                        }
                    }
                }
            }

            val settingsBuilder = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            if (Build.VERSION.SDK_INT >= 26) {
                settingsBuilder.setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                settingsBuilder.setLegacy(false)
            }

            try {
                scanner.startScan(null, settingsBuilder.build(), callback)
                Thread.sleep(4000)
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
            if (mac.equals(activeAddr, ignoreCase = true) && activeSerial.isNotEmpty()) {
                results.add(
                    PrinterInfo(
                        ip = bleTarget,
                        serial = activeSerial,
                        model = activeModel.ifEmpty { "Zebra BLE Printer" },
                        type = "zpl",
                        ready = true,
                        status = "online",
                        errors = PrinterErrors(unreachable = false)
                    )
                )
            } else {
                val serialGuess = if (advName.length in 6..24 && !advName.contains(" ")) advName else ""
                results.add(
                    PrinterInfo(
                        ip = bleTarget,
                        serial = serialGuess,
                        model = advName.ifEmpty { "Zebra BLE Printer" },
                        type = "zpl",
                        ready = true,
                        status = "online",
                        errors = PrinterErrors(unreachable = false)
                    )
                )
            }
        }
        return results
    }
}
