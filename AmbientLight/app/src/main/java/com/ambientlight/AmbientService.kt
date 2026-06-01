package com.ambientlight

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.usb.UsbManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.core.app.NotificationCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.ByteBuffer

class AmbientService : Service() {


    companion object {

        const val ACTION_START       = "com.ambientlight.START"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val CHANNEL_ID         = "ambient_channel"
        const val NOTIF_ID           = 1

        var isRunning = false

        // ── LED config ────────────────────────────────────────────────────────
        const val TOP_LEDS    = 19
        const val RIGHT_LEDS  = 8
        const val LEFT_LEDS   = 8
        const val TOTAL_LEDS  = LEFT_LEDS + TOP_LEDS + RIGHT_LEDS
        const val SAMPLE_SIZE = 50
        const val FPS         = 30
        const val BAUDRATE    = 921600

        val START_BYTE = 0xAA.toByte()
        val END_BYTE   = 0x55.toByte()


        var logListener: ((String) -> Unit)? = null

        fun log(message: String) {
            android.util.Log.d("AmbientLight", message)
            logListener?.invoke(message)
        }
    }

    private val handler     = Handler(Looper.getMainLooper())
    private val frameIntervalMs = (1000.0 / FPS).toLong()

    private var mediaProjection : MediaProjection? = null
    private var virtualDisplay  : VirtualDisplay?  = null
    private var imageReader     : ImageReader?      = null
    private var usbPort         : UsbSerialPort?    = null

    // smoothed LED state
    private val smoothed = Array(TOTAL_LEDS) { FloatArray(3) }
    private val alpha    = 1.0f   // 0.0 = max smooth, 1.0 = no smooth

    // ── service lifecycle ─────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startForeground(NOTIF_ID, buildNotification())
            isRunning = true
            log("Service started @ ${FPS} FPS")

            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)!!

            val pm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = pm.getMediaProjection(resultCode, resultData)

            connectUsb()
            startCapture()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        mediaProjection?.stop()
        imageReader?.close()
        try { usbPort?.close() } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?) = null

    // ── USB ───────────────────────────────────────────────────────────────────

    private fun connectUsb() {
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)

        if (drivers.isEmpty()) {
            log("No USB device found, retrying in 2s...")
            handler.postDelayed({ connectUsb() }, 2000)
            return
        }

        val driver     = drivers[0]
        val connection = manager.openDevice(driver.device)

        if (connection == null) {
            // request permission via broadcast receiver, retry after delay
            log("USB permission not granted, requesting...")
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(UsbReceiver.ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            manager.requestPermission(driver.device, pi)
            handler.postDelayed({ connectUsb() }, 2000)
            return
        }

        try {
            usbPort = driver.ports[0].also { port ->
                port.open(connection)
                port.setParameters(BAUDRATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }
            log("USB connected: ${driver.device.deviceName}")
        } catch (e: Exception) {
            log("USB open failed: ${e.message}, retrying...")
            handler.postDelayed({ connectUsb() }, 2000)
        }
    }

    // ── screen capture ────────────────────────────────────────────────────────

    private fun startCapture() {
        val metrics = resources.displayMetrics
        val W = metrics.widthPixels
        val H = metrics.heightPixels

        imageReader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "AmbientCapture", W, H, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        scheduleFrame()
    }

    private fun scheduleFrame() {
        handler.postDelayed({
            processFrame()
            scheduleFrame()
        }, frameIntervalMs)
    }

    // ── frame processing ──────────────────────────────────────────────────────

    private fun processFrame() {
        val image = imageReader?.acquireLatestImage() ?: return

        try {
            val plane  = image.planes[0]
            val buffer = plane.buffer
            val W      = image.width
            val H      = image.height
            val stride = plane.rowStride / plane.pixelStride

            val raw = Array(TOTAL_LEDS) { FloatArray(3) }
            var idx = 0

            // LEFT (bottom → top)
            val segHL = H / LEFT_LEDS
            for (i in 0 until LEFT_LEDS) {
                val y = H - ((i + 1) * segHL)
                raw[idx++] = avgColor(buffer, stride, y, segHL, 0, SAMPLE_SIZE)
            }

            // TOP (left → right)
            val segWT = W / TOP_LEDS
            for (i in 0 until TOP_LEDS) {
                raw[idx++] = avgColor(buffer, stride, 130, SAMPLE_SIZE, i * segWT, segWT)
            }

            // RIGHT (top → bottom)
            val segHR = H / RIGHT_LEDS
            for (i in 0 until RIGHT_LEDS) {
                raw[idx++] = avgColor(buffer, stride, i * segHR, segHR, W - SAMPLE_SIZE, SAMPLE_SIZE)
            }

            // exponential smoothing
            for (i in 0 until TOTAL_LEDS) {
                for (c in 0..2) {
                    smoothed[i][c] += alpha * (raw[i][c] - smoothed[i][c])
                }
            }

            sendPacket()

        } finally {
            image.close()
        }
    }

    private fun avgColor(
        buffer: ByteBuffer, stride: Int,
        startY: Int, numRows: Int,
        startX: Int, numCols: Int
    ): FloatArray {
        var r = 0L; var g = 0L; var b = 0L; var count = 0

        val maxOffset = buffer.capacity()
        val endY = (startY + numRows).coerceAtMost(buffer.capacity() / (stride * 4) * stride)
        val endX = (startX + numCols).coerceAtMost(stride)

        for (y in startY until endY) {
            for (x in startX until endX) {
                val offset = (y * stride + x) * 4
                if (offset + 2 >= maxOffset) continue
                r += buffer.get(offset).toInt()     and 0xFF
                g += buffer.get(offset + 1).toInt() and 0xFF
                b += buffer.get(offset + 2).toInt() and 0xFF
                count++
            }
        }

        if (count == 0) return floatArrayOf(0f, 0f, 0f)
        return floatArrayOf(
            (r / count).toFloat(),
            (g / count).toFloat(),
            (b / count).toFloat()
        )
    }

    private fun sendPacket() {
        val port = usbPort ?: return

        val packet = ByteArray(2 + TOTAL_LEDS * 3)
        packet[0] = START_BYTE

        for (i in 0 until TOTAL_LEDS) {
            val r = smoothed[i][0].toInt().coerceIn(0, 255)
            val g = smoothed[i][1].toInt().coerceIn(0, 255)
            val b = smoothed[i][2].toInt().coerceIn(0, 255)
            packet[1 + i * 3 + 0] = b.toByte()
            packet[1 + i * 3 + 1] = g.toByte()
            packet[1 + i * 3 + 2] = r.toByte()
        }

        packet[packet.size - 1] = END_BYTE

        try {
            port.write(packet, 100)
        } catch (e: Exception) {
            log("Serial write failed: ${e.message}, reconnecting...")
            usbPort = null
            handler.postDelayed({ connectUsb() }, 2000)
        }
    }

    // ── notification ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Ambient Light",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Ambient light service" }

        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient Light")
            .setContentText("Running @ ${FPS} FPS")
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }
}
