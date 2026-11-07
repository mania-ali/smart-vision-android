package com.example.myapplication.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/** The phone's back camera. Can be stopped and started again when switching to/from the Pi. */
class CameraController(
    private val owner: LifecycleOwner,
    private val previewView: PreviewView,
    private val onFrame: (Bitmap) -> Unit
) : FrameSource {
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    @Volatile private var running = false

    override fun start() {
        running = true
        val context = previewView.context
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            if (!running) return@addListener   // stopped before the camera was ready
            val provider = providerFuture.get().also { this.provider = it }

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
                if (running) onFrame(bitmap)
            }

            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    /** Releases the camera (main thread). [start] can be called again later. */
    override fun stop() {
        running = false
        provider?.unbindAll()
    }

    /** Final cleanup when the activity is destroyed. */
    fun shutdown() {
        stop()
        cameraExecutor.shutdown()
    }
}
