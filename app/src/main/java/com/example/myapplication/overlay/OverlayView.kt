package com.example.myapplication.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.example.myapplication.detection.Detection

class OverlayView(context: Context) : View(context) {
    private var detections: List<Detection> = emptyList()
    private var imgW = 1
    private var imgH = 1

    private val boxPaint = Paint().apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE; textSize = 42f; isFakeBoldText = true
    }
    private val bgPaint = Paint().apply { color = Color.argb(160, 0, 150, 0) }

    // Speed stats shown in the top-left corner
    private var fps = 0.0
    private var inferenceMs = 0.0
    private val statsPaint = Paint().apply {
        color = Color.YELLOW; textSize = 40f; isFakeBoldText = true
    }
    private val statsBgPaint = Paint().apply { color = Color.argb(170, 0, 0, 0) }

    fun setResults(dets: List<Detection>, imageWidth: Int, imageHeight: Int) {
        detections = dets; imgW = imageWidth; imgH = imageHeight
        postInvalidate()
    }

    // Called once a second from MainActivity with the latest numbers
    fun setStats(fps: Double, inferenceMs: Double) {
        this.fps = fps
        this.inferenceMs = inferenceMs
        postInvalidate()   // safe to call from the background thread
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = maxOf(width.toFloat() / imgW, height.toFloat() / imgH)
        val dx = (width - imgW * scale) / 2
        val dy = (height - imgH * scale) / 2

        for (d in detections) {
            val l = d.box.left * imgW * scale + dx
            val t = d.box.top * imgH * scale + dy
            val r = d.box.right * imgW * scale + dx
            val b = d.box.bottom * imgH * scale + dy
            canvas.drawRect(l, t, r, b, boxPaint)
            val text = "${d.label} ${(d.score * 100).toInt()}%"
            canvas.drawRect(l, t - 50f, l + textPaint.measureText(text) + 16f, t, bgPaint)
            canvas.drawText(text, l + 8f, t - 12f, textPaint)
        }

        // Stats drawn last, so they stay on top of the boxes
        val stats = "FPS: %.1f   Inference: %.0f ms".format(fps, inferenceMs)
        val textWidth = statsPaint.measureText(stats)
        canvas.drawRect(16f, 16f, 16f + textWidth + 32f, 86f, statsBgPaint)
        canvas.drawText(stats, 32f, 66f, statsPaint)
    }
}