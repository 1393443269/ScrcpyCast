package com.scrcpycast.server

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.scrcpycast.protocol.Protocol
import com.scrcpycast.ui.MainActivity
import kotlin.concurrent.thread

object ServerManager {

    data class State(
        val isRunning: Boolean = false,
        val status: String = "就绪",
        val receiverIp: String = "",
        val port: Int = com.scrcpycast.protocol.Protocol.DEFAULT_PORT,
        val fps: Float = 0f
    )

    private var currentState = State()
    private var listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    var pendingStatusUpdate: ((String) -> Unit)? = null
    var pendingFpsUpdate: ((Float) -> Unit)? = null

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

    data class CastConfig(
        val width: Int = 1920,
        val height: Int = 1080,
        val bitRate: Int = 5_000_000,
        val frameRate: Int = 30
    )

    fun start(context: Context, receiverIp: String, port: Int = com.scrcpycast.protocol.Protocol.DEFAULT_PORT, castConfig: CastConfig = CastConfig()) {
        if (currentState.isRunning) return

        ServerService.pendingProjectionData = MainActivity.pendingResultData

        updateState { copy(isRunning = true, status = "启动中...", receiverIp = receiverIp, port = port) }

        pendingStatusUpdate = { status ->
            updateState { copy(status = status) }
        }
        pendingFpsUpdate = { fps ->
            updateState { copy(fps = fps) }
        }

        val intent = Intent(context, ServerService::class.java).apply {
            putExtra("receiver_ip", receiverIp)
            putExtra("receiver_port", port)
            putExtra("cast_width", castConfig.width)
            putExtra("cast_height", castConfig.height)
            putExtra("cast_bitrate", castConfig.bitRate)
            putExtra("cast_framerate", castConfig.frameRate)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }

        // Start LeboCastManager for lelink/UGREEN protocol
        if (Protocol.isLelinkPort(port)) {
            Log.d("ServerManager", "Starting LeboCastManager: ip=$receiverIp port=$port width=${castConfig.width} height=${castConfig.height}")
            // Set Protocol.leboCastManager IMMEDIATELY so encodeLoop's sendCodecConfig (invoked
            // on INFO_OUTPUT_FORMAT_CHANGED) can find it and store pendingCsd before handshake.
            val leboManager = LeboCastManager()
            Protocol.leboCastManager = leboManager
            // Wait for encoder to produce csd so we can include sprop-parameter-sets in SDP
            thread {
                var csd: ByteArray? = null
                for (i in 0..100) { // wait up to ~5s (50ms * 100)
                    csd = ServerService.csdForLelink
                    if (csd != null) break
                    Thread.sleep(50)
                }
                if (csd == null) {
                    Log.w("ServerManager", "csd not available after waiting, starting without sprop-parameter-sets")
                }
                leboManager.start(receiverIp, port, castConfig.width, castConfig.height, csd)
            }
        }
    }

    fun stop() {
        ServerService.currentService?.stopCasting()
        Protocol.stopLeboCast()
        updateState { copy(isRunning = false, status = "已停止", fps = 0f) }
    }

    fun getState(): State = currentState
}
