package com.example.myapplication.camera

/**
 * Anything that feeds camera frames into the detection pipeline:
 * the phone's own camera ([CameraController]) or the Pi stream (PiCameraSource).
 * Frames are delivered through the callback each source takes in its constructor.
 */
interface FrameSource {
    fun start()
    fun stop()
}
