package com.scrcpycast.receiver

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import com.scrcpycast.receiver.databinding.ActivityClientBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private lateinit var wifiManager: WifiNetworkManager
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var surfaceRef: Surface? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wm: WifiManager? = null
    private lateinit var stateListener: (ClientManager.State) -> Unit

    private val clientManager = ClientManager.instance

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        wifiManager = WifiNetworkManager(this)
        wm = getSystemService(WifiManager::class.java)
        wifiLock = wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ScrcpyCast")
        wifiLock?.acquire()
        BootJobService.markStarted()
        BootJobService.scheduleNextBoot(this)
        BootAlarmReceiver.scheduleBootAlarm(this)
        ensureServiceRunning()
        setupUi()
        autoConnectWifi()
        wifiManager.periodicScan()

        // 开机自启 Wi-Fi 热点
        scope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(5000)
            try {
                Runtime.getRuntime().exec(arrayOf("su", "0", "sh", "/data/local/tmp/start_softap.sh"))
            } catch (_: Exception) {}
        }

        // AP 热点模式下 ConnectivityManager 不触发回调，需要主动启动
        scope.launch {
            delay(1000)
            clientManager.refreshIp()
            val ip = clientManager.getState().localIp
            if (ip.isNotEmpty() && !clientManager.getState().isListening) {
                clientManager.startListening()
                clientManager.registerMdns(this@ClientActivity)
            }
        }
    }

    private fun ensureServiceRunning() {
        if (BootService.instance == null) {
            val intent = Intent(this, BootService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    private fun autoConnectWifi() {
        scope.launch {
            kotlinx.coroutines.delay(2000)
            wifiManager.autoConnectIfNeeded()
        }
    }

    private fun setupUi() {
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.root.requestFocus()

        binding.tvDisplay.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceRef?.release()
                surfaceRef = holder.surface
                clientManager.displaySurface = surfaceRef
                if (clientManager.getState().isConnected) {
                    clientManager.reconfigureDecoder()
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceRef?.release()
                surfaceRef = null
                clientManager.displaySurface = null
            }
        })

        // Ensure SurfaceView is drawn behind overlays (not covered by system UI)
        binding.tvDisplay.setZOrderMediaOverlay(false)

        stateListener = { state ->
            runOnUiThread {
                binding.tvStatus.text = state.status
                binding.tvFps.text = if (state.fps > 0) String.format("%.1f fps", state.fps) else ""
                if (state.isConnected) {
                    binding.tvIp.visibility = android.view.View.GONE
                } else {
                    binding.tvIp.visibility = android.view.View.VISIBLE
                    binding.tvIp.text = "等待投屏连接\n${state.localIp}:${state.port}\n\nWiFi: Axiliu_AP\n密码: 12345678"
                }
                binding.vCover.visibility = if (state.isConnected) android.view.View.GONE else android.view.View.VISIBLE
            }
        }
        clientManager.addListener(stateListener)
        clientManager.startIpRefresh()

        binding.btnWifi.visibility = android.view.View.GONE

        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { onNetworkChanged() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { onNetworkChanged() }
            override fun onLost(network: Network) { onNetworkChanged() }
        }
        networkCallback = callback
        cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
    }

    private var hadValidIp = false

    private fun onNetworkChanged() {
        try { if (wm?.isWifiEnabled == false) wm?.isWifiEnabled = true } catch (_: Exception) {}
        clientManager.refreshIp()
        val ip = clientManager.getState().localIp
        val isValid = ip.isNotEmpty()
        if (!hadValidIp && isValid) {
            hadValidIp = true
            clientManager.restartDiscovery()
        }
        if (isValid && !clientManager.getState().isListening && ClientManager.isActive) {
            clientManager.startListening()
            clientManager.registerMdns(this@ClientActivity)
        }
    }

    override fun onDestroy() {
        clientManager.removeListener(stateListener)
        clientManager.stopIpRefresh()
        scope.cancel()
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        wifiManager.release()
        surfaceRef?.release()
        surfaceRef = null
        wifiLock?.release()
        wifiLock = null
        super.onDestroy()
    }
}
