package com.example.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class Detection(val box: RectF, val label: String, val score: Float)

class Detector(context: Context) {
    private val interpreter: Interpreter
    private val labels: List<String>
    private val inputSize: Int
    private var channelsFirst = false
    init {
        val model = loadModel(context, "yolov8n.tflite")
        interpreter = Interpreter(model, Interpreter.Options().apply { setNumThreads(4) })
        labels = context.assets.open("labels.txt").bufferedReader()
            .readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val inShape = interpreter.getInputTensor(0).shape()
        channelsFirst = inShape[1] == 3
        inputSize = if (channelsFirst) inShape[2] else inShape[1]
    }

    private fun loadModel(context: Context, name: String): MappedByteBuffer {
        val fd = context.assets.openFd(name)
        FileInputStream(fd.fileDescriptor).use { stream ->
            return stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        }
    }

    fun detect(bitmap: Bitmap, threshold: Float = 0.45f): List<Detection> {
        val resized = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val input = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        resized.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (channelsFirst) {
            // [1, 3, H, W]: all red values, then all green, then all blue
            for (p in pixels) input.putFloat(((p shr 16) and 0xFF) / 255f)
            for (p in pixels) input.putFloat(((p shr 8) and 0xFF) / 255f)
            for (p in pixels) input.putFloat((p and 0xFF) / 255f)
        } else {
            // [1, H, W, 3]: red, green, blue for each pixel
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xFF) / 255f)
                input.putFloat(((p shr 8) and 0xFF) / 255f)
                input.putFloat((p and 0xFF) / 255f)
            }
        }
        input.rewind()

        val shape = interpreter.getOutputTensor(0).shape()
        val output = Array(1) { Array(shape[1]) { FloatArray(shape[2]) } }
        interpreter.run(input, output)

        val transposed = shape[1] > shape[2]
        val numBoxes = if (transposed) shape[1] else shape[2]
        val numChannels = if (transposed) shape[2] else shape[1]
        fun v(c: Int, i: Int) = if (transposed) output[0][i][c] else output[0][c][i]

        val results = mutableListOf<Detection>()
        for (i in 0 until numBoxes) {
            var best = 0f
            var bestClass = -1
            for (c in 4 until numChannels) {
                val s = v(c, i)
                if (s > best) { best = s; bestClass = c - 4 }
            }
            if (best < threshold || bestClass < 0) continue

            var cx = v(0, i); var cy = v(1, i); var w = v(2, i); var h = v(3, i)
            if (cx > 1.5f) {
                cx /= inputSize; cy /= inputSize; w /= inputSize; h /= inputSize
            }
            val box = RectF(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
            val label = labels.getOrElse(bestClass) { "object" }
            results.add(Detection(box, label, best))
        }
        return nms(results)
    }

    private fun nms(dets: List<Detection>, iouThreshold: Float = 0.5f): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }.toMutableList()
        val kept = mutableListOf<Detection>()
        while (sorted.isNotEmpty()) {
            val top = sorted.removeAt(0)
            kept.add(top)
            sorted.removeAll { it.label == top.label && iou(it.box, top.box) > iouThreshold }
        }
        return kept
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left); val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right); val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }
}