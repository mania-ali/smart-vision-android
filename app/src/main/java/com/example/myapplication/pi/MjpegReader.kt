package com.example.myapplication.pi

import java.io.EOFException
import java.io.InputStream

/**
 * Splits an MJPEG byte stream (JPEG images back to back) into single JPEG frames
 * by finding each image's start (FF D8) and end (FF D9) markers.
 */
class MjpegReader(private val input: InputStream) {
    private val chunk = ByteArray(64 * 1024)
    private var frame = ByteArray(256 * 1024)
    private var length = 0

    /**
     * Reads until the stream fails or ends (throws). [onFrame] gets a reused buffer
     * and the frame's length, so copy whatever you keep.
     */
    fun readFrames(onFrame: (ByteArray, Int) -> Unit) {
        var inFrame = false
        var prev = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) throw EOFException("Pi closed the video stream")
            var start = 0   // where the not-yet-copied part of the current frame begins in chunk
            for (i in 0 until n) {
                val b = chunk[i].toInt() and 0xFF
                if (prev == 0xFF) {
                    if (b == SOI) {
                        // New image (also restarts if the last one was cut off)
                        inFrame = true
                        length = 0
                        append(0xFF.toByte())
                        start = i
                    } else if (b == EOI && inFrame) {
                        append(chunk, start, i + 1 - start)
                        onFrame(frame, length)
                        inFrame = false
                    }
                }
                prev = b
            }
            if (inFrame) append(chunk, start, n - start)
            if (length > MAX_FRAME_BYTES) {   // not a JPEG stream: drop and resync
                inFrame = false
                length = 0
            }
        }
    }

    private fun append(b: Byte) {
        ensureCapacity(length + 1)
        frame[length++] = b
    }

    private fun append(src: ByteArray, offset: Int, count: Int) {
        ensureCapacity(length + count)
        System.arraycopy(src, offset, frame, length, count)
        length += count
    }

    private fun ensureCapacity(size: Int) {
        if (size > frame.size) frame = frame.copyOf(maxOf(size, frame.size * 2))
    }

    private companion object {
        const val SOI = 0xD8
        const val EOI = 0xD9
        const val MAX_FRAME_BYTES = 8 * 1024 * 1024
    }
}
