package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.myapplication.audio.AudioFeedback
import com.example.myapplication.detection.Detector
import com.example.myapplication.memory.SpatialMemory
import com.example.myapplication.overlay.OverlayView
import com.example.myapplication.pi.PhoneHotspot
import com.example.myapplication.pi.PiCameraSource
import com.example.myapplication.pi.PiStatus

class MainActivity : ComponentActivity() {
    private lateinit var piView: ImageView
    private lateinit var overlay: OverlayView
    private lateinit var statusText: TextView
    private lateinit var hotspotText: TextView

    private lateinit var detector: Detector
    private lateinit var audio: AudioFeedback
    private lateinit var memory: SpatialMemory
    private lateinit var piCamera: PiCameraSource

    // FPS measurement: counted over one-second windows (only touched inside detectLock)
    private var frameCount = 0                    // frames processed this second
    private var totalInferenceNs = 0L             // time spent in detect() this second
    private var windowStart = System.nanoTime()   // when this second started

    private val detectLock = Any()   // the detector must not run on two threads at once
    // Set in onDestroy; frames and status messages still on their way are then ignored.
    @Volatile private var destroyed = false

    private var launched = false
    // Say "can't reach the Pi" once, not on every retry.
    private var failureAnnounced = false
    private var lastHotspotFailure: PhoneHotspot.State.Failed? = null

    // Android 17+ needs this runtime permission to open connections to devices on the hotspot.
    private val localNetworkPermission: String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) Manifest.permission.ACCESS_LOCAL_NETWORK
        else null

    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            afterPermissions?.invoke()
            afterPermissions = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()

        detector = Detector(this)
        audio = AudioFeedback(this)
        memory = SpatialMemory(this)
        memory.attachDebugLongPress(overlay)   // temporary: long-press logs lastSeen("person") (TC-M04)
        piCamera = PiCameraSource(
            this,
            onFrame = { bitmap -> processFrame(bitmap) },
            onStatus = { status -> runOnUiThread { onPiStatus(status) } }
        )

        val launchPermissions = listOfNotNull(localNetworkPermission) + hotspotPermissions()
        withPermissions(launchPermissions) { startAtLaunch() }
    }

    override fun onStart() {
        super.onStart()
        if (launched) {
            startHotspot()   // turn it back on if it was stopped while we were away
            if (canUsePi()) piCamera.start()   // no-op while running; starts it if permission was granted meanwhile
        }
    }

    // The pipeline: every frame from the Pi camera comes here, on a background thread.
    private fun processFrame(bitmap: Bitmap) {
        if (destroyed) return
        synchronized(detectLock) {
            // 1. Object detection, timed with a stopwatch
            val start = System.nanoTime()
            val results = detector.detect(bitmap)
            val inferenceNs = System.nanoTime() - start
            if (destroyed) return

            // Add this frame to the current one-second window
            frameCount++
            totalInferenceNs += inferenceNs

            // Once a second has passed: work out averages, log them, show them, reset
            val now = System.nanoTime()
            val elapsedNs = now - windowStart
            if (elapsedNs >= 1_000_000_000L) {
                val fps = frameCount * 1_000_000_000.0 / elapsedNs
                val avgMs = totalInferenceNs / frameCount / 1_000_000.0

                Log.d("SVA_FPS", "FPS: %.1f | inference: %.1f ms".format(fps, avgMs))
                overlay.setStats(fps, avgMs)

                frameCount = 0
                totalInferenceNs = 0L
                windowStart = now
            }

            // Remember what was seen (spatial memory, saved at most once per second)
            memory.save(results)

            // 2. Depth estimation goes here later

            // 3. Draw on screen
            piView.post { piView.setImageBitmap(bitmap) }
            overlay.setResults(results, bitmap.width, bitmap.height)

            // 4. Audio feedback
            audio.update(results)
        }
    }

    /** Turns on the phone's network for the Pi, then looks for the Pi camera until it streams. */
    private fun startAtLaunch() {
        launched = true
        startHotspot()
        if (canUsePi()) {
            audio.announce("Looking for Pi camera")
            piCamera.start()   // runs in the background and keeps retrying until onDestroy
        } else {
            statusText.text = "Pi: Nearby devices permission is needed"
            audio.announce("Nearby devices permission is needed to reach the Pi camera")
        }
    }

    private fun hotspotPermissions() = if (PhoneHotspot.isEnabled()) PhoneHotspot.permissions() else emptyList()

    private fun startHotspot() {
        if (!PhoneHotspot.isEnabled()) return
        if (hotspotPermissions().all { hasPermission(it) }) {
            PhoneHotspot.start(this) { state -> onHotspotState(state) }
        } else {
            onHotspotState(PhoneHotspot.State.Failed("Nearby devices permission missing"))
        }
    }

    private fun onHotspotState(state: PhoneHotspot.State) {
        hotspotText.text = when (state) {
            PhoneHotspot.State.Off -> ""
            PhoneHotspot.State.Starting -> "Phone network: starting…"
            is PhoneHotspot.State.On -> "Phone network: \"${state.name}\" on (Wi-Fi Direct, not shown as Hotspot)"
            PhoneHotspot.State.NormalHotspotOn ->
                "Phone network: normal hotspot is on, the Pi can use it. Turn it off to use the app's own network"
            is PhoneHotspot.State.Failed -> "Phone network: ${state.reason}"
        }
        hotspotText.visibility = if (state == PhoneHotspot.State.Off) View.GONE else View.VISIBLE
        when (state) {
            is PhoneHotspot.State.On -> lastHotspotFailure = null
            is PhoneHotspot.State.Failed -> if (state != lastHotspotFailure) {
                lastHotspotFailure = state
                audio.announce(
                    if (state.reason == PhoneHotspot.WIFI_OFF) "Turn on Wi-Fi so the phone can start the Pi network."
                    else "Couldn't turn on the Pi network."
                )
            }
            else -> {}
        }
    }

    private fun onPiStatus(status: PiStatus) {
        if (destroyed) return
        statusText.text = when (status) {
            PiStatus.Searching -> "Pi: searching hotspot…"
            is PiStatus.Connecting -> "Pi: starting camera on ${status.host}…"
            is PiStatus.Streaming -> "Pi: streaming from ${status.host}"
            is PiStatus.Lost -> "Pi: lost (${status.reason}), reconnecting…"
            is PiStatus.Failed -> "Pi: ${status.reason}, retrying…"
        }
        when (status) {
            is PiStatus.Streaming -> {
                failureAnnounced = false
                audio.announce("Pi camera connected")
            }
            is PiStatus.Lost -> audio.announce("Pi camera disconnected. Reconnecting.")
            is PiStatus.Failed -> if (!failureAnnounced) {
                failureAnnounced = true
                audio.announce("Can't reach the Pi camera. Still trying.")
            }
            else -> {}
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canUsePi() = localNetworkPermission?.let { hasPermission(it) } ?: true

    /** Asks for any missing [permissions], then runs [then] (whether or not they were granted). */
    private fun withPermissions(permissions: List<String>, then: () -> Unit) {
        val missing = permissions.filterNot { hasPermission(it) }
        if (missing.isEmpty()) {
            then()
        } else {
            afterPermissions = then
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun buildUi() {
        // Shows the Pi frames, center-cropped to fill the screen the same way OverlayView maps the boxes
        piView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
        }
        overlay = OverlayView(this)

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            text = "Starting…"
        }
        hotspotText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
            visibility = View.GONE
        }
        val statusLines = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(hotspotText)
        }
        // Keep the status lines below the status bar / camera cutout
        ViewCompat.setOnApplyWindowInsetsListener(statusLines) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left + dp(12), bars.top + dp(8), bars.right + dp(12), dp(8))
            insets
        }

        val root = FrameLayout(this).apply {
            addView(piView)
            addView(this@MainActivity.overlay)   // plain `overlay` would be the FrameLayout's own
            addView(statusLines, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP
            ))
        }
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        piCamera.stop()
        audio.shutdown()
        memory.close()   // late frames are ignored after this (SpatialMemory checks `closed`)
        // Keep the network up through a screen rotation so the Pi doesn't drop off it
        if (!isChangingConfigurations) PhoneHotspot.stop()
    }
}
