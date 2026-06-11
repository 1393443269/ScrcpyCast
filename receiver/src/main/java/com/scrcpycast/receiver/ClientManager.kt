package com.scrcpycast.receiver

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

typealias Config = Protocol.Config

class ClientManager {

    companion object {
        private const val TAG = "ClientManager"
        private const val DISCOVERY_PORT = 37281
        private const val BEACON_PORT = 37282
        private val DISCOVER_REQ = "SCRPYCAST_DISCOVER".toByteArray()
        private val BEACON_PREFIX = "SCRPYCAST_BEACON:"

        @Volatile
        var isActive = false

        val instance: ClientManager by lazy { ClientManager() }
    }

    data class State(
        val isListening: Boolean = false,
        val status: String = "就绪",
        val localIp: String = "",
        val port: Int = Protocol.DEFAULT_PORT,
        val isConnected: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val fps: Float = 0f
    )

    private var currentState = State()
    private var listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var discoveryJob: Job? = null
    private var ipRefreshJob: Job? = null
    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var savedConfig: Config? = null
    private var savedCsd: ByteArray? = null

    private data class PendingFrame(val type: Int, val data: ByteArray, val pts: Long = 0)
    private val pendingFrames = mutableListOf<PendingFrame>()
    private var decoderPending = false

    @Volatile
    var displaySurface: Surface? = null
        set(value) {
            field = value
            heldSurface = value
        }

    private var heldSurface: Surface? = null

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    private fun updateState(transform: State.() -> State) {
        currentState = currentState.transform()
        listeners.forEach { it(currentState) }
    }

    fun getState(): State = currentState

    fun restartDiscovery() {
        Log.d(TAG, "restartDiscovery called")
        discoveryJob?.cancel()
        discoveryJob = null
        if (isRunning.get()) {
            startDiscovery()
        }
    }

    fun refreshIp() {
        val ip = getLocalIpAddress()
        if (ip.isEmpty() || ip == "0.0.0.0") return
        if (ip != currentState.localIp) {
            updateState { copy(localIp = ip, status = "等待发送端连接\nIP: $ip:${currentState.port}") }
        }
    }

    fun startIpRefresh() {
        ipRefreshJob?.cancel()
        ipRefreshJob = scope.launch {
            while (isActive) {
                refreshIp()
                delay(5000)
            }
        }
    }

    fun stopIpRefresh() {
        ipRefreshJob?.cancel()
        ipRefreshJob = null
    }

    fun startListening(port: Int = Protocol.DEFAULT_PORT) {
        Log.d(TAG, "startListening called, isRunning=${isRunning.get()}")
        if (isRunning.get()) return
        isRunning.set(true)
        ClientManager.isActive = true

        val ip = getLocalIpAddress()
        Log.d(TAG, "localIp=$ip")

        updateState {
            copy(isListening = true, status = "正在监听...", localIp = ip, port = port)
        }

        startDiscovery()

        listenJob = scope.launch {
            Log.d(TAG, "listenJob started, binding port $port")
            var bindFailCount = 0
            while (isRunning.get()) {
                try {
                    serverSocket = ServerSocket().apply {
                        reuseAddress = true
                        receiveBufferSize = 524288
                        bind(java.net.InetSocketAddress("0.0.0.0", port))
                    }
                    bindFailCount = 0
                    val ipNow = getLocalIpAddress()
                    updateState {
                        copy(status = "等待发送端连接\nIP: ${ipNow}:$port", localIp = ipNow, isConnected = false)
                    }

                    clientSocket = serverSocket!!.accept()
                    clientSocket?.tcpNoDelay = true
                    clientSocket?.soTimeout = 120000
                    clientSocket?.receiveBufferSize = 524288
                    inputStream = clientSocket!!.getInputStream()
                    Log.d(TAG, "Client connected from ${clientSocket!!.inetAddress.hostAddress}")
                    updateState {
                        copy(
                            status = "已连接: ${clientSocket!!.inetAddress.hostAddress}",
                            isConnected = true
                        )
                    }

                    val cfg = Protocol.readConfig(inputStream!!)
                    if (cfg == null) {
                        Log.e(TAG, "Failed to read config")
                        updateState { copy(status = "错误：无效的配置信息", isConnected = false) }
                        cleanup()
                        continue
                    }

                    Log.d(TAG, "Config received: ${cfg.width}x${cfg.height} codec=${cfg.codec}")
                    updateState {
                        copy(
                            status = "接收中 ${cfg.width}x${cfg.height}",
                            width = cfg.width, height = cfg.height
                        )
                    }

                    pendingFrames.clear()
                    decoderPending = false
                    setupDecoder(cfg)
                    Log.d(TAG, "Decoder started=${decoder != null}, pending=${decoderPending}")
                    receiveLoop(cfg)

                } catch (e: java.net.BindException) {
                    bindFailCount++
                    Log.w(TAG, "Bind failed (attempt $bindFailCount), retrying...", e)
                    updateState { copy(status = "网络不可用，等待恢复... ($bindFailCount)", isConnected = false) }
                    cleanup()
                    delay(3000)
                } catch (e: Exception) {
                    if (!isRunning.get()) break
                    Log.e(TAG, "Listener error", e)
                    updateState { copy(status = "等待重连...", isConnected = false) }
                    delay(2000)
                } finally {
                    cleanup()
                }
            }
            isRunning.set(false)
            ClientManager.isActive = false
            updateState { copy(isListening = false, isConnected = false) }
        }
    }

    private fun setupDecoder(config: Config) {
        savedConfig = config
        val mime = Protocol.getCodecMime(config.codec)
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
            // Tell decoder the expected frame rate so it pre-allocates enough buffers
            if (Build.VERSION.SDK_INT >= 23) {
                setInteger(MediaFormat.KEY_OPERATING_RATE, config.frameRate * 3)
            }
            // Low-latency mode — reduces decoder pipeline depth
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger("low-latency", 1)
            }
            // Allow frame drop when decoder is behind (catches up faster)
            if (Build.VERSION.SDK_INT >= 29) {
                setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
            }
        }

        val surface = displaySurface
        if (surface == null) {
            decoderPending = true
            updateState { copy(status = "等待画面显示...") }
            Log.d(TAG, "Surface not ready, will buffer frames")
            return
        }

        createDecoder(mime, format, surface)
        flushPendingFrames()
    }

    private fun createDecoder(mime: String, format: MediaFormat, surface: Surface) {
        decoderSurface = surface
        decoderPending = false

        val d = DecoderSelector.selectDecoder(mime, format, surface)
        if (d != null) {
            decoder = d
            updateState { copy(status = "解码器已启动") }
            return
        }

        Log.e(TAG, "All decoders failed")
        updateState { copy(status = "解码器错误", isConnected = false) }
    }

    private fun feedCsdToDecoder(csd: ByteArray) {
        savedCsd = csd
        val d = decoder ?: return
        try {
            val inputIndex = d.dequeueInputBuffer(50_000)
            if (inputIndex >= 0) {
                val inputBuf = d.getInputBuffer(inputIndex) ?: return
                inputBuf.clear()
                inputBuf.put(csd)
                d.queueInputBuffer(inputIndex, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            } else {
                Log.w(TAG, "dequeueInputBuffer(CSD) returned $inputIndex")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedCsdToDecoder error", e)
        }
    }

    private fun feedFrameToDecoder(frameData: ByteArray, pts: Long, bufferInfo: MediaCodec.BufferInfo) {
        val d = decoder ?: return
        try {
            var inputIndex = d.dequeueInputBuffer(50_000)
            // If decoder input is full, drain output buffers and retry with longer wait
            if (inputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                drainDecoder(d, bufferInfo)
                inputIndex = d.dequeueInputBuffer(100_000)
            }
            if (inputIndex >= 0) {
                val inputBuf = d.getInputBuffer(inputIndex) ?: return
                inputBuf.clear()
                inputBuf.put(frameData)
                d.queueInputBuffer(inputIndex, 0, frameData.size, pts, 0)
                drainDecoder(d, bufferInfo)
            } else if (inputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                Log.v(TAG, "Skipped frame (pts=$pts) — decoder too busy after retry")
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedFrameToDecoder error", e)
        }
    }

    private fun flushPendingFrames() {
        if (pendingFrames.isEmpty()) return
        Log.d(TAG, "Flushing ${pendingFrames.size} pending frames to decoder")
        val bufferInfo = MediaCodec.BufferInfo()
        for (frame in pendingFrames) {
            when (frame.type) {
                Protocol.TYPE_CODEC_CONFIG -> feedCsdToDecoder(frame.data)
                Protocol.TYPE_VIDEO_FRAME -> feedFrameToDecoder(frame.data, frame.pts, bufferInfo)
            }
        }
        pendingFrames.clear()
    }

    private fun readFrameData(inputStream: InputStream, size: Int): ByteArray? {
        val data = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = inputStream.read(data, offset, size - offset)
            if (n < 0) return null
            offset += n
        }
        return data
    }

    private suspend fun receiveLoop(config: Config) {
        val bufferInfo = MediaCodec.BufferInfo()
        frameCount = 0L
        lastFpsTime = System.currentTimeMillis()
        val inputStream = inputStream ?: return

        Log.d(TAG, "receiveLoop started, decoder=${decoder != null}, pending=${decoderPending}")
        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                val pair = Protocol.readHeader(inputStream) ?: break
                val (header, frameSize) = pair

                when (header.type) {
                    Protocol.TYPE_CODEC_CONFIG -> {
                        val csd = readFrameData(inputStream, frameSize) ?: break
                        Log.d(TAG, "Received CSD size=${csd.size}")
                        if (decoderPending) {
                            pendingFrames.add(PendingFrame(header.type, csd))
                        } else {
                            feedCsdToDecoder(csd)
                        }
                    }
                    Protocol.TYPE_VIDEO_FRAME -> {
                        if (frameSize <= 0) continue
                        val frameData = readFrameData(inputStream, frameSize) ?: break
                        if (decoderPending) {
                            pendingFrames.add(PendingFrame(header.type, frameData, header.pts))
                            // Keep latency low — discard oldest frames if buffer fills up
                            while (pendingFrames.size > 5) {
                                pendingFrames.removeAt(0)
                            }
                        } else {
                            feedFrameToDecoder(frameData, header.pts, bufferInfo)
                        }
                    }
                    Protocol.TYPE_KEEPALIVE -> {
                        // heartbeat, ignore
                    }
                    else -> {
                        Log.w(TAG, "Unknown frame type: ${header.type}")
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "receiveLoop error", e)
                    updateState { copy(status = "接收错误：${e.message}", isConnected = false) }
                }
                break
            }
        }
        Log.d(TAG, "receiveLoop ended, rendered frames: $frameCount, pending: ${pendingFrames.size}")
    }

    private fun drainDecoder(dec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        var drained = 0
        while (true) {
            try {
                when (val index = dec.dequeueOutputBuffer(bufferInfo, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "Decoder output format changed: ${dec.outputFormat}")
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {}
                    else -> {
                        if (index >= 0) {
                            dec.releaseOutputBuffer(index, true)
                            frameCount++
                            drained++
                            reportFps()
                        }
                    }
                }
            } catch (e: MediaCodec.CodecException) {
                Log.e(TAG, "Codec error in drainDecoder", e)
                break
            } catch (e: Exception) {
                Log.e(TAG, "drainDecoder error", e)
                break
            }
        }
        if (drained > 5) {
            Log.v(TAG, "Drained $drained frames in batch")
        }
    }

    private fun reportFps() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsTime
        if (elapsed >= 2000) {
            val fps = frameCount.toFloat() / (elapsed / 1000f)
            updateState { copy(fps = fps) }
            frameCount = 0
            lastFpsTime = now
        }
    }

    private var nsdManager: android.net.nsd.NsdManager? = null
    private var nsdInfo: android.net.nsd.NsdServiceInfo? = null

    fun registerMdns(context: android.content.Context, port: Int = Protocol.DEFAULT_PORT) {
        try {
            nsdManager = context.getSystemService(android.content.Context.NSD_SERVICE) as android.net.nsd.NsdManager
            val info = android.net.nsd.NsdServiceInfo().apply {
                serviceName = "阿系留接收端"
                serviceType = "_scrcpycast._tcp."
                setPort(port)
            }
            nsdInfo = info
            nsdManager?.registerService(info, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: android.net.nsd.NsdServiceInfo) {
                    Log.d(TAG, "mDNS registered: _scrcpycast._tcp. port=$port")
                }
                override fun onRegistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "mDNS registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: android.net.nsd.NsdServiceInfo) {}
                override fun onUnregistrationFailed(serviceInfo: android.net.nsd.NsdServiceInfo, errorCode: Int) {}
            })
            Log.d(TAG, "Registering mDNS service _scrcpycast._tcp. port=$port")
        } catch (e: Exception) {
            Log.e(TAG, "mDNS registration failed", e)
        }
    }

    private fun unregisterMdns() {
        try {
            nsdManager?.unregisterService(object : android.net.nsd.NsdManager.RegistrationListener {
                override fun onServiceRegistered(p0: android.net.nsd.NsdServiceInfo?) {}
                override fun onRegistrationFailed(p0: android.net.nsd.NsdServiceInfo?, p1: Int) {}
                override fun onServiceUnregistered(p0: android.net.nsd.NsdServiceInfo?) {}
                override fun onUnregistrationFailed(p0: android.net.nsd.NsdServiceInfo?, p1: Int) {}
            })
            nsdManager = null
            nsdInfo = null
        } catch (_: Exception) {}
    }

    private fun startDiscovery() {
        Log.d(TAG, "startDiscovery called")
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            val responder = launch {
                try {
                    val socket = java.net.DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(java.net.InetSocketAddress(DISCOVERY_PORT))
                    }
                    socket.broadcast = true
                    Log.d(TAG, "Discovery listening on port $DISCOVERY_PORT")
                    val buf = ByteArray(256)
                    while (isActive) {
                        try {
                            val pkt = java.net.DatagramPacket(buf, buf.size)
                            socket.receive(pkt)
                            val len = DISCOVER_REQ.size
                            if (pkt.length >= len && ByteArray(len).let { arr ->
                                    System.arraycopy(buf, 0, arr, 0, len)
                                    arr.contentEquals(DISCOVER_REQ)
                                }) {
                                val ip = getLocalIpAddress()
                                val resp = "$ip".toByteArray()
                                val reply = java.net.DatagramPacket(resp, resp.size, pkt.address, pkt.port)
                                socket.send(reply)
                                Log.d(TAG, "Discovery responded to ${pkt.address.hostAddress}")
                            }
                        } catch (_: java.net.SocketException) { break }
                    }
                    socket.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Discovery failed", e)
                }
            }

            val beacon = launch {
                try {
                    val bSocket = java.net.DatagramSocket()
                    bSocket.broadcast = true
                    val bAddr1 = InetAddress.getByName("255.255.255.255")
                    Log.d(TAG, "Beacon broadcasting started on port $BEACON_PORT")
                    while (isActive) {
                        val ip = getLocalIpAddress()
                        val msg = "$BEACON_PREFIX$ip".toByteArray()
                        val bAddr2 = getSubnetBroadcast()
                        for (addr in listOfNotNull(bAddr1, bAddr2).distinct()) {
                            val pkt = java.net.DatagramPacket(msg, msg.size, addr, BEACON_PORT)
                            try { bSocket.send(pkt) } catch (_: Exception) {}
                        }
                        delay(2000)
                    }
                    bSocket.close()
                } catch (_: Exception) {}
            }

            responder.join()
            beacon.join()
        }
    }

    fun stop() {
        ClientManager.isActive = false
        isRunning.set(false)
        discoveryJob?.cancel()
        discoveryJob = null
        unregisterMdns()
        listenJob?.cancel()
        listenJob = null
        cleanup()
        updateState { copy(isListening = false, isConnected = false, status = "已停止") }
    }

    private fun cleanup() {
        try { decoder?.stop() } catch (_: Exception) {}
        try { decoder?.release() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { clientSocket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        decoder = null
        decoderSurface = null
        savedCsd = null
        savedConfig = null
        inputStream = null
        clientSocket = null
        serverSocket = null
        frameCount = 0L
        lastFpsTime = 0L
        decoderPending = false
        pendingFrames.clear()
    }

    fun reconfigureDecoder() {
        if (!currentState.isConnected || savedConfig == null) return
        scope.launch {
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            decoder = null
            decoderSurface = null
            val config = savedConfig ?: return@launch
            val mime = Protocol.getCodecMime(config.codec)
            val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
                setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
                if (Build.VERSION.SDK_INT >= 23) {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, config.frameRate * 3)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    setInteger("low-latency", 1)
                    setInteger(MediaFormat.KEY_ALLOW_FRAME_DROP, 1)
                }
            }
            val surface = displaySurface
            if (surface != null) {
                try {
                    createDecoder(mime, format, surface)
                    flushPendingFrames()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to reconfigure decoder", e)
                }
            }
        }
    }

    fun destroy() {
        discoveryJob?.cancel()
        discoveryJob = null
        stop()
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            var firstV4: String? = null
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    val host = addr.hostAddress
                    if (addr is InetAddress && !addr.isLoopbackAddress && host != null && host.contains('.')) {
                        if (name.contains("wlan") || name.contains("ap")) {
                            return host
                        }
                        if (firstV4 == null) firstV4 = host
                    }
                }
            }
            if (firstV4 != null) return firstV4
        } catch (_: Exception) {}
        return ""
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
}
