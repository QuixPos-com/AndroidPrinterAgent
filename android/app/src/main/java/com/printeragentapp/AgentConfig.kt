package com.printeragentapp

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject

data class PrinterErrors(
    val headOpen: Boolean = false,
    val pause: Boolean = false,
    val other: Boolean = false,
    val unreachable: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("headOpen", headOpen)
        put("pause", pause)
        put("other", other)
        put("unreachable", unreachable)
    }
}

data class PrinterInfo(
    val ip: String,
    val serial: String = "",
    val model: String = "",
    val type: String = "zpl",
    val labelLength: Int? = null,
    val errors: PrinterErrors = PrinterErrors(),
    val ready: Boolean = false,
    val status: String = "unpolled"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ip", ip)
        put("serial", serial)
        put("model", model)
        put("type", type)
        if (labelLength != null) {
            put("labelLength", labelLength)
        } else {
            put("labelLength", JSONObject.NULL)
        }
        put("errors", errors.toJson())
        put("ready", ready)
        put("status", status)
    }
}

data class JobReport(
    val id: String,
    val state: String,
    val detail: String = "",
    val updatedAt: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("state", state)
        if (detail.isNotEmpty()) put("detail", detail)
        if (updatedAt.isNotEmpty()) put("updatedAt", updatedAt)
    }
}

data class AgentConfigData(
    var token: String = "",
    var agentId: Int = 0,
    var agentName: String = "",
    var apiUrl: String = AgentConfigStore.DEFAULT_API_URL,
    var printerIp: String = "",
    var zplPrinters: List<String> = emptyList(),
    var port: String = "41783",
    var localAddr: String = "127.0.0.1:41783",
    var clientSerial: String = "",
    var clientModel: String = ""
) {
    fun endpoint(subpath: String): String {
        val domain = AgentConfigStore.cleanApiUrl(apiUrl)
        var sub = subpath.trim().removePrefix("/")
        sub = sub.removePrefix("api/agent/")
        sub = sub.removePrefix("api/agent")
        sub = sub.removePrefix("/")
        return if (sub.isEmpty()) {
            "$domain${AgentConfigStore.AGENT_BASE_PATH}"
        } else {
            "$domain${AgentConfigStore.AGENT_BASE_PATH}/$sub"
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("api_url", apiUrl)
        put("printer_ip", printerIp)
        put("zpl_printers", JSONArray(zplPrinters))
        put("port", port)
        put("token", token)
        put("local_addr", localAddr)
        put("name", agentName)
        put("agent_id", agentId)
    }
}

object AgentConfigStore {
    const val AGENT_VERSION = "1"
    const val AGENT_BASE_PATH = "/api/agent"
    const val DEFAULT_API_URL = "https://dash.quixpos.com"
    private const val PREFS_NAME = "ZebraAgentPrefs"

    fun cleanApiUrl(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.endsWith(AGENT_BASE_PATH)) {
            u = u.removeSuffix(AGENT_BASE_PATH).trimEnd('/')
        }
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
            u = if (u.startsWith("localhost") || u.startsWith("127.0.0.1") || u.startsWith("10.") || u.startsWith("192.168.")) {
                "http://$u"
            } else {
                "https://$u"
            }
        }
        return u
    }

    fun load(context: Context): AgentConfigData {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val rawApi = prefs.getString("ApiUrl", DEFAULT_API_URL) ?: DEFAULT_API_URL
        val apiUrl = cleanApiUrl(rawApi).ifEmpty { DEFAULT_API_URL }
        val token = (prefs.getString("Token", "") ?: "").trim()
        val name = (prefs.getString("Name", "") ?: "").trim()
        val agentId = prefs.getInt("AgentId", 0)
        val port = (prefs.getString("Port", "41783") ?: "41783").trim().ifEmpty { "41783" }
        val rawPrinters = prefs.getString("ZplPrinters", "") ?: ""
        var printerIp = (prefs.getString("PrinterIp", "") ?: "").trim()

        val zplPrinters = rawPrinters
            .split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() && PrinterProtocol.isValidPrinterTarget(it) }
            .toMutableList()

        if (zplPrinters.isEmpty() && printerIp.isNotEmpty() && PrinterProtocol.isValidPrinterTarget(printerIp)) {
            zplPrinters.add(printerIp)
        } else if (printerIp.isEmpty() && zplPrinters.isNotEmpty()) {
            printerIp = zplPrinters[0]
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "android-agent"
        } catch (_: Exception) {
            "android-agent"
        }
        val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        return AgentConfigData(
            token = token,
            agentId = agentId,
            agentName = name,
            apiUrl = apiUrl,
            printerIp = printerIp,
            zplPrinters = zplPrinters,
            port = port,
            localAddr = "127.0.0.1:$port",
            clientSerial = androidId,
            clientModel = model
        )
    }

    fun saveConfig(context: Context, apiUrl: String, zplPrinters: List<String>, port: String? = null) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cleanApi = cleanApiUrl(apiUrl).ifEmpty { DEFAULT_API_URL }
        val cleanList = zplPrinters.map { it.trim() }.filter { it.isNotEmpty() }
        val primaryIp = cleanList.firstOrNull() ?: ""
        prefs.edit().apply {
            putString("ApiUrl", cleanApi)
            putString("ZplPrinters", cleanList.joinToString(","))
            putString("PrinterIp", primaryIp)
            if (!port.isNullOrBlank()) {
                putString("Port", port.trim())
            }
            apply()
        }
    }

    fun saveApiUrl(context: Context, apiUrl: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("ApiUrl", cleanApiUrl(apiUrl)).apply()
    }

    fun savePrinterIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val clean = ip.trim()
        val existing = (prefs.getString("ZplPrinters", "") ?: "")
            .split(',', ';', '\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (clean.isNotEmpty() && !existing.contains(clean)) {
            existing.add(0, clean)
        }
        prefs.edit()
            .putString("PrinterIp", clean)
            .putString("ZplPrinters", existing.joinToString(","))
            .apply()
    }

    fun savePairing(context: Context, token: String, name: String, agentId: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("Token", token.trim())
            .putString("Name", name.trim())
            .putInt("AgentId", agentId)
            .apply()
    }
}
