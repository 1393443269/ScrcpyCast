package com.scrcpycast.ui

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scrcpycast.databinding.ActivityMainBinding
import com.scrcpycast.protocol.Protocol

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SCREEN_CAPTURE = 1001
        var pendingResultCode: Int = -1
        var pendingResultData: Intent? = null
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSender.setOnClickListener {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_SCREEN_CAPTURE && resultCode == RESULT_OK && data != null) {
            pendingResultCode = resultCode
            pendingResultData = data
            startActivity(Intent(this, ServerActivity::class.java))
        } else {
            Toast.makeText(this, "需要屏幕录制权限才能投屏", Toast.LENGTH_SHORT).show()
        }
    }
}
