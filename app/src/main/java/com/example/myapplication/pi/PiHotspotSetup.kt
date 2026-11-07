package com.example.myapplication.pi

import android.os.SystemClock
import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream

/**
 * Saves the phone's network on the Pi as NetworkManager Wi-Fi profiles (auto-connect, preferred
 * over other networks, power saving off), so the Pi joins it by itself at boot:
 *  - "sva-hotspot": HOTSPOT_NAME (normal hotspot, or the app's hotspot on Android 16 QPR2+)
 *  - "sva-direct":  PhoneHotspot.directName (the app's Wi-Fi Direct network on Android 10-16)
 * Writes only when something changed, and doesn't disturb the Pi's current connection.
 */
object PiHotspotSetup {
    private const val TAG = "PiHotspot"

    fun isConfigured() = PiConfig.HOTSPOT_NAME.isNotEmpty()

    /** True if the Pi now has the hotspot settings. Blocking: call off the main thread. */
    fun apply(ssh: Session): Boolean {
        val name = PiConfig.HOTSPOT_NAME
        val password = PiConfig.HOTSPOT_PASSWORD
        if (name.isEmpty()) return false
        if (password.length !in 8..63) {
            Log.w(TAG, "HOTSPOT_PASSWORD must be 8-63 characters; not saving it on the Pi")
            return false
        }

        val channel = ssh.openChannel("exec") as ChannelExec
        try {
            // Changing Wi-Fi settings needs root; sudo reads the login password from stdin
            val networks = listOf("sva-hotspot" to name, "sva-direct" to PhoneHotspot.directName)
            channel.setCommand("sudo -S -p '' sh -c " + quote(script(networks, password)))
            val stdout = channel.inputStream
            val stdin = channel.outputStream
            val errors = ByteArrayOutputStream()
            channel.setErrStream(errors)
            channel.connect(5000)
            stdin.write((PiConfig.PASSWORD + "\n").toByteArray())
            stdin.close()   // EOF, so a rejected password fails instead of waiting

            val result = stdout.bufferedReader().readText().trim()
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (!channel.isClosed && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)

            return if (channel.exitStatus == 0) {
                Log.i(TAG, "Phone network saved on the Pi: ${result.replace('\n', ',')}")
                true
            } else {
                Log.w(TAG, "Couldn't save hotspot on the Pi (exit ${channel.exitStatus}): $result $errors".trim())
                false
            }
        } finally {
            channel.disconnect()
        }
    }

    /** Shell script that creates/updates one profile per (profile name, Wi-Fi name) pair. */
    private fun script(networks: List<Pair<String, String>>, password: String): String {
        // save PROFILE SSID PASSWORD: values arrive as quoted arguments, never as shell code
        val save = """
            save() {
              set -- "${'$'}1" "${'$'}2" "${'$'}3" 802-11-wireless.ssid "${'$'}2" wifi-sec.key-mgmt wpa-psk wifi-sec.psk "${'$'}3" connection.autoconnect yes connection.autoconnect-priority 100 802-11-wireless.powersave 2
              if nmcli -t -e no -f NAME connection show | grep -qxF "${'$'}1"; then
                [ "${'$'}(nmcli -t -e no -g 802-11-wireless.ssid connection show "${'$'}1")" = "${'$'}2" ] &&
                [ "${'$'}(nmcli -s -t -e no -g 802-11-wireless-security.psk connection show "${'$'}1")" = "${'$'}3" ] &&
                { echo "${'$'}1 unchanged"; return 0; }
                p="${'$'}1"; shift 3; nmcli connection modify "${'$'}p" "${'$'}@" >/dev/null && echo "${'$'}p updated"
              else
                p="${'$'}1"; shift 3; nmcli connection add type wifi con-name "${'$'}p" ifname wlan0 "${'$'}@" >/dev/null && echo "${'$'}p added"
              fi
            }
        """.trimIndent()
        val calls = networks.joinToString("\n") { (profile, ssid) ->
            "save ${quote(profile)} ${quote(ssid)} ${quote(password)} || failed=1"
        }
        return "command -v nmcli >/dev/null || { echo 'nmcli not found: this Pi does not use NetworkManager'; exit 3; }\n" +
            "$save\nfailed=0\n$calls\nexit \$failed"
    }

    /** Single-quotes [s] for the shell, so any characters in a name or password are safe. */
    private fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
