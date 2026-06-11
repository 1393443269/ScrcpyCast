package com.scrcpycast.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.scrcpycast.protocol.Protocol
import com.scrcpycast.protocol.Protocol.Config
import kotlinx.coroutines.*
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class ServerService : Service() {

    companion object {
        private const val TAG = "ServerService"
        private const val CHANNEL_ID = "scrcpycast_server"
        private const val NOTIFICATION_ID = 1001
        var currentService: ServerService? = null
            private set
        var pendingProjectionData: Intent? = null
        @Volatile
        var csdForLelink: ByteArray? = null
        @Volatile
        @JvmStatic
        var requestKeyFrame: Boolean = false
    }

    private var clientSocket: Socket? = null
    private var encoder: MediaCodec? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var surface: Surface? = null
    private var outputStream: OutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var captureJob: Job? = null
    private var sendJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Reusable read buffer to avoid per-frame allocation
    private val readBuffer = ByteArray(1024 * 1024)  // 1MB

    // Async send queue: encoder produces → network consumer
    private data class FrameData(val data: ByteArray, val pts: Long)
    private val sendQueue = ConcurrentLinkedQueue<FrameData>()
    private val maxQueueSize = 15  // max 15 queued frames (~0.5s at 30fps)

    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var lastReceiverIp: String = ""
    private var lastReceiverPort: Int = Protocol.DEFAULT_PORT
    private var lastResultData: Intent? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var wifiConnected = false
    private var reconnectCount = 0
    private var totalReconnects = 0
    private var connectionStartTime = 0L
    private var wakeLock: PowerManager.WakeLock? = null

    private fun reportStatus(status: String) {
        ServerManager.pendingStatusUpdate?.invoke(status)
    }

    private fun reportFpsLocal(fps: Float) {
        ServerManager.pendingFpsUpdate?.invoke(fps)
    }

    override fun onCreate() {
        super.onCreate()
        currentService = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("ScrcpyCast 启动中..."))
        registerNetworkMonitor()
        acquireWakeLock()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "ScrcpyCast:Cast"
        )
        wakeLock?.acquire()
        Log.d(TAG, "WakeLock acquired")
    }

    private fun registerNetworkMonitor() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WiFi network available")
                wifiConnected = true
            }
            override fun onLost(network: Network) {
                Log.d(TAG, "WiFi network lost")
                wifiConnected = false
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    if (!wifiConnected && lastResultData != null) {
                        Log.d(TAG, "WiFi restored with internet, triggering reconnect")
                        wifiConnected = true
                        scope.launch { reconnectWithDelay() }
                    }
                }
            }
        }
        cm.registerNetworkCallback(request, networkCallback!!)
    }

    private suspend fun reconnectWithDelay() {
        delay(2000)
        if (lastResultData != null && lastReceiverIp.isNotEmpty()) {
            Log.d(TAG, "Auto-reconnecting to $lastReceiverIp:$lastReceiverPort")
            reportStatus("WiFi已恢复，自动重连...")
            startCastingInternal(lastReceiverIp, lastReceiverPort, lastResultData!!)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ip = intent.getStringExtra("receiver_ip") ?: ""
        val port = intent.getIntExtra("receiver_port", Protocol.DEFAULT_PORT)
        val castWidth = intent.getIntExtra("cast_width", 1280)
        val castHeight = intent.getIntExtra("cast_height", 720)
        val castBitrate = intent.getIntExtra("cast_bitrate", 5_000_000)
        val castFramerate = intent.getIntExtra("cast_framerate", 30)
        val resultData = pendingProjectionData
        pendingProjectionData = null
        if (resultData != null) {
            startCasting(ip, port, castWidth, castHeight, castBitrate, castFramerate, resultData)
        } else {
            reportStatus("错误：无屏幕录制权限数据")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private var pendingWidth = 1280
    private var pendingHeight = 720
    private var pendingBitrate = 5_000_000
    private var pendingFramerate = 30

    private fun startCasting(receiverIp: String, port: Int, width: Int, height: Int, bitRate: Int, frameRate: Int, resultData: Intent) {
        if (isRunning.get()) return
        lastReceiverIp = receiverIp
        lastReceiverPort = port
        lastResultData = resultData
        pendingWidth = width
        pendingHeight = height
        pendingBitrate = bitRate
        pendingFramerate = frameRate
        startCastingInternal(receiverIp, port, resultData)
    }

    private fun startCastingInternal(receiverIp: String, port: Int, resultData: Intent) {
        if (isRunning.get()) return
        isRunning.set(true)

        Log.d(TAG, "startCastingInternal: ip=$receiverIp port=$port (port=0x${port.toString(16)})")

        reportStatus("正在获取屏幕投影...")

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, resultData)

        if (mediaProjection == null) {
            reportStatus("错误：无法获取屏幕投影")
            isRunning.set(false)
            return
        }

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
                reportStatus("屏幕投影已停止")
            }
        }, null)
        Log.d(TAG, "MediaProjection obtained")

        startForeground(NOTIFICATION_ID, createNotification("正在投屏中..."))

        captureJob = scope.launch {
            reconnectCount = 0
            while (isRunning.get()) {
                try {
                    cleanupResources()
                    reconnectCount++
                    var connected = false
                    var baseDelay = 500L

                    // For lelink (UGREEN) protocol, encoder-only mode: no TCP connection needed,
                    // LeboCastManager handles the RTSP/interleaved RTP connection separately
                    val isLelinkMode = Protocol.isLelinkPort(port)
                    if (!isLelinkMode) {
                        for (attempt in 1..60) {
                            if (!isRunning.get()) return@launch
                            reportStatus("正在连接接收端($attempt/60) $receiverIp:$port ...")
                            try {
                                val startMs = System.currentTimeMillis()
                                clientSocket = Socket().apply {
                                    tcpNoDelay = true
                                    keepAlive = true
                                    soTimeout = 5000
                                    sendBufferSize = 524288  // 512KB
                                    receiveBufferSize = 262144  // 256KB
                                    connect(InetSocketAddress(java.net.Inet4Address.getByName(receiverIp), port), 3000)
                                }
                                val rtt = System.currentTimeMillis() - startMs
                                Log.d(TAG, "Connected in ${rtt}ms, attempt $attempt")
                                reportStatus("已连接 (${rtt}ms)")
                                connected = true
                                break
                            } catch (e: Exception) {
                                if (!isRunning.get()) return@launch
                                val delayMs = baseDelay * attempt.coerceAtMost(10)
                                delay(delayMs)
                            }
                        }
                        if (!connected) {
                            if (isRunning.get()) {
                                totalReconnects++
                                reportStatus("连接失败${totalReconnects}次，15秒后重试...")
                                delay(15000)
                                continue
                            }
                            return@launch
                        }
                        reconnectCount = 0
                        connectionStartTime = System.currentTimeMillis()
                        totalReconnects = 0
                        outputStream = clientSocket!!.getOutputStream()
                        reportStatus("已连接接收端，开始投屏")
                    } else {
                        // Lelink mode: use dummy OutputStream (LeboCastManager handles actual transport)
                        outputStream = object : OutputStream() {
                            override fun write(b: Int) {}  // no-op
                            override fun write(b: ByteArray, off: Int, len: Int) {}
                            override fun flush() {}
                        }
                        reportStatus("lelink模式，编码器启动中...")
                    }
                    updateNotification("投屏中 1280x720")

                    val config = Config(port = port, width = pendingWidth, height = pendingHeight, codec = Protocol.CODEC_H264, bitRate = pendingBitrate, frameRate = pendingFramerate)
                    setupEncoder(config)

                    // Get encoder input Surface and feed directly to VirtualDisplay
                    val encoderSurface = encoder?.createInputSurface()
                    if (encoderSurface == null) {
                        reportStatus("错误：无法创建编码器输入表面")
                        isRunning.set(false)
                        return@launch
                    }
                    Log.d(TAG, "Encoder input surface created")

                    // start encoder
                    encoder?.start()

                    virtualDisplay = mediaProjection?.createVirtualDisplay(
                        "ScrcpyCast",
                        config.width, config.height,
                        resources.displayMetrics.densityDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        encoderSurface,
                        null,
                        null
                    )
                    Log.d(TAG, "VirtualDisplay created: ${virtualDisplay != null}, surface=$encoderSurface")

                    // Start async send loop
                    startSendLoop(config)
                    // Run encoder loop (non-blocking writes to queue)
                    encodeLoop(config)
                    // Avoid tight reconnection loop if encodeLoop exits normally
                    sendJob?.cancel()
                    sendJob = null
                    sendQueue.clear()
                    if (!isRunning.get()) return@launch
                    delay(3000)

                } catch (e: Exception) {
                    Log.e(TAG, "Casting error", e)
                    reportStatus("错误：${e.message}")
                    if (!isRunning.get()) return@launch
                    delay(3000)
                } finally {
                    cleanupResources()
                }
            }
        }
    }

    private fun setupEncoder(config: Config) {
        val mime = Protocol.getCodecMime(config.codec)
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.frameRate)
            setInteger(MediaFormat.KEY_PRIORITY, 1)  // realtime priority
            // Baseline Profile: lower decoder complexity, better compatibility
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
        }
        encoder = MediaCodec.createEncoderByType(mime)
        encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        Log.d(TAG, "Encoder configured: ${encoder?.codecInfo?.name} (Surface input)")
    }

    private fun startSendLoop(config: Config) {
        val out = outputStream ?: return
        sendJob?.cancel()
        sendQueue.clear()
        sendJob = scope.launch(Dispatchers.IO) {
            Log.d(TAG, "sendLoop started")
            while (isRunning.get()) {
                val frame = sendQueue.poll()
                if (frame == null) {
                    delay(1)
                    continue
                }
                try {
                    Protocol.writeFrame(out, config.codec, config.width, config.height, frame.pts, frame.data)
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "sendLoop error", e)
                    break
                }
            }
            Log.d(TAG, "sendLoop ended")
        }
    }

    private suspend fun encodeLoop(config: Config) {
        val bufferInfo = MediaCodec.BufferInfo()
        frameCount = 0L
        lastFpsTime = System.currentTimeMillis()
        val out = outputStream ?: return
        val enc = encoder ?: return
        var configSent = false
        var lastSendTime = System.currentTimeMillis()
        var sendCount = 0
        var csdSent = false
        var dropCount = 0
        var frameRateDropCount = 0

        // Frame rate limiting: min interval in µs between frames
        val minFrameIntervalUs = 1_000_000L / config.frameRate
        var lastFramePtsUs = 0L

        Log.d(TAG, "encodeLoop started with async send (max_queue=$maxQueueSize, target_fps=${config.frameRate}, interval=${minFrameIntervalUs}us)")

        while (isRunning.get() && clientSocket?.isConnected != false) {
            // Drain encoder output (Surface input drives encoder automatically)
            while (true) {
                val index = enc.dequeueOutputBuffer(bufferInfo, 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Encoder output format changed")
                        if (!configSent) {
                            Protocol.writeConfig(out, config)
                            configSent = true
                        }
                        if (!csdSent) {
                            val csd0 = enc.outputFormat.getByteBuffer("csd-0")
                            val csd1 = enc.outputFormat.getByteBuffer("csd-1")
                            if (csd0 != null) {
                                val csd0Bytes = ByteArray(csd0.remaining()).also { csd0.get(it) }
                                val combined = if (csd1 != null) {
                                    val csd1Bytes = ByteArray(csd1.remaining()).also { csd1.get(it) }
                                    ByteArray(csd0Bytes.size + csd1Bytes.size).also {
                                        System.arraycopy(csd0Bytes, 0, it, 0, csd0Bytes.size)
                                        System.arraycopy(csd1Bytes, 0, it, csd0Bytes.size, csd1Bytes.size)
                                    }
                                } else {
                                    csd0Bytes
                                }
                                Protocol.writeCodecConfig(out, config.codec, config.width, config.height, combined)
                                csdSent = true
                                csdForLelink = combined
                                Log.d(TAG, "Sent CSD-0+1 combined size=${combined.size}")
                            }
                        }
                        lastSendTime = System.currentTimeMillis()
                    }
                    index >= 0 -> {
                        val outputBuf = enc.getOutputBuffer(index) ?: continue
                        if (bufferInfo.size > 0) {
                            val size = bufferInfo.size

                            // Frame rate limiting: drop frame if it's too early
                            val pts = bufferInfo.presentationTimeUs
                            if (lastFramePtsUs > 0 && pts - lastFramePtsUs < minFrameIntervalUs) {
                                frameRateDropCount++
                                if (frameRateDropCount <= 3 || frameRateDropCount % 60 == 0) {
                                    Log.w(TAG, "Frame rate drop #$frameRateDropCount (pts=${pts}us, since_last=${pts - lastFramePtsUs}us, target=${minFrameIntervalUs}us)")
                                }
                                enc.releaseOutputBuffer(index, false)
                                continue
                            }
                            frameRateDropCount = 0
                            lastFramePtsUs = pts

                            // Backpressure: drop frame if queue is too deep
                            if (sendQueue.size >= maxQueueSize) {
                                dropCount++
                                if (dropCount <= 3 || dropCount % 30 == 0) {
                                    Log.w(TAG, "Dropping frame #$dropCount (queue full, size=$size)")
                                }
                                enc.releaseOutputBuffer(index, false)
                                lastSendTime = System.currentTimeMillis()
                                continue
                            }
                            dropCount = 0

                            // Use pre-allocated readBuffer to avoid per-frame allocation
                            val data = if (size <= readBuffer.size) {
                                outputBuf.get(readBuffer, 0, size)
                                readBuffer.copyOf(size)
                            } else {
                                ByteArray(size).also { outputBuf.get(it) }
                            }

                            val isKeyFrame = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            sendQueue.add(FrameData(data, pts))
                            sendCount++
                            frameCount++
                            if (sendCount <= 3 || sendCount % 30 == 1 || isKeyFrame) {
                                Log.d(TAG, "Sent frame #$sendCount size=${data.size} keyFrame=$isKeyFrame pts=$pts")
                            }
                            reportFps()
                        }
                        enc.releaseOutputBuffer(index, false)
                        lastSendTime = System.currentTimeMillis()
                    }
                }
            }

            // Keepalive if idle too long
            val now = System.currentTimeMillis()
            if (now - lastSendTime > 8000) {
                try {
                    Protocol.writeHeader(out, Protocol.TYPE_KEEPALIVE, config.codec, 0, 0, 0, 0)
                    lastSendTime = now
                    Log.d(TAG, "Sent keepalive")
                } catch (_: Exception) { break }
            }

            // Brief delay to avoid busy-loop
            delay(1)

            // Check for IDR frame request (from LeboCastManager handshake completion)
            if (requestKeyFrame) {
                requestKeyFrame = false
                try {
                    val params = Bundle()
                    params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    enc.setParameters(params)
                    Log.d(TAG, "Requested IDR frame after handshake")
                } catch (_: Exception) {}
            }
        }
        Log.d(TAG, "encodeLoop ended: sent=$sendCount dropped=$dropCount fps_dropped=$frameRateDropCount")
    }

    private fun reportFps() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 2000) {
            val fps = frameCount.toFloat() / (elapsed / 1000f)
            reportFpsLocal(fps)
            updateNotifWithFps(fps)
            frameCount = 0
            lastFpsTime = now
        }
    }

    fun stopCasting() {
        isRunning.set(false)
        captureJob?.cancel()
        captureJob = null
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        reportStatus("已停止")
    }

    private fun cleanup() {
        sendJob?.cancel()
        sendJob = null
        sendQueue.clear()
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        encoder = null
        surface = null
        virtualDisplay = null
        outputStream = null
        clientSocket = null
        mediaProjection = null
    }

    private fun cleanupResources() {
        sendJob?.cancel()
        sendJob = null
        sendQueue.clear()
        try { encoder?.stop() } catch (_: Exception) {}
        try { encoder?.release() } catch (_: Exception) {}
        try { surface?.release() } catch (_: Exception) {}
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        encoder = null
        surface = null
        virtualDisplay = null
        outputStream = null
        clientSocket = null
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        networkCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        networkCallback = null
        stopCasting()
        currentService = null
        scope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ScrcpyCast 投屏", NotificationManager.IMPORTANCE_LOW).apply {
                description = "屏幕镜像服务状态"
                setSound(null, null)
                vibrationPattern = null
                enableVibration(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScrcpyCast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScrcpyCast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
    }

    private var lastNotifFps = 0f
    private var lastFpsUpdateMs = 0L

    private fun updateNotifWithFps(fps: Float) {
        val now = System.currentTimeMillis()
        if (now - lastFpsUpdateMs < 3000) return
        lastFpsUpdateMs = now
        lastNotifFps = fps
        val uptime = (now - connectionStartTime) / 1000
        val text = buildString {
            append(String.format("投屏中 1280x720 @ %.1f fps", fps))
            if (uptime > 0) append(" | ${uptime}s")
            if (totalReconnects > 0) append(" | 重连${totalReconnects}")
        }
        updateNotification(text)
    }
}
