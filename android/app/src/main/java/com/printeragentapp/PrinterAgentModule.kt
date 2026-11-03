package com.printeragentapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONArray
import org.json.JSONObject

class PrinterAgentModule(private val reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext), AgentEngine.AgentListener {

    private val engine = AgentEngine.getInstance(reactContext.applicationContext)

    init {
        engine.addListener(this)
    }

    override fun getName(): String = "PrinterAgentModule"

    override fun invalidate() {
        engine.removeListener(this)
        super.invalidate()
    }

    override fun onLog(line: String) {
        if (reactContext.hasActiveCatalystInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("agent-log", line)
        }
    }

    override fun onStateChanged(snapshot: JSONObject) {
        if (reactContext.hasActiveCatalystInstance()) {
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("agent-state", jsonToWritableMap(snapshot))
        }
    }

    override fun onEvent(type: String, data: Any?) {
        if (reactContext.hasActiveCatalystInstance()) {
            val payload = Arguments.createMap().apply {
                putString("type", type)
                when (data) {
                    is JSONObject -> putMap("data", jsonToWritableMap(data))
                    is JSONArray -> putArray("data", jsonToWritableArray(data))
                    is String -> putString("data", data)
                    is Boolean -> putBoolean("data", data)
                    is Int -> putInt("data", data)
                    is Double -> putDouble("data", data)
                }
            }
            reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                .emit("agent-event", payload)
        }
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for NativeEventEmitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for NativeEventEmitter
    }

    @ReactMethod
    fun requestNotificationPermission(promise: Promise) {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        val activity = reactContext.currentActivity
        if (needed.isNotEmpty() && activity != null) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), 1001)
            promise.resolve(false)
        } else {
            promise.resolve(needed.isEmpty())
        }
    }

    @ReactMethod
    fun requestBluetoothPermissions(promise: Promise) {
        val needed = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            if (ContextCompat.checkSelfPermission(reactContext, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        val activity = reactContext.currentActivity
        if (needed.isNotEmpty() && activity != null) {
            ActivityCompat.requestPermissions(activity, needed.toTypedArray(), 1002)
            promise.resolve(false)
        } else {
            promise.resolve(needed.isEmpty())
        }
    }

    @ReactMethod
    fun startForegroundService(promise: Promise) {
        try {
            PrinterForegroundService.startService(reactContext.applicationContext)
            promise.resolve("Started foreground notification service")
        } catch (e: Exception) {
            promise.reject("START_SERVICE_ERR", e.message, e)
        }
    }

    @ReactMethod
    fun stopForegroundService(promise: Promise) {
        try {
            PrinterForegroundService.stopService(reactContext.applicationContext)
            promise.resolve("Stopped foreground notification service")
        } catch (e: Exception) {
            promise.reject("STOP_SERVICE_ERR", e.message, e)
        }
    }

    @ReactMethod
    fun getServiceStatus(promise: Promise) {
        val map = Arguments.createMap().apply {
            putBoolean("running", engine.isRunning())
            putBoolean("background", PrinterForegroundService.isForegroundRunning)
        }
        promise.resolve(map)
    }

    @ReactMethod
    fun getConfig(promise: Promise) {
        Thread {
            try {
                val cfg = AgentConfigStore.load(reactContext.applicationContext)
                promise.resolve(jsonToWritableMap(cfg.toJson()))
            } catch (e: Exception) {
                promise.reject("GET_CONFIG_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun saveConfig(apiUrl: String, zplPrinters: ReadableArray, port: String?, promise: Promise) {
        Thread {
            try {
                val printersList = ArrayList<String>()
                for (i in 0 until zplPrinters.size()) {
                    val item = zplPrinters.getString(i)?.trim() ?: ""
                    if (item.isNotEmpty()) {
                        printersList.add(item)
                    }
                }
                engine.saveAndReloadConfig(apiUrl, printersList, port)
                promise.resolve(jsonToWritableMap(engine.getSnapshot()))
            } catch (e: Exception) {
                promise.reject("SAVE_CONFIG_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun pairAgent(pin: String, apiUrl: String?, promise: Promise) {
        Thread {
            try {
                val res = engine.pair(pin, apiUrl)
                promise.resolve(jsonToWritableMap(res))
            } catch (e: Exception) {
                promise.reject("PAIR_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun unpairAgent(promise: Promise) {
        Thread {
            try {
                engine.unpair()
                promise.resolve(true)
            } catch (e: Exception) {
                promise.reject("UNPAIR_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun scanLocalPrinters(promise: Promise) {
        Thread {
            try {
                val printers = engine.scanPrinters()
                val arr = Arguments.createArray()
                for (p in printers) {
                    arr.pushMap(jsonToWritableMap(p.toJson()))
                }
                promise.resolve(arr)
            } catch (e: Exception) {
                promise.reject("SCAN_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun getSnapshot(promise: Promise) {
        Thread {
            try {
                promise.resolve(jsonToWritableMap(engine.getSnapshot()))
            } catch (e: Exception) {
                promise.reject("SNAP_ERR", e.message, e)
            }
        }.start()
    }

    @ReactMethod
    fun getLogs(promise: Promise) {
        val arr = Arguments.createArray()
        for (line in engine.getLogs()) {
            arr.pushString(line)
        }
        promise.resolve(arr)
    }

    @ReactMethod
    fun clearLogs(promise: Promise) {
        engine.clearLogs()
        promise.resolve(true)
    }

    @ReactMethod
    fun sendTestPrint(zpl: String, promise: Promise) {
        Thread {
            try {
                val jobId = "test-${System.currentTimeMillis() / 1000}"
                engine.enqueueJob(PrintJob(id = jobId, zpl = zpl))
                promise.resolve(jobId)
            } catch (e: Exception) {
                promise.reject("PRINT_ERR", e.message, e)
            }
        }.start()
    }

    private fun jsonToWritableMap(jsonObject: JSONObject): WritableMap {
        val map = Arguments.createMap()
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = jsonObject.opt(key)
            when (value) {
                null, JSONObject.NULL -> map.putNull(key)
                is Boolean -> map.putBoolean(key, value)
                is Int -> map.putInt(key, value)
                is Long -> map.putDouble(key, value.toDouble())
                is Double -> map.putDouble(key, value)
                is String -> map.putString(key, value)
                is JSONObject -> map.putMap(key, jsonToWritableMap(value))
                is JSONArray -> map.putArray(key, jsonToWritableArray(value))
                else -> map.putString(key, value.toString())
            }
        }
        return map
    }

    private fun jsonToWritableArray(jsonArray: JSONArray): WritableArray {
        val array = Arguments.createArray()
        for (i in 0 until jsonArray.length()) {
            val value = jsonArray.opt(i)
            when (value) {
                null, JSONObject.NULL -> array.pushNull()
                is Boolean -> array.pushBoolean(value)
                is Int -> array.pushInt(value)
                is Long -> array.pushDouble(value.toDouble())
                is Double -> array.pushDouble(value)
                is String -> array.pushString(value)
                is JSONObject -> array.pushMap(jsonToWritableMap(value))
                is JSONArray -> array.pushArray(jsonToWritableArray(value))
                else -> array.pushString(value.toString())
            }
        }
        return array
    }
}

