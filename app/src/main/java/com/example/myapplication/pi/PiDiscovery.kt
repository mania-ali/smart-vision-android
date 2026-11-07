package com.example.myapplication.pi

import android.content.Context
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Finds the Pi on the phone's hotspot.
 *
 * Android gives hotspot clients a random address, and apps can't read the hotspot's
 * client list, so we try (in order): the address that worked last time, PiConfig.FIXED_HOST,
 * then every address on the phone's local subnets that answers on the SSH port.
 */
class PiDiscovery(context: Context) {
    private val prefs = context.getSharedPreferences("pi_camera", Context.MODE_PRIVATE)

    fun remember(host: String) = prefs.edit().putString(KEY_LAST_HOST, host).apply()

    /** Addresses with an open SSH port, most likely Pi first. Computed lazily. */
    fun candidates(): Sequence<String> = sequence {
        val tried = HashSet<String>()
        val quick = listOfNotNull(prefs.getString(KEY_LAST_HOST, null), PiConfig.FIXED_HOST)
        for (host in quick) {
            if (tried.add(host) && sshPortOpen(host, QUICK_TIMEOUT_MS)) yield(host)
        }
        for (host in scanLocalSubnets()) {
            if (tried.add(host)) yield(host)
        }
    }

    /** Probes x.y.z.1-254 on each local subnet in parallel (about 1 s per subnet). */
    private fun scanLocalSubnets(): List<String> {
        val hosts = localSubnets().flatMap { (prefix, ownLastByte) ->
            (1..254).filter { it != ownLastByte }.map { "$prefix.$it" }
        }
        if (hosts.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(SCAN_THREADS)
        try {
            val probes = hosts.map { host ->
                Callable { if (sshPortOpen(host, SCAN_TIMEOUT_MS)) host else null }
            }
            return pool.invokeAll(probes).mapNotNull { runCatching { it.get() }.getOrNull() }
        } finally {
            pool.shutdownNow()
        }
    }

    /** "a.b.c" prefix and our own last byte for each private IPv4 address; hotspot first. */
    private fun localSubnets(): List<Pair<String, Int>> {
        val found = mutableListOf<Triple<Int, String, Int>>()   // priority, prefix, own byte
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (e: IOException) {
            emptyList()
        }
        for (iface in interfaces) {
            val name = iface.name.lowercase()
            val usable = try { iface.isUp && !iface.isLoopback } catch (e: IOException) { false }
            if (!usable || SKIP_INTERFACES.any { name.startsWith(it) }) continue
            for (address in iface.interfaceAddresses) {
                val ip = address.address as? Inet4Address ?: continue
                if (!ip.isSiteLocalAddress) continue
                val b = ip.address.map { it.toInt() and 0xFF }
                val priority = if (HOTSPOT_HINTS.any { name.contains(it) }) 0 else 1
                found += Triple(priority, "${b[0]}.${b[1]}.${b[2]}", b[3])
            }
        }
        return found.sortedBy { it.first }.distinctBy { it.second }.map { it.second to it.third }
    }

    private fun sshPortOpen(host: String, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, PiConfig.SSH_PORT), timeoutMs) }
        true
    } catch (e: IOException) {
        false
    } catch (e: IllegalArgumentException) {
        false
    }

    private companion object {
        const val KEY_LAST_HOST = "last_host"
        const val QUICK_TIMEOUT_MS = 800
        // Generous: the Pi's Wi-Fi power saving can delay replies by a few hundred ms
        const val SCAN_TIMEOUT_MS = 500
        const val SCAN_THREADS = 128

        // Mobile data, VPN and other virtual interfaces: the Pi is never there
        val SKIP_INTERFACES = listOf("rmnet", "ccmni", "v4-", "clat", "tun", "ppp", "dummy", "ipsec", "ifb", "seth", "radio")
        // Typical names of the hotspot / Wi-Fi Direct interface on Android phones
        val HOTSPOT_HINTS = listOf("ap", "swlan", "softap", "p2p")
    }
}
