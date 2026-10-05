package com.scos3.camera.ui.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.scos3.camera.R
import com.scos3.camera.camera.CameraCapabilities
import com.scos3.camera.camera.CameraManager
import com.scos3.camera.databinding.ActivityDiagnosticsBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Temporary diagnostics screen (Day 3 phase). Dumps everything needed to debug
 * the 0.5x/ultra-wide problem on the physical test device:
 *  - device model / Android version / SDK
 *  - every Camera2 camera id with facing, focal lengths, sensor size, pixel
 *    array, capabilities, physical camera ids, zoom range
 *  - the "0.5x supported / not exposed" verdict
 *  - CameraX-visible cameras
 *  - supported JPEG sizes
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagnosticsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.copyButton.setOnClickListener {
            val clip = ClipData.newPlainText("Merit1st diagnostics", binding.diagnosticsText.text)
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(clip)
            Toast.makeText(this, R.string.diagnostics_copy, Toast.LENGTH_SHORT).show()
        }
        binding.shareButton.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, binding.diagnosticsText.text.toString())
            }
            startActivity(Intent.createChooser(send, getString(R.string.diagnostics_share)))
        }

        CoroutineScope(Dispatchers.Main).launch {
            binding.diagnosticsText.text = buildReport()
        }
    }

    private suspend fun buildReport(): String {
        val caps = CameraCapabilities(this)
        val sb = StringBuilder()
        sb.append("Device     : ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n")
        sb.append("Android    : ").append(Build.VERSION.RELEASE)
            .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Target SDK : 36\n\n")
        sb.append(caps.buildReport()).append("\n")

        val provider = runCatching { CameraManager(applicationContext).getProvider() }.getOrNull()
        if (provider != null) {
            sb.append("CameraX available cameras : ")
                .append(if (caps.cameraXIds(provider).isEmpty()) "none" else caps.cameraXIds(provider).joinToString(", "))
                .append("\n")
            sb.append("CameraX has back          : ").append(caps.cameraXHasBack(provider)).append("\n")
            sb.append("CameraX has front         : ").append(caps.cameraXHasFront(provider)).append("\n\n")
        }

        sb.append("Supported JPEG sizes (back, top 10):\n")
        caps.supportedResolutions(back = true).take(10).forEach { s ->
            sb.append("  ").append(s.width).append("x").append(s.height)
                .append(" (").append(String.format("%.1f", s.width * s.height / 1_000_000f)).append(" MP)\n")
        }
        sb.append("\nSupported JPEG sizes (front, top 10):\n")
        caps.supportedResolutions(back = false).take(10).forEach { s ->
            sb.append("  ").append(s.width).append("x").append(s.height).append("\n")
        }

        sb.append("\nVerdict:\n  ")
        sb.append(
            if (caps.supportsSubUnitZoomRatio() || caps.ultrawidePhysicalCameraId() != null) {
                getString(R.string.diagnostics_ultrawide_supported)
            } else {
                getString(R.string.diagnostics_ultrawide_not_exposed)
            }
        )
        sb.append(" — ").append(caps.ultrawideVerdict())
        return sb.toString()
    }
}
