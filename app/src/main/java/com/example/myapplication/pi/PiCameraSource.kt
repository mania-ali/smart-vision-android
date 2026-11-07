package com.example.myapplication.pi

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.example.myapplication.camera.FrameSource
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

sealed class PiStatus {
    data object Searching : PiStatus()
    data class Connecting(val host: String) : PiStatus()
    data class Streaming(val host: String) : PiStatus()
    /** Video was flowing and stopped; reconnecting. */
    data class Lost(val reason: String) : PiStatus()
    /** An attempt failed before any video arrived; retrying. */
    data class Failed(val reason: String) : PiStatus()
}

/**
 * The Pi camera as a frame source:
 *  1. finds the Pi on the hotspot ([PiDiscovery]) and logs in over SSH,
 *  2. runs rpicam-vid on the Pi, which serves MJPEG on a TCP port,
 *  3. reads that stream and hands only the newest frame to [onFrame], so delay never builds up.
 * Keeps retrying until [stop]. Both callbacks run on background threads.
 */
class PiCameraSource(
    context: Context,
    private val onFrame: (Bitmap) -> Unit,
    private val onStatus: (PiStatus) -> Unit
) : FrameSource {
    private val discovery = PiDiscovery(context.applicationContext)
    private var run: StreamRun? = null
    @Volatile private var hotspotSaved = false

    override fun start() {
        if (run == null) run = StreamRun().also { it.start() }
    }

    override fun stop() {
        run?.stop()
        run = null
    }

    /** Saves PiConfig's hotspot on the Pi once per launch, in parallel so the video isn't delayed. */
    private fun saveHotspotInBackground(ssh: Session) {
        if (hotspotSaved || !PiHotspotSetup.isConfigured()) return
        thread(name = "pi-hotspot", isDaemon = true) {
            try {
                if (PiHotspotSetup.apply(ssh)) hotspotSaved = true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save hotspot settings on the Pi", e)
            }
        }
    }

    /** One start()..stop() cycle. Each start gets a fresh one, so a run that is shutting down can't come back. */
    private inner class StreamRun {
        @Volatile private var active = true
        @Volatile private var socket: Socket? = null
        @Volatile private var gotVideo = false
        private val latest = ArrayBlockingQueue<ByteArray>(1)
        private val networkThread = thread(start = false, name = "pi-network") { networkLoop() }
        private val decodeThread = thread(start = false, name = "pi-decode") { decodeLoop() }

        fun start() {
            networkThread.start()
            decodeThread.start()
        }

        /** Safe on the main thread: no network I/O here; SSH is closed by the network thread. */
        fun stop() {
            active = false
            closeQuietly(socket)   // unblocks a waiting read
            networkThread.interrupt()
            decodeThread.interrupt()
        }

        private fun status(s: PiStatus) {
            if (active) onStatus(s)
        }

        private fun networkLoop() {
            var failures = 0
            while (active) {
                gotVideo = false
                try {
                    streamOnce()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (!active) break
                    Log.w(TAG, "Pi stream attempt failed", e)
                    val reason = describe(e)
                    status(if (gotVideo) PiStatus.Lost(reason) else PiStatus.Failed(reason))
                }
                failures = if (gotVideo) 0 else failures + 1
                try {
                    // After a minute or so of not finding the Pi, scan less often
                    Thread.sleep(if (failures < 15) RETRY_DELAY_MS else SLOW_RETRY_DELAY_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }

        /** Connects and streams until something fails (always ends by throwing). */
        private fun streamOnce() {
            status(PiStatus.Searching)
            val (host, ssh) = findAndLogin()
            var channel: ChannelExec? = null
            try {
                status(PiStatus.Connecting(host))
                saveHotspotInBackground(ssh)
                channel =(ssh.openChannel("exec") as ChannelExec).apply {
                    setCommand(streamCommand())
                    setPty(true)   // so closing the session hangs up (stops) rpicam-vid on the Pi
                }
                val piOutput = OutputTail(channel.inputStream)
                channel.connect(SSH_TIMEOUT_MS)

                val stream = openStream(host, channel, piOutput)
                discovery.remember(host)
                MjpegReader(stream.getInputStream()).readFrames { buffer, length ->
                    if (!active) throw InterruptedIOException("stopped")
                    if (!gotVideo) {
                        gotVideo = true
                        status(PiStatus.Streaming(host))
                    }
                    // Keep only the newest frame: if detection is slower than the camera,
                    // older frames are dropped instead of queueing up as delay.
                    val jpeg = buffer.copyOf(length)
                    if (!latest.offer(jpeg)) {
                        latest.poll()
                        latest.offer(jpeg)
                    }
                }
            } finally {
                closeQuietly(socket)
                socket = null
                channel?.disconnect()
                ssh.disconnect()
            }
        }

        private fun findAndLogin(): Pair<String, Session> {
            var lastError: IOException? = null
            for (host in discovery.candidates()) {
                if (!active) throw InterruptedIOException("stopped")
                try {
                    return host to login(host)
                } catch (e: JSchException) {
                    // Could be another device on the hotspot with SSH; try the next one
                    Log.w(TAG, "SSH to $host failed", e)
                    lastError = if (e.message.orEmpty().contains("auth", ignoreCase = true)) {
                        IOException("SSH login to $host rejected, check username/password in PiConfig", e)
                    } else {
                        IOException("SSH to $host failed: ${e.message}", e)
                    }
                }
            }
            throw lastError ?: IOException("no Pi found on the hotspot")
        }

        /** Connects to rpicam-vid's TCP port, retrying while the Pi camera starts up. */
        private fun openStream(host: String, channel: ChannelExec, piOutput: OutputTail): Socket {
            val deadline = SystemClock.elapsedRealtime() + STREAM_START_TIMEOUT_MS
            Thread.sleep(STREAM_FIRST_TRY_DELAY_MS)
            while (true) {
                if (!active) throw InterruptedIOException("stopped")
                if (channel.isClosed) {
                    val why = piOutput.lastLine().ifBlank { "exit code ${channel.exitStatus}" }
                    throw IOException("Pi camera didn't start: $why")
                }
                val s = Socket()
                socket = s   // lets stop() abort the connect
                try {
                    s.connect(InetSocketAddress(host, PiConfig.STREAM_PORT), 1000)
                    s.soTimeout = READ_TIMEOUT_MS   // no video for this long = connection is dead
                    return s
                } catch (e: IOException) {
                    closeQuietly(s)
                    if (SystemClock.elapsedRealtime() > deadline) {
                        throw IOException("Pi video port didn't open (${e.message})")
                    }
                    Thread.sleep(200)
                }
            }
        }

        private fun decodeLoop() {
            while (active) {
                val jpeg = try {
                    latest.take()
                } catch (e: InterruptedException) {
                    break
                }
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                if (active) onFrame(bitmap)
            }
        }
    }

    /**
     * Drains the Pi command's output (unread output would eventually block rpicam-vid)
     * and keeps the end of it to explain failures, e.g. "no cameras available".
     */
    private class OutputTail(input: InputStream) {
        private val text = StringBuilder()
        private val reader = thread(name = "pi-output", isDaemon = true) {
            try {
                val buf = ByteArray(1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(text) {
                        text.append(String(buf, 0, n))
                        if (text.length > 2000) text.delete(0, text.length - 2000)
                    }
                }
            } catch (e: IOException) {
                // channel closed
            }
        }

        fun lastLine(): String {
            reader.join(300)   // let the final error message arrive
            return synchronized(text) { text.lines().lastOrNull { it.isNotBlank() }?.trim().orEmpty() }
        }
    }

    private companion object {
        const val TAG = "PiCamera"
        const val RETRY_DELAY_MS = 1500L
        const val SLOW_RETRY_DELAY_MS = 5000L
        const val SSH_TIMEOUT_MS = 5000
        const val STREAM_FIRST_TRY_DELAY_MS = 500L
        const val STREAM_START_TIMEOUT_MS = 10_000L
        const val READ_TIMEOUT_MS = 3000

        fun login(host: String): Session =
            JSch().getSession(PiConfig.USERNAME, host, PiConfig.SSH_PORT).apply {
                setPassword(PiConfig.PASSWORD.toByteArray())
                // The Pi's host key isn't known in advance (it changes when the SD card is re-flashed)
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password,keyboard-interactive")
                // Prefer algorithms that Android's built-in crypto supports
                setConfig(
                    "kex",
                    "ecdh-sha2-nistp256,ecdh-sha2-nistp384,ecdh-sha2-nistp521," +
                        "diffie-hellman-group14-sha256,diffie-hellman-group16-sha512"
                )
                setConfig("server_host_key", "ecdsa-sha2-nistp256,rsa-sha2-512,rsa-sha2-256,ssh-ed25519")
                setServerAliveInterval(2000)
                setServerAliveCountMax(3)
                connect(SSH_TIMEOUT_MS)
            }

        /**
         * Shell command run on the Pi: free the camera from any earlier stream, then serve
         * MJPEG over TCP to the first client that connects (the app). MJPEG has the lowest delay
         * here: every frame is a complete JPEG, so nothing is buffered for decoding and frames
         * can be dropped freely. rpicam-vid is called libcamera-vid on older Raspberry Pi OS.
         */
        fun streamCommand(): String {
            val c = PiConfig
            val rotate = if (c.ROTATE_180) " --rotation 180" else ""
            return "pkill -x rpicam-vid; pkill -x libcamera-vid; sleep 0.3; " +
                "CAM=\$(command -v rpicam-vid || command -v libcamera-vid) " +
                "|| { echo 'rpicam-vid is not installed on the Pi'; exit 127; }; " +
                "exec \"\$CAM\" -t 0 -n -v 0 --codec mjpeg --width ${c.WIDTH} --height ${c.HEIGHT} " +
                "--framerate ${c.FPS} -q ${c.JPEG_QUALITY}$rotate --listen -o tcp://0.0.0.0:${c.STREAM_PORT}"
        }

        fun describe(e: Exception): String = when (e) {
            is SocketTimeoutException -> "no video for ${READ_TIMEOUT_MS / 1000} s"
            else -> e.message ?: e.javaClass.simpleName
        }

        fun closeQuietly(socket: Socket?) {
            try {
                socket?.close()
            } catch (e: IOException) {
                // already closed
            }
        }
    }
}
