package com.scrcpycast.client

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.scrcpycast.protocol.Protocol
import com.scrcpycast.protocol.Protocol.Config
import kotlinx.coroutines.*
import java.io.InputStream
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class ClientManager {

    companion object {
        private const val TAG = "ClientManager"
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
    private var listeners = mutableListOf<(State) -> Unit>()
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: InputStream? = null
    private var decoder: MediaCodec? = null
    private var decoderSurface: Surface? = null
    private val isRunning = AtomicBoolean(false)
    private var listenJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var frameCount = 0L
    private var lastFpsTime = 0L
    private var savedConfig: Config? = null
    private var savedCsd: ByteArray? = null

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

    fun startListening(port: Int = Protocol.DEFAULT_PORT) {
        if (isRunning.get()) return
        isRunning.set(true)

        val ip = getLocalIpAddress()

        updateState {
            copy(isListening = true, status = "正在监听...", localIp = ip, port = port)
        }

        listenJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                serverSocket?.reuseAddress = true
                updateState {
                    copy(status = "等待发送端连接\nIP: ${ip}:$port")
                }

                clientSocket = serverSocket!!.accept()
                clientSocket?.tcpNoDelay = true
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
                    return@launch
                }

                Log.d(TAG, "Config received: ${cfg.width}x${cfg.height} codec=${cfg.codec}")
                updateState {
                    copy(
                        status = "接收中 ${cfg.width}x${cfg.height}",
                        width = cfg.width, height = cfg.height
                    )
                }

                setupDecoder(cfg)
                if (decoder == null) return@launch
                Log.d(TAG, "Decoder started")
                receiveLoop(cfg)

            } catch (e: Exception) {
                Log.e(TAG, "Listener error", e)
                updateState { copy(status = "错误：${e.message}", isConnected = false) }
            } finally {
                cleanup()
                isRunning.set(false)
                updateState { copy(isListening = false, isConnected = false) }
            }
        }
    }

    private fun setupDecoder(config: Config) {
        savedConfig = config
        val mime = Protocol.getCodecMime(config.codec)
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_FRAME_RATE, config.frameRate)
        }

        val surface = displaySurface
        if (surface == null) {
            updateState { copy(status = "等待显示 Surface...") }
            throw IllegalStateException("displaySurface is null")
        }

        try {
            decoderSurface = surface
            decoder = MediaCodec.createDecoderByType(mime)
            decoder?.configure(format, surface, null, 0)
            decoder?.start()
            updateState { copy(status = "解码器已启动") }
        } catch (e: Exception) {
            Log.e(TAG, "Decoder setup failed", e)
            updateState { copy(status = "解码器错误：${e.message}", isConnected = false) }
            throw e
        }
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

    private fun drainDecoder(dec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
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
    }

    private suspend fun receiveLoop(config: Config) {
        val bufferInfo = MediaCodec.BufferInfo()
        frameCount = 0L
        lastFpsTime = System.currentTimeMillis()
        val inputStream = inputStream ?: return
        val dec = decoder ?: return

        Log.d(TAG, "receiveLoop started")
        while (isRunning.get() && clientSocket?.isConnected == true) {
            try {
                val pair = Protocol.readHeader(inputStream) ?: break
                val (header, frameSize) = pair

                when (header.type) {
                    Protocol.TYPE_CODEC_CONFIG -> {
                        val csd = readFrameData(inputStream, frameSize) ?: break
                        savedCsd = csd
                        Log.d(TAG, "Received CSD size=${csd.size}")
                        val inputIndex = dec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuf = dec.getInputBuffer(inputIndex) ?: continue
                            inputBuf.clear()
                            inputBuf.put(csd)
                            dec.queueInputBuffer(inputIndex, 0, csd.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        }
                    }
                    Protocol.TYPE_VIDEO_FRAME -> {
                        if (frameSize <= 0) continue
                        val frameData = readFrameData(inputStream, frameSize) ?: break
                        val inputIndex = dec.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuf = dec.getInputBuffer(inputIndex) ?: continue
                            inputBuf.clear()
                            inputBuf.put(frameData)
                            dec.queueInputBuffer(inputIndex, 0, frameData.size, header.pts, 0)
                            drainDecoder(dec, bufferInfo)
                        }
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
        Log.d(TAG, "receiveLoop ended, rendered frames: $frameCount")
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

    fun stop() {
        isRunning.set(false)
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
    }

    fun destroy() {
        stop()
        scope.cancel()
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
}
