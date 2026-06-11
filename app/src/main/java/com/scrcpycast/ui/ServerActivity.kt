package com.scrcpycast.ui

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.text.TextUtils
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.scrcpycast.R
import com.scrcpycast.databinding.ActivityServerBinding
import com.scrcpycast.server.ServerManager
import com.scrcpycast.protocol.Protocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class ServerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityServerBinding
    private val serverManager = ServerManager
    private val prefs by lazy { getSharedPreferences("scrcpycast", Context.MODE_PRIVATE) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var discoveryJob: Job? = null
    private var beaconListenerJob: Job? = null
    private var uiUpdaterJob: Job? = null
    private val discoveredIps = mutableSetOf<String>()
    private val discoveredChannel = Channel<String>(Channel.UNLIMITED)
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryActive = false
    private var nsdScrcpycastListener: NsdManager.DiscoveryListener? = null
    private var nsdLelinkListener: NsdManager.DiscoveryListener? = null

    data class CastSettings(
        val width: Int = 1920,
        val height: Int = 1080,
        val bitRate: Int = 5_000_000,
        val frameRate: Int = 30
    )

    private var castSettings = CastSettings()
    private var pendingScreenCaptureResult: Intent? = null

    private fun loadSettings() {
        castSettings = CastSettings(
            width = prefs.getInt("cast_width", 1920),
            height = prefs.getInt("cast_height", 1080),
            bitRate = prefs.getInt("cast_bitrate", 5_000_000),
            frameRate = prefs.getInt("cast_framerate", 30)
        )
    }

    private fun saveSettings(settings: CastSettings) {
        castSettings = settings
        prefs.edit().apply {
            putInt("cast_width", settings.width)
            putInt("cast_height", settings.height)
            putInt("cast_bitrate", settings.bitRate)
            putInt("cast_framerate", settings.frameRate)
            apply()
        }
    }

    private fun showSettingsDialog() {
        val inflater = layoutInflater
        val view = inflater.inflate(R.layout.dialog_settings, null)
        val resSpinner = view.findViewById<android.widget.Spinner>(R.id.spinner_resolution)
        val brSpinner = view.findViewById<android.widget.Spinner>(R.id.spinner_bitrate)
        val frSpinner = view.findViewById<android.widget.Spinner>(R.id.spinner_framerate)

        val resolutions = arrayOf("1920x1080", "1280x720", "854x480")
        val bitrateLabels = arrayOf("1 Mbps", "2 Mbps", "5 Mbps", "8 Mbps", "10 Mbps", "15 Mbps", "20 Mbps")
        val bitrateValues = intArrayOf(1_000_000, 2_000_000, 5_000_000, 8_000_000, 10_000_000, 15_000_000, 20_000_000)
        val framerates = arrayOf("15", "30", "60")

        resSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, resolutions)
        brSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bitrateLabels)
        frSpinner.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, framerates)

        resSpinner.setSelection(resolutions.indexOfFirst { it == "${castSettings.width}x${castSettings.height}" }.coerceAtLeast(0))
        val brIdx = bitrateValues.indexOfFirst { it == castSettings.bitRate }.coerceAtLeast(2)
        brSpinner.setSelection(brIdx)
        frSpinner.setSelection(framerates.indexOfFirst { it == castSettings.frameRate.toString() }.coerceAtLeast(1))

        MaterialAlertDialogBuilder(this)
            .setTitle("画质设置")
            .setView(view)
            .setPositiveButton("确定") { _, _ ->
                val res = resolutions[resSpinner.selectedItemPosition].split("x")
                val br = bitrateValues[brSpinner.selectedItemPosition]
                val fr = framerates[frSpinner.selectedItemPosition].toInt()
                saveSettings(CastSettings(res[0].toInt(), res[1].toInt(), br, fr))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    companion object {
        private const val DISCOVERY_PORT = 37281
        private const val BEACON_PORT = 37282
        private val DISCOVER_REQ = "SCRPYCAST_DISCOVER".toByteArray()
        private const val BEACON_PREFIX = "SCRPYCAST_BEACON:"
        private const val REQUEST_SCREEN_CAPTURE = 2001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityServerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etReceiverIp.setText(prefs.getString("last_ip", ""))
        binding.etPort.setText(prefs.getInt("last_port", Protocol.DEFAULT_PORT).toString())
        binding.tvLocalIp.text = "本机: ${getLocalIpAddress()}"

        loadSettings()

        serverManager.addListener { state ->
            runOnUiThread {
                binding.tvStatus.text = state.status
                binding.tvFps.text = if (state.fps > 0) String.format("%.1f fps", state.fps) else ""
                binding.btnToggle.text = if (state.isRunning) "停止投屏" else "开始投屏"
                binding.etReceiverIp.isEnabled = !state.isRunning
                binding.etPort.isEnabled = !state.isRunning
                binding.btnDiscover.isEnabled = !state.isRunning
            }
        }

        binding.btnToggle.setOnClickListener {
            if (serverManager.getState().isRunning) {
                serverManager.stop()
            } else {
                // If no screen capture permission yet, request it first
                if (MainActivity.pendingResultData == null && pendingScreenCaptureResult == null) {
                    requestScreenCapture()
                    return@setOnClickListener
                }
                val ip = binding.etReceiverIp.text.toString().trim()
                if (TextUtils.isEmpty(ip)) {
                    binding.etReceiverIp.error = "请输入接收端IP"
                    return@setOnClickListener
                }
                val portStr = binding.etPort.text.toString().trim()
                val port = portStr.toIntOrNull() ?: Protocol.DEFAULT_PORT
                android.util.Log.d("ServerActivity", "Start button: ip=$ip port=$port")
                prefs.edit().putString("last_ip", ip).putInt("last_port", port).apply()
                val resultData = MainActivity.pendingResultData ?: pendingScreenCaptureResult
                if (resultData == null) {
                    binding.tvStatus.text = "错误：未获取屏幕录制权限"
                    return@setOnClickListener
                }
                MainActivity.pendingResultData = resultData
                serverManager.start(this, ip, port, ServerManager.CastConfig(
                    width = castSettings.width,
                    height = castSettings.height,
                    bitRate = castSettings.bitRate,
                    frameRate = castSettings.frameRate
                ))
            }
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        binding.btnBack.setOnClickListener {
            serverManager.stop()
            finish()
        }

        binding.btnDiscover.setOnClickListener {
            manualDiscover()
        }
    }

    override fun onResume() {
        super.onResume()
        startAutoDiscovery()
        startMdnsDiscovery()
    }

    override fun onPause() {
        super.onPause()
        stopAutoDiscovery()
        stopMdnsDiscovery()
    }

    private fun startAutoDiscovery() {
        android.util.Log.d("ServerActivity", "startAutoDiscovery")
        stopAutoDiscovery()
        discoveredIps.clear()
        binding.deviceList.removeAllViews()
        binding.tvLocalIp.text = "本机: ${getLocalIpAddress()}"

        uiUpdaterJob = scope.launch(Dispatchers.Main) {
            discoveredChannel.consumeEach { ip ->
                android.util.Log.d("ServerActivity", "Discovered: $ip")
                if (discoveredIps.add(ip)) {
                    updateDeviceList()
                    binding.etReceiverIp.setText(ip)
                    binding.tvStatus.text = "已自动发现接收端: $ip"
                }
            }
        }

        beaconListenerJob = scope.launch {
            var beaconSocket: DatagramSocket? = null
            try {
                beaconSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(BEACON_PORT))
                    broadcast = true
                    soTimeout = 2000
                }
                android.util.Log.d("ServerActivity", "Beacon listener started on $BEACON_PORT")
                val buf = ByteArray(256)
                while (isActive) {
                    try {
                        val pkt = DatagramPacket(buf, buf.size)
                        beaconSocket.receive(pkt)
                        val msg = String(pkt.data, 0, pkt.length)
                        if (msg.startsWith(BEACON_PREFIX)) {
                            val ip = msg.removePrefix(BEACON_PREFIX).trim()
                            if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                discoveredChannel.send(ip)
                            }
                        }
                    } catch (_: SocketTimeoutException) {}
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    android.util.Log.e("ServerActivity", "Beacon listener failed: ${e.message}")
                }
            } finally {
                beaconSocket?.close()
                android.util.Log.d("ServerActivity", "Beacon listener socket closed")
            }
        }

        discoveryJob = scope.launch {
            while (isActive) {
                scanForReceivers()
                delay(3000)
            }
        }
    }

    private fun stopAutoDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        beaconListenerJob?.cancel()
        beaconListenerJob = null
        uiUpdaterJob?.cancel()
        uiUpdaterJob = null
    }

    private suspend fun scanForReceivers() {
        try {
            val socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = 1500

            val bAddr1 = getSubnetBroadcast()
            val bAddr2 = InetAddress.getByName("255.255.255.255")

            val targets = mutableListOf(bAddr2)
            if (bAddr1 != null && bAddr1 != bAddr2) targets.add(bAddr1)

            for (addr in targets) {
                val req = DatagramPacket(DISCOVER_REQ, DISCOVER_REQ.size, addr, DISCOVERY_PORT)
                try { socket.send(req) } catch (_: Exception) {}
            }

            val buf = ByteArray(256)
            while (true) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = String(pkt.data, 0, pkt.length)
                    if (ip.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                        discoveredChannel.send(ip)
                    }
                } catch (_: SocketTimeoutException) { break }
            }
            socket.close()
        } catch (_: Exception) {}
    }

    private fun manualDiscover() {
        binding.btnDiscover.isEnabled = false
        binding.tvStatus.text = "正在搜索接收端..."

        scope.launch {
            scanForReceivers()
            withContext(Dispatchers.Main) {
                binding.btnDiscover.isEnabled = true
                if (discoveredIps.isEmpty()) {
                    binding.tvStatus.text = "未发现接收端，请手动输入IP"
                    Toast.makeText(this@ServerActivity, "未发现接收端", Toast.LENGTH_SHORT).show()
                } else {
                    binding.tvStatus.text = "已发现 ${discoveredIps.size} 个接收端"
                    Toast.makeText(this@ServerActivity, "发现 ${discoveredIps.size} 个接收端", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateDeviceList() {
        binding.deviceList.removeAllViews()
        for (ip in discoveredIps) {
            val chip = Chip(this).apply {
                text = ip
                isCheckable = true
                isChecked = ip == binding.etReceiverIp.text.toString().trim()
                setOnClickListener {
                    binding.etReceiverIp.setText(ip)
                    binding.tvStatus.text = "已选择接收端: $ip"
                }
                setOnCloseIconClickListener {
                    discoveredIps.remove(ip)
                    binding.deviceList.removeView(this)
                }
            }
            binding.deviceList.addView(chip)
        }
    }

    private fun startMdnsDiscovery() {
        if (nsdDiscoveryActive) return
        nsdDiscoveryActive = true
        nsdManager = (getSystemService(Context.NSD_SERVICE) as NsdManager).also { nsd ->
            val discoveryListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    android.util.Log.e("ServerActivity", "mDNS discovery start failed: $errorCode")
                    nsdDiscoveryActive = false
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {
                    android.util.Log.d("ServerActivity", "mDNS discovery started for $serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                val port = serviceInfo.port
                                if (port > 0 && port != Protocol.DEFAULT_PORT) {
                                    // Found lelink/UGREEN dongle, auto-set port
                                    binding.etPort.setText(port.toString())
                                }
                                scope.launch { discoveredChannel.send(host) }
                                android.util.Log.d("ServerActivity", "mDNS resolved: $host:$port (${serviceInfo.serviceType})")
                            }
                        }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            nsdScrcpycastListener = discoveryListener
            nsd.discoverServices("_scrcpycast._tcp.", NsdManager.PROTOCOL_DNS_SD, discoveryListener)

            // Separate listener for lelink (UGREEN/乐播) dongles — same listener can't be reused
            val lelinkListener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    android.util.Log.e("ServerActivity", "lelink mDNS start failed: $errorCode")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String) {
                    android.util.Log.d("ServerActivity", "lelink mDNS discovery started for $serviceType")
                }
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            android.util.Log.d("ServerActivity", "lelink resolve failed: $errorCode")
                        }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            val host = serviceInfo.host?.hostAddress ?: return
                            if (host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+"))) {
                                val port = serviceInfo.port
                                android.util.Log.d("ServerActivity", "lelink mDNS resolved: $host:$port")
                                if (port > 0) {
                                    binding.etPort.setText(port.toString())
                                }
                                scope.launch { discoveredChannel.send(host) }
                            }
                        }
                    })
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            }
            nsdLelinkListener = lelinkListener
            try {
                nsd.discoverServices("_leboremote._tcp.", NsdManager.PROTOCOL_DNS_SD, lelinkListener)
                android.util.Log.d("ServerActivity", "lelink mDNS discovery started")
            } catch (e: Exception) {
                android.util.Log.d("ServerActivity", "lelink mDNS not available: ${e.message}")
            }
        }
        android.util.Log.d("ServerActivity", "mDNS discovery started")
    }

    private fun stopMdnsDiscovery() {
        nsdDiscoveryActive = false
        val nsd = nsdManager ?: return
        nsdScrcpycastListener?.let { listener ->
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        nsdLelinkListener?.let { listener ->
            try { nsd.stopServiceDiscovery(listener) } catch (_: Exception) {}
        }
        nsdScrcpycastListener = null
        nsdLelinkListener = null
        nsdManager = null
    }

    private fun getSubnetBroadcast(): InetAddress? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                for (addr in intf.interfaceAddresses) {
                    val broadcast = addr.broadcast ?: continue
                    return broadcast
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress
                    if (addr is InetAddress && !addr.isLoopbackAddress && host != null && host.contains('.')) {
                        return host
                    }
                }
            }
        } catch (_: Exception) {}
        return "未知"
    }

    private fun requestScreenCapture() {
        binding.tvStatus.text = "请求屏幕录制权限..."
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                pendingScreenCaptureResult = data
                binding.tvStatus.text = "屏幕录制权限已获取，请再次点击开始投屏"
            } else {
                binding.tvStatus.text = "需要屏幕录制权限才能投屏"
            }
        }
    }

    override fun onDestroy() {
        stopAutoDiscovery()
        stopMdnsDiscovery()
        scope.cancel()
        super.onDestroy()
    }
}
