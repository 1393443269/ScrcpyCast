package com.scrcpycast.ui

import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import androidx.appcompat.app.AppCompatActivity
import com.scrcpycast.client.ClientManager
import com.scrcpycast.databinding.ActivityClientBinding
import com.scrcpycast.protocol.Protocol

class ClientActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClientBinding
    private val clientManager = ClientManager()
    private var surfaceRef: Surface? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityClientBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.tvDisplay.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                if (surfaceRef == null) {
                    surfaceRef = Surface(surface).also { clientManager.displaySurface = it }
                } else {
                    clientManager.displaySurface = surfaceRef
                }
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                return false
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
        }

        if (binding.tvDisplay.isAvailable && surfaceRef == null) {
            surfaceRef = Surface(binding.tvDisplay.surfaceTexture).also { clientManager.displaySurface = it }
        }

        clientManager.addListener { state ->
            runOnUiThread {
                binding.tvStatus.text = state.status
                binding.tvFps.text = if (state.fps > 0) String.format("%.1f fps", state.fps) else ""
                binding.tvResolution.text = if (state.width > 0) "${state.width}x${state.height}" else ""
                binding.btnToggle.text = if (state.isListening) "停止接收" else "开始接收"
                binding.controlPanel.visibility = if (state.isConnected) android.view.View.GONE else android.view.View.VISIBLE
            }
        }

        val autoStart = intent.getBooleanExtra("auto_start", false)
        if (autoStart) {
            val port = intent.getIntExtra("port", Protocol.DEFAULT_PORT)
            binding.etPort.setText(port.toString())
            binding.controlPanel.visibility = android.view.View.GONE
            binding.root.postDelayed({
                clientManager.startListening(port)
            }, 500)
        }

        binding.btnToggle.setOnClickListener {
            if (clientManager.getState().isListening) {
                clientManager.stop()
            } else {
                val portStr = binding.etPort.text.toString().trim()
                val port = portStr.toIntOrNull() ?: Protocol.DEFAULT_PORT
                clientManager.startListening(port)
            }
        }

        binding.btnBack.setOnClickListener {
            clientManager.stop()
            finish()
        }
    }

    override fun onDestroy() {
        clientManager.destroy()
        surfaceRef?.release()
        surfaceRef = null
        super.onDestroy()
    }
}
