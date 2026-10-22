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

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var detector: Detector
    private lateinit var camera: CameraController

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
        camera = CameraController(this, previewView) { bitmap -> processFrame(bitmap) }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) camera.start()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // The pipeline: every camera frame comes here.
    private fun processFrame(bitmap: Bitmap) {
        // 1. Object detection
        val results = detector.detect(bitmap)



        // 3. Draw on screen
        overlay.setResults(results, bitmap.width, bitmap.height)


    }

    override fun onDestroy() {
        super.onDestroy()
        camera.stop()
    }
}