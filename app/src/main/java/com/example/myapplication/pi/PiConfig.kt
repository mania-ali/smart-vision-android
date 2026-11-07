package com.example.myapplication.pi

/**
 * Settings for the Raspberry Pi camera. Edit these to match your Pi.
 * One-time Pi setup (hotspot auto-join, SSH, camera) is described in pi-setup/README.md.
 */
object PiConfig {
    // SSH login: the username and password you set for the Pi (e.g. in Raspberry Pi Imager)
    const val USERNAME = "picamera"
    const val PASSWORD = "1234"
    const val SSH_PORT = 22

    // The Wi-Fi network between the phone and the Pi (WPA2, password 8-63 characters).
    // Whenever the app connects to the Pi, it saves these on the Pi so the Pi joins by itself at
    // boot. The first time, put the Pi and phone on the same Wi-Fi (e.g. at home) and open the app.
    // Leave HOTSPOT_NAME empty to set up the Pi's Wi-Fi yourself.
    const val HOTSPOT_NAME = "picamera"
    const val HOTSPOT_PASSWORD = "12345678"

    // true: the app turns on this network itself when it starts (see PhoneHotspot). On Android
    // 16 QPR2+ it has exactly HOTSPOT_NAME; on Android 10-16 Android forces a Wi-Fi Direct name,
    // "DIRECT-SV-" + HOTSPOT_NAME. false: turn on the phone's normal hotspot by hand, named HOTSPOT_NAME.
    const val AUTO_HOTSPOT = true

    // Leave null to find the Pi automatically on the phone's hotspot.
    // Set an IP or hostname (e.g. "192.168.43.50") to always try that first.
    val FIXED_HOST: String? = null

    // Video stream (MJPEG over TCP, started by the app over SSH)
    const val STREAM_PORT = 8888
    const val WIDTH = 720
    const val HEIGHT = 540
    const val FPS = 15
    const val JPEG_QUALITY = 70     // 1-100: lower = smaller frames, less delay on weak Wi-Fi
    const val ROTATE_180 = false    // set true if the camera is mounted upside down
}
