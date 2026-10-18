package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var detector: Detector
    private var tts: TextToSpeech? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var lastSpoken = ""
    private var lastSpokenTime = 0L

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
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
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.US
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) startCamera()
        else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val rotation = imageProxy.imageInfo.rotationDegrees
                var bitmap = imageProxy.toBitmap()
                imageProxy.close()
                if (rotation != 0) {
                    val m = Matrix().apply { postRotate(rotation.toFloat()) }
                    bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                }
                val results = detector.detect(bitmap)
                overlay.setResults(results, bitmap.width, bitmap.height)
                announce(results)
            }

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun announce(results: List<Detection>) {
        val top = results.maxByOrNull { it.score } ?: return
        val now = System.currentTimeMillis()
        if (top.label != lastSpoken || now - lastSpokenTime > 3000) {
            if (now - lastSpokenTime > 1500) {
                tts?.speak("${top.label} ahead", TextToSpeech.QUEUE_FLUSH, null, "det")
                lastSpoken = top.label
                lastSpokenTime = now
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        tts?.shutdown()
    }
}