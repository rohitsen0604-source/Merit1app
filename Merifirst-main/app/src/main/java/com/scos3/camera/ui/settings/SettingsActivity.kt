package com.scos3.camera.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.scos3.camera.R
import com.scos3.camera.camera.CameraCapabilities
import com.scos3.camera.camera.CameraManager
import com.scos3.camera.databinding.ActivitySettingsBinding
import com.scos3.camera.email.EmailManager
import com.scos3.camera.email.EmailManagerProvider
import com.scos3.camera.settings.AppSettings
import com.scos3.camera.settings.AppSettings.CameraControllerLens
import com.scos3.camera.settings.AppSettings.CameraResolution
import com.scos3.camera.settings.AppSettings.GmailSettings
import com.scos3.camera.settings.AppSettings.JpegQuality
import com.scos3.camera.settings.SettingsRepository
import com.scos3.camera.ui.diagnostics.DiagnosticsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Settings screen (Day 3).
 *  - Camera: resolution populated from device-supported JPEG sizes (plus AUTO),
 *    quality, default lens, ultra-wide availability verdict.
 *  - Capture: auto interval. Burst/Face are placeholders (next phases).
 *  - Gmail: sender / app password / receiver persisted ENCRYPTED (Android
 *    Keystore) and a "Test email" button that sends a small message via SMTP.
 *    Gmail here is SMTP auth only — not Google Sign-In, not app login, not
 *    Google Drive.
 *  - Device diagnostics entry.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repository: SettingsRepository
    private lateinit var emailManager: EmailManager
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val scrollContent = binding.root.getChildAt(0)
        val baseBottomPadding = scrollContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bottom = maxOf(
                insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom,
                insets.getInsets(WindowInsetsCompat.Type.ime()).bottom,
            )
            scrollContent.updatePadding(bottom = baseBottomPadding + bottom)
            insets
        }

        repository = SettingsRepository(this)
        emailManager = EmailManagerProvider.get(this)
        settings = repository.load()

        binding.saveSettingsButton.setOnClickListener { save() }
        binding.testEmailButton.setOnClickListener { testEmail() }
        binding.clearGmailButton.setOnClickListener { clearGmail() }
        binding.diagnosticsButton.setOnClickListener {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
        }
        binding.volumeOpenSettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        refreshVolumeStatus()
        populateUi()
    }

    override fun onResume() {
        super.onResume()
        refreshVolumeStatus()
    }

    private fun refreshVolumeStatus() {
        binding.volumeStatusText.text = "Volume Control: Active (via Volume Keys)"
        binding.volumeStatusText.setTextColor(getColor(R.color.accent))
        binding.volumeOpenSettingsButton.visibility = View.GONE
    }

    private fun populateUi() {
        val caps = CameraCapabilities(this@SettingsActivity)

        binding.ultrawideInfo.text =
            if (caps.supportsSubUnitZoomRatio() || caps.ultrawidePhysicalCameraId() != null) {
                getString(R.string.settings_ultrawide_available)
            } else {
                getString(R.string.settings_ultrawide_unavailable)
            }

            val sizes = caps.supportedResolutions(back = true).filter { it.width * it.height >= 1_000_000 }
            val entries = mutableListOf(ResolutionEntry(null, null, getString(R.string.settings_resolution_auto)))
            sizes.forEach { entries += ResolutionEntry(it.width, it.height, "%d MP · %dx%d".format(it.width * it.height / 1_000_000, it.width, it.height)) }
            binding.resolutionSpinner.adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                entries,
            )
            val requested = settings.resolution
            val index = if (requested == null) 0 else entries.indexOfFirst {
                requested.width == it.width && requested.height == it.height
            }
            binding.resolutionSpinner.setSelection(if (index >= 0) index else 0)

            binding.qualitySpinner.adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.settings_quality_low),
                    getString(R.string.settings_quality_standard),
                    getString(R.string.settings_quality_high),
                ),
            )
            binding.qualitySpinner.setSelection(settings.quality.ordinal)

            binding.lensSpinner.adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(
                    getString(R.string.lens_back),
                    getString(R.string.lens_front),
                    getString(R.string.lens_screen),
                ),
            )
            binding.lensSpinner.setSelection(
                when (settings.defaultLens) {
                    CameraControllerLens.FRONT -> 1
                    CameraControllerLens.SCREEN -> 2
                    else -> 0
                }
            )

            binding.intervalSpinner.adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("2 s", "4 s", "6 s", "10 s", "12 s", "14 s"),
            )
            binding.intervalSpinner.setSelection(
                when (settings.intervalMs) {
                    2000L -> 0
                    6000L -> 2
                    10000L -> 3
                    12000L -> 4
                    14000L -> 5
                    else -> 1
                }
            )

            binding.emailEnabledSwitch.isChecked = settings.emailEnabled
            binding.gallerySaveEnabledSwitch.isChecked = settings.gallerySaveEnabled

            binding.gmailSenderInput.setText(settings.gmail.senderEmail)
            binding.gmailPasswordInput.setText(settings.gmail.senderAppPassword)
            binding.gmailReceiverInput.setText(settings.gmail.receiverEmail)
            binding.gmailNote.visibility = View.VISIBLE
    }

    private fun currentSettings(): AppSettings {
        val selected = binding.resolutionSpinner.selectedItem as? ResolutionEntry
        val resolution = selected?.takeIf { it.width != null && it.height != null }
            ?.let { CameraResolution(it.width!!, it.height!!) }
        val quality = JpegQuality.entries.getOrElse(binding.qualitySpinner.selectedItemPosition) { JpegQuality.HIGH }
        val lens = when (binding.lensSpinner.selectedItemPosition) {
            1 -> CameraControllerLens.FRONT
            2 -> CameraControllerLens.SCREEN
            else -> CameraControllerLens.BACK
        }
        val intervalMs = when (binding.intervalSpinner.selectedItemPosition) {
            0 -> 2000L
            2 -> 6000L
            3 -> 10000L
            4 -> 12000L
            5 -> 14000L
            else -> 4000L
        }
        return settings.copy(
            resolution = resolution,
            quality = quality,
            intervalMs = intervalMs,
            defaultLens = lens,
            emailEnabled = binding.emailEnabledSwitch.isChecked,
            gallerySaveEnabled = binding.gallerySaveEnabledSwitch.isChecked,
            gmail = GmailSettings(
                senderEmail = binding.gmailSenderInput.text.toString().trim(),
                senderAppPassword = binding.gmailPasswordInput.text.toString().replace(" ", "").trim(),
                receiverEmail = binding.gmailReceiverInput.text.toString().trim(),
            ),
        )
    }

    private fun save() {
        settings = currentSettings()
        repository.save(settings)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun testEmail() {
        repository.save(currentSettings())
        Toast.makeText(this, R.string.settings_testing_email, Toast.LENGTH_SHORT).show()
        emailManager.sendTest { ok, message ->
            runOnUiThread {
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun clearGmail() {
        repository.clearGmail()
        binding.gmailSenderInput.setText("")
        binding.gmailPasswordInput.setText("")
        binding.gmailReceiverInput.setText("")
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private data class ResolutionEntry(val width: Int?, val height: Int?, val label: String) {
        override fun toString(): String = label
    }
}
