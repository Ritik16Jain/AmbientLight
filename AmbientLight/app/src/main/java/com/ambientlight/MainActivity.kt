package com.ambientlight

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var projectionManager: MediaProjectionManager

    private val handler = Handler(Looper.getMainLooper())

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
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
        logText      = findViewById(R.id.logText)
        logScroll    = findViewById(R.id.logScroll)

        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        toggleButton.setOnClickListener {
            if (AmbientService.isRunning) {
                stopService(Intent(this, AmbientService::class.java))
                updateUI(running = false)
            } else {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        // register log listener
        AmbientService.logListener = { message ->
            handler.post {
                logText.append("$message\n")
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }
        if (AmbientService.logBuffer.isNotEmpty()) {
            AmbientService.logBuffer.forEach { line ->
                logText.append("$line\n")
            }
            logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        updateUI(AmbientService.isRunning)
    }

        override fun onResume() {
            super.onResume()
            updateUI(AmbientService.isRunning)
        
            // re-register listener in case it was cleared
            AmbientService.logListener = { message ->
                handler.post {
                    logText.append("$message\n")
                    logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        
            AmbientService.colorListener = { smoothed ->
                val colors = IntArray(AmbientService.TOTAL_LEDS) { i ->
                    val r = smoothed[i][0].toInt().coerceIn(0, 255)
                    val g = smoothed[i][1].toInt().coerceIn(0, 255)
                    val b = smoothed[i][2].toInt().coerceIn(0, 255)
                    Color.rgb(r, g, b)
                }
                handler.post { ledView.updateColors(colors) }
            }
        
            // replay buffer
            if (AmbientService.logBuffer.isNotEmpty()) {
                logText.text = ""
                AmbientService.logBuffer.forEach { line ->
                    logText.append("$line\n")
                }
                logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }

    override fun onDestroy() {
        super.onDestroy()
        AmbientService.logListener   = null
        AmbientService.colorListener = null
        // do NOT clear logBuffer here
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
