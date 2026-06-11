package com.scrcpycast.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class WifiEntry(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val capabilities: String,
    val isSaved: Boolean,
    val isValid: Boolean = true
)

data class WifiCredential(
    val ssid: String,
    val password: String,
    val lastResult: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class WifiNetworkManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiNetworkManager"
        private const val PREFS_NAME = "scrcpycast_wifi"
        private const val KEY_SSID = "saved_ssid"
        private const val KEY_PASSWORD = "saved_password"
        private const val KEY_ALL_CREDENTIALS = "all_credentials"
        private const val SCAN_TIMEOUT_MS = 15_000L
        private const val MAX_CREDENTIALS = 20
        private const val RETRY_INTERVAL_MS = 30_000L
        private const val MAX_RETRIES = 5

        private const val AES_ALGORITHM = "AES/CBC/PKCS5Padding"
        private var encryptionKey: ByteArray? = null

        @Synchronized
        private fun getEncryptionKey(context: Context): ByteArray {
            if (encryptionKey == null) {
                val deviceId = Settings.Secure.getString(
                    context.contentResolver, Settings.Secure.ANDROID_ID
                ) ?: Build.MODEL
                val digest = MessageDigest.getInstance("SHA-256")
                encryptionKey = digest.digest(deviceId.toByteArray())
                    .copyOf(16)
            }
            return encryptionKey!!
        }

        fun encrypt(context: Context, plainText: String): String {
            if (plainText.isEmpty()) return ""
            return try {
                val key = getEncryptionKey(context)
                val cipher = Cipher.getInstance(AES_ALGORITHM)
                val iv = ByteArray(16).also { "ScrcpyCastSalt!1".toByteArray().copyInto(it, 0, 0, minOf(16, it.size)) }
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Encrypt failed", e)
                plainText
            }
        }

        fun decrypt(context: Context, encryptedText: String): String {
            if (encryptedText.isEmpty()) return ""
            return try {
                val key = getEncryptionKey(context)
                val cipher = Cipher.getInstance(AES_ALGORITHM)
                val iv = ByteArray(16).also { "ScrcpyCastSalt!1".toByteArray().copyInto(it, 0, 0, minOf(16, it.size)) }
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                val decrypted = cipher.doFinal(Base64.decode(encryptedText, Base64.NO_WRAP))
                String(decrypted, Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "Decrypt failed", e)
                encryptedText
            }
        }
    }

    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        migrateIfNeeded()
    }

    private fun migrateIfNeeded() {
        if (prefs.getBoolean("_encrypted_v1", false)) return
        try {
            val allJson = prefs.getString(KEY_ALL_CREDENTIALS, null)
            val savedPwd = prefs.getString(KEY_PASSWORD, null)
            if (allJson != null) {
                val encrypted = allJson.split("||").joinToString("||") { entry ->
                    val parts = entry.split("|")
                    if (parts.size >= 3 && parts[1].isNotEmpty() && !looksEncrypted(parts[1])) {
                        "${parts[0]}|${encrypt(context, parts[1])}|${parts.drop(2).joinToString("|")}"
                    } else entry
                }
                prefs.edit().putString(KEY_ALL_CREDENTIALS, encrypted).apply()
            }
            if (savedPwd != null && savedPwd.isNotEmpty() && !looksEncrypted(savedPwd)) {
                prefs.edit().putString(KEY_PASSWORD, encrypt(context, savedPwd)).apply()
            }
            prefs.edit().putBoolean("_encrypted_v1", true).apply()
            Log.d(TAG, "Credential encryption migration completed")
        } catch (e: Exception) {
            Log.e(TAG, "Credential migration failed", e)
        }
    }

    private fun looksEncrypted(s: String): Boolean {
        return try {
            val decoded = Base64.decode(s, Base64.NO_WRAP)
            decoded.size >= 16
        } catch (_: Exception) { false }
    }

    private var scanReceiver: BroadcastReceiver? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiConnectCallback: ((Boolean, String) -> Unit)? = null
    private var currentRetryCount = 0

    val savedSsid: String get() = prefs.getString(KEY_SSID, "") ?: ""
    val savedPassword: String get() = decrypt(context, prefs.getString(KEY_PASSWORD, "") ?: "")
    val hasCredentials: Boolean get() = savedSsid.isNotEmpty() && savedPassword.isNotEmpty()

    val allCredentials: List<WifiCredential>
        get() {
            val json = prefs.getString(KEY_ALL_CREDENTIALS, null) ?: return emptyList()
            return try {
                json.split("||").mapNotNull { entry ->
                    val parts = entry.split("|")
                    if (parts.size >= 3) {
                        WifiCredential(
                            ssid = parts[0],
                            password = decrypt(context, parts[1]),
                            lastResult = parts[2].toBoolean(),
                            timestamp = parts.getOrElse(3) { "0" }.toLong()
                        )
                    } else null
                }
            } catch (_: Exception) { emptyList() }
        }

    val validCredentials: List<WifiCredential>
        get() = allCredentials.filter { it.lastResult }

    val invalidCredentials: List<WifiCredential>
        get() = allCredentials.filter { !it.lastResult }

    val currentSsid: String
        get() {
            val info = wifiManager.connectionInfo
            return info?.ssid?.removeSurrounding("\"") ?: ""
        }

    val isWifiConnected: Boolean
        get() {
            val network = connectivityManager.activeNetwork ?: return false
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

    fun saveCredentials(ssid: String, password: String, success: Boolean = true) {
        val all = allCredentials.toMutableList()
        all.removeAll { it.ssid == ssid }
        all.add(0, WifiCredential(ssid, password, success))
        if (all.size > MAX_CREDENTIALS) {
            all.removeAt(all.lastIndex)
        }
        val json = all.joinToString("||") { cred ->
            val encPwd = encrypt(context, cred.password)
            "${cred.ssid}|${encPwd}|${cred.lastResult}|${cred.timestamp}"
        }
        prefs.edit().putString(KEY_ALL_CREDENTIALS, json).apply()

        if (success) {
            prefs.edit()
                .putString(KEY_SSID, ssid)
                .putString(KEY_PASSWORD, encrypt(context, password))
                .apply()
            Log.d(TAG, "Saved valid credentials for $ssid")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                suggestNetwork(ssid, password)
            }
        } else {
            Log.d(TAG, "Saved invalid credentials for $ssid (kept for retry)")
        }
    }

    fun clearCredentials(ssid: String? = null) {
        if (ssid != null) {
            val all = allCredentials.toMutableList()
            all.removeAll { it.ssid == ssid }
            val json = all.joinToString("||") { "${it.ssid}|${it.password}|${it.lastResult}|${it.timestamp}" }
            prefs.edit().putString(KEY_ALL_CREDENTIALS, json).apply()
            Log.d(TAG, "Cleared credentials for $ssid")
        } else {
            prefs.edit().remove(KEY_SSID).remove(KEY_PASSWORD).remove(KEY_ALL_CREDENTIALS).apply()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wifiManager.removeNetworkSuggestions(emptyList())
            }
            Log.d(TAG, "Cleared all credentials")
        }
    }

    private fun suggestNetwork(ssid: String, password: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val suggestion = WifiNetworkSuggestion.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            wifiManager.removeNetworkSuggestions(emptyList())
            val status = wifiManager.addNetworkSuggestions(listOf(suggestion))
            Log.d(TAG, "WifiNetworkSuggestion status=$status for $ssid")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add network suggestion", e)
        }
    }

    fun suggestAllValidNetworks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val valid = validCredentials
        if (valid.isEmpty()) return
        try {
            val suggestions = valid.map { cred ->
                WifiNetworkSuggestion.Builder()
                    .setSsid(cred.ssid)
                    .setWpa2Passphrase(cred.password)
                    .build()
            }
            wifiManager.removeNetworkSuggestions(emptyList())
            wifiManager.addNetworkSuggestions(suggestions)
            Log.d(TAG, "Added ${suggestions.size} network suggestions")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add network suggestions", e)
        }
    }

    suspend fun scanNetworks(): List<WifiEntry> = withContext(Dispatchers.Default) {
        val deferred = CompletableDeferred<List<WifiEntry>>()
        val savedSet = allCredentials.map { it.ssid }.toSet()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                    val results = wifiManager.scanResults ?: emptyList()
                    val entries = results
                        .filter { it.SSID.isNotBlank() }
                        .distinctBy { it.SSID }
                        .sortedByDescending { it.level }
                        .map { r ->
                            val rawSsid = r.SSID.removeSurrounding("\"")
                            val saved = rawSsid in savedSet
                            val valid = allCredentials.firstOrNull { it.ssid == rawSsid }?.lastResult != false
                            WifiEntry(
                                ssid = rawSsid,
                                bssid = r.BSSID,
                                level = WifiManager.calculateSignalLevel(r.level, 4),
                                capabilities = r.capabilities,
                                isSaved = saved,
                                isValid = valid
                            )
                        }
                    deferred.complete(entries)
                }
            }
        }

        scanReceiver = receiver
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        wifiManager.startScan()

        try {
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { deferred.await() } ?: emptyList()
        } finally {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
            scanReceiver = null
        }
    }

    fun connectToNetwork(ssid: String, password: String, callback: (Boolean, String) -> Unit) {
        saveCredentials(ssid, password, false)
        currentRetryCount = 0
        doConnect(ssid, password, callback)
    }

    private fun doConnect(ssid: String, password: String, callback: (Boolean, String) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectWithSpecifier(ssid, password, callback)
        } else {
            connectLegacy(ssid, password, callback)
        }
    }

    private fun connectWithSpecifier(ssid: String, password: String, callback: (Boolean, String) -> Unit) {
        wifiConnectCallback = object : (Boolean, String) -> Unit {
            override fun invoke(success: Boolean, msg: String) {
                if (success) {
                    saveCredentials(ssid, password, true)
                    callback(true, msg)
                } else if (currentRetryCount < MAX_RETRIES) {
                    currentRetryCount++
                    Log.d(TAG, "Connection failed, retry $currentRetryCount/$MAX_RETRIES for $ssid")
                    Thread.sleep(RETRY_INTERVAL_MS)
                    doConnect(ssid, password, callback)
                } else {
                    Log.e(TAG, "Max retries reached for $ssid, keeping invalid credential")
                    saveCredentials(ssid, password, false)
                    callback(false, msg)
                }
            }
        }

        val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier)
            .build()

        connectivityCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WiFi connected to $ssid")
                wifiConnectCallback?.invoke(true, "已连接到 $ssid")
                wifiConnectCallback = null
            }

            override fun onUnavailable() {
                Log.e(TAG, "WiFi connection unavailable for $ssid")
                wifiConnectCallback?.invoke(false, "无法连接到 $ssid")
                wifiConnectCallback = null
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "WiFi lost: $ssid")
            }
        }

        connectivityManager.requestNetwork(request, connectivityCallback!!)
    }

    private fun connectLegacy(ssid: String, password: String, callback: (Boolean, String) -> Unit) {
        try {
            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            val netId = wifiManager.addNetwork(config)
            if (netId == -1) {
                saveCredentials(ssid, password, false)
                callback(false, "添加网络配置失败")
                return
            }
            wifiManager.disconnect()
            wifiManager.enableNetwork(netId, true)
            wifiManager.reconnect()
            saveCredentials(ssid, password, true)
            callback(true, "已连接到 $ssid")
        } catch (e: Exception) {
            Log.e(TAG, "Legacy WiFi connect failed", e)
            saveCredentials(ssid, password, false)
            callback(false, "连接失败: ${e.message}")
        }
    }

    fun autoConnectIfNeeded(): Boolean {
        if (!wifiManager.isWifiEnabled) {
            try {
                wifiManager.isWifiEnabled = true
                Log.d(TAG, "Enabling WiFi")
                Thread.sleep(3000)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot enable WiFi (no hardware or permission): ${e.message}")
            }
        }
        if (isWifiConnected) {
            Log.d(TAG, "WiFi already connected to $currentSsid")
            return true
        }
        if (!hasCredentials && validCredentials.isEmpty()) {
            Log.d(TAG, "No saved credentials, skip auto-connect")
            return false
        }

        // Suggest all valid networks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            suggestAllValidNetworks()
        }

        // Try the primary saved network first
        val primary = if (hasCredentials) savedSsid to savedPassword else null
        if (primary != null) {
            Log.d(TAG, "Auto-connecting to primary: ${primary.first}")
            connectToNetwork(primary.first, primary.second) { success, msg ->
                Log.d(TAG, "Primary connect result: $success, $msg")
            }
        }

        // Also try other valid credentials after a delay
        val others = validCredentials.filter { it.ssid != primary?.first }
        if (others.isNotEmpty()) {
            Thread {
                Thread.sleep(15000)
                for (cred in others.take(3)) {
                    if (isWifiConnected) break
                    Log.d(TAG, "Trying alternate: ${cred.ssid}")
                    connectToNetwork(cred.ssid, cred.password) { _, _ -> }
                    Thread.sleep(10000)
                }
                // Try invalid credentials too as last resort
                if (!isWifiConnected) {
                    for (cred in invalidCredentials.take(3)) {
                        if (isWifiConnected) break
                        Log.d(TAG, "Trying previously invalid: ${cred.ssid}")
                        connectToNetwork(cred.ssid, cred.password) { _, _ -> }
                        Thread.sleep(10000)
                    }
                }
            }.start()
        }

        return true
    }

    fun periodicScan(intervalMs: Long = 60_000L) {
        Thread {
            while (true) {
                try {
                    if (!isWifiConnected && hasCredentials) {
                        Log.d(TAG, "Periodic scan: not connected, triggering auto-connect")
                        autoConnectIfNeeded()
                    }
                    Thread.sleep(intervalMs)
                } catch (_: Exception) { break }
            }
        }.start()
    }

    fun release() {
        try { scanReceiver?.let { context.unregisterReceiver(it) } } catch (_: Exception) {}
        try { connectivityCallback?.let { connectivityManager.unregisterNetworkCallback(it) } } catch (_: Exception) {}
        scanReceiver = null
        connectivityCallback = null
        wifiConnectCallback = null
    }
}
