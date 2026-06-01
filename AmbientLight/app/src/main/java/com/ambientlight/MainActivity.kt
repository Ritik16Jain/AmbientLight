package com.ambientlight

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // pass projection grant to the service
            val intent = Intent(this, AmbientService::class.java).apply {
                action = AmbientService.ACTION_START
                putExtra(AmbientService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AmbientService.EXTRA_RESULT_DATA, result.data)
            }
            startForegroundService(intent)
            updateUI(running = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText   = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        toggleButton.setOnClickListener {
            if (AmbientService.isRunning) {
                stopService(Intent(this, AmbientService::class.java))
                updateUI(running = false)
            } else {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        updateUI(AmbientService.isRunning)
    }

    override fun onResume() {
        super.onResume()
        updateUI(AmbientService.isRunning)
    }

    private fun updateUI(running: Boolean) {
        if (running) {
            statusText.text   = "● Running"
            toggleButton.text = "Stop"
        } else {
            statusText.text   = "○ Stopped"
            toggleButton.text = "Start"
        }
    }
}
