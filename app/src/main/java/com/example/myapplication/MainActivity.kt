package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.myapplication.audio.AudioFeedback
import com.example.myapplication.camera.CameraController
import com.example.myapplication.detection.Detector
import com.example.myapplication.overlay.OverlayView

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var detector: Detector
    private lateinit var camera: CameraController
    private lateinit var audio: AudioFeedback

    // FPS measurement: counted over one-second windows
    private var frameCount = 0                    // frames processed this second
    private var totalInferenceNs = 0L             // time spent in detect() this second
    private var windowStart = System.nanoTime()   // when this second started

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) camera.start()
            else Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlay = OverlayView(this)
        val root = FrameLayout(this).apply {
            addView(previewView)
            addView(this@MainActivity.overlay)
        }
        setContentView(root)

        detector = Detector(this)
        audio = AudioFeedback(this)
        camera = CameraController(this, previewView) { bitmap -> processFrame(bitmap) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) camera.start()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The pipeline: every camera frame comes here.
    private fun processFrame(bitmap: Bitmap) {
        // 1. Object detection, timed with a stopwatch
        val start = System.nanoTime()
        val results = detector.detect(bitmap)
        val inferenceNs = System.nanoTime() - start

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

        // 2. Depth estimation goes here later

        // 3. Draw on screen
        overlay.setResults(results, bitmap.width, bitmap.height)

        // 4. Audio feedback
        audio.update(results)
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
        audio.shutdown()
    }
}