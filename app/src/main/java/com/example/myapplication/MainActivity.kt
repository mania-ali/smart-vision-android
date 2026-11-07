package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.myapplication.camera.CameraController
import com.example.myapplication.detection.Detector
import com.example.myapplication.overlay.OverlayView
import com.example.myapplication.audio.AudioFeedback
import com.example.myapplication.memory.SpatialMemory   // ADDED 
import android.util.Log
class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var detector: Detector
    private lateinit var camera: CameraController
    private lateinit var audio: AudioFeedback
    private lateinit var memory: SpatialMemory          // ADDED 

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
        memory = SpatialMemory(this)                        // ADDED 
        memory.attachDebugLongPress(overlay)                // ADDED - temporary, for TC-M04
        camera = CameraController(this, previewView) { bitmap -> processFrame(bitmap) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) camera.start()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The pipeline: every camera frame comes here.
    private fun processFrame(bitmap: Bitmap) {
        // 1. Object detection
        val results = detector.detect(bitmap)

        // 2. Remember what was seen                        // ADDED 
        memory.save(results)                                // ADDED

        // 3. Draw on screen
        overlay.setResults(results, bitmap.width, bitmap.height)

        // 4. Audio feedback
        audio.update(results)
    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
        audio.shutdown()
        memory.close()                                      // ADDED 
    }
}