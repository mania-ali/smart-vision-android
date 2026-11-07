package com.example.myapplication.pi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiSsid
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Turns on a Wi-Fi network from the phone for the Pi to join, using PiConfig's HOTSPOT_NAME and
 * HOTSPOT_PASSWORD. Android doesn't let apps switch on the normal hotspot, so instead:
 *  - Android 16 QPR2 and newer: a local-only hotspot with exactly that name and password.
 *  - Android 10 to 16: a Wi-Fi Direct group. Android requires those names to start with
 *    "DIRECT-xy", so it is called [directName] ("DIRECT-SV-" + HOTSPOT_NAME).
 *  - Older: not possible; turn the normal hotspot on by hand.
 * The network has no internet; it only links the phone and the Pi. It stays on while the app
 * is running. The app saves both names on the Pi (PiHotspotSetup), so the Pi joins either one.
 * Use from the main thread.
 */
// MissingPermission: MainActivity asks for permissions() before start().
// StaticFieldLeak: only the application context is kept, which lives as long as the app anyway.
@SuppressLint("MissingPermission", "StaticFieldLeak")
object PhoneHotspot {
    sealed class State {
        data object Off : State()
        data object Starting : State()
        data class On(val name: String) : State()
        /** The phone's normal hotspot is on; most phones then can't run Wi-Fi Direct. Not an error. */
        data object NormalHotspotOn : State()
        data class Failed(val reason: String) : State()
    }

    const val WIFI_OFF = "Wi-Fi is off"
    private const val TAG = "PhoneHotspot"
    private const val RETRY_MS = 5000L
    private const val SLOW_RETRY_MS = 15_000L
    private val HOTSPOT_INTERFACE = Regex("(ap|softap|swlan|ap_br_ap)\\d+")

    /** Network name used for Wi-Fi Direct (max 32 bytes, as for any Wi-Fi name). */
    val directName: String = fitWifiName("DIRECT-SV-" + PiConfig.HOTSPOT_NAME)

    var state: State = State.Off
        private set

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var listener: ((State) -> Unit)? = null
    private var wanted = false

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null   // exact-name hotspot
    private var p2p: WifiP2pManager? = null                                    // Wi-Fi Direct
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false

    fun isEnabled() = PiConfig.AUTO_HOTSPOT && PiConfig.HOTSPOT_NAME.isNotEmpty()

    /** The local-only hotspot API with a custom name and password arrived in Android 16 QPR2 (36.1). */
    fun canUseExactName() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
        Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1

    /** Runtime permissions needed before [start]. */
    fun permissions(): List<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> listOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        else -> emptyList()
    }

    /** Turns the network on unless it already is. Safe to call on every app start. */
    fun start(context: Context, onState: (State) -> Unit) {
        this.context = context.applicationContext
        listener = onState
        onState(state)
        wanted = true
        if (state is State.On || state is State.Starting) return
        if (PiConfig.HOTSPOT_PASSWORD.length !in 8..63) {
            set(State.Failed("HOTSPOT_PASSWORD must be 8-63 characters"))
            return
        }
        when {
            canUseExactName() -> startExactName()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startWifiDirect()
            else -> set(State.Failed("needs Android 10+, turn the hotspot on by hand"))
        }
    }

    fun stop() {
        wanted = false
        main.removeCallbacksAndMessages(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) reservation?.close()
        reservation = null
        channel?.let { ch ->
            p2p?.removeGroup(ch, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ch.close()
        }
        channel = null
        if (receiverRegistered) {
            context?.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        set(State.Off)
        listener = null
    }

    private fun set(newState: State) {
        if (newState == state) return
        state = newState
        Log.i(TAG, "Phone network: $newState")
        listener?.invoke(newState)
    }

    // ---- Android 16 QPR2+: local-only hotspot with the exact name ----

    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun startExactName() {
        if (Build.VERSION.SDK_INT_FULL < Build.VERSION_CODES_FULL.BAKLAVA_1) return startWifiDirect()
        val ctx = context ?: return
        val wifi = ctx.getSystemService(WifiManager::class.java) ?: return startWifiDirect()
        val config = try {
            SoftApConfiguration.Builder()
                .setWifiSsid(WifiSsid.fromBytes(PiConfig.HOTSPOT_NAME.toByteArray()))
                .setPassphrase(PiConfig.HOTSPOT_PASSWORD, SoftApConfiguration.SECURITY_TYPE_WPA2_PSK)
                .build()   // band defaults to 2.4 GHz, which every Pi supports
        } catch (e: IllegalArgumentException) {
            set(State.Failed("invalid hotspot name or password (${e.message})"))
            return
        }
        set(State.Starting)
        try {
            wifi.startLocalOnlyHotspotWithConfiguration(config, ctx.mainExecutor,
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                        if (!wanted) {
                            r.close()
                            return
                        }
                        reservation = r
                        set(State.On(PiConfig.HOTSPOT_NAME))
                    }

                    override fun onStopped() {
                        // Turned off from Settings, etc. start() runs again on the next app start.
                        reservation = null
                        if (wanted) set(State.Failed("hotspot was turned off"))
                    }

                    override fun onFailed(reason: Int) {
                        // e.g. the normal hotspot is already on. Wi-Fi Direct may still work.
                        Log.w(TAG, "Exact-name hotspot failed (${hotspotError(reason)}), trying Wi-Fi Direct")
                        if (wanted) startWifiDirect()
                    }
                })
        } catch (e: SecurityException) {
            set(State.Failed("Nearby devices permission missing"))
        }
    }

    // ---- Android 10+: Wi-Fi Direct group, works like a hotspot for the Pi ----

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startWifiDirect() {
        val ctx = context ?: return
        val manager = p2p ?: ctx.getSystemService(WifiP2pManager::class.java)
        if (manager == null) {
            set(State.Failed("this phone has no Wi-Fi Direct"))
            return
        }
        p2p = manager
        val ch = channel ?: manager.initialize(ctx, Looper.getMainLooper()) {
            channel = null   // the Wi-Fi service restarted
            if (wanted) retryLater()
        }
        if (ch == null) {
            set(State.Failed("Wi-Fi Direct unavailable"))
            return
        }
        channel = ch
        registerReceiver(ctx)
        // Checked first: on some phones the normal hotspot also switches Wi-Fi off
        if (normalHotspotOn()) {
            // The receiver starts again when the normal hotspot goes off and Wi-Fi Direct comes back
            set(State.NormalHotspotOn)
            retryLater(SLOW_RETRY_MS)
            return
        }
        if (!wifiEnabled()) {
            set(State.Failed(WIFI_OFF))   // the receiver starts again when Wi-Fi comes on
            return
        }
        set(State.Starting)
        try {
            manager.requestGroupInfo(ch) { group ->
                when {
                    !wanted -> {}
                    group == null -> createGroup(manager, ch, WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    group.isGroupOwner && group.networkName == directName -> set(State.On(directName))
                    else -> {
                        // Another Wi-Fi Direct connection holds the radio. The phone allows only one.
                        val next = { createGroup(manager, ch, WifiP2pConfig.GROUP_OWNER_BAND_2GHZ) }
                        manager.removeGroup(ch, actionListener(onSuccess = next, onFailure = { next() }))
                    }
                }
            }
        } catch (e: SecurityException) {
            set(State.Failed("Nearby devices permission missing"))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createGroup(manager: WifiP2pManager, ch: WifiP2pManager.Channel, band: Int) {
        if (!wanted) return
        val config = WifiP2pConfig.Builder()
            .setNetworkName(directName)
            .setPassphrase(PiConfig.HOTSPOT_PASSWORD)
            .setGroupOperatingBand(band)
            .enablePersistentMode(false)
            .build()
        try {
            manager.createGroup(ch, config, actionListener(
                onSuccess = { main.postDelayed({ confirmGroup(manager, ch) }, 1500) },
                onFailure = { reason ->
                    when {
                        normalHotspotOn() -> {
                            set(State.NormalHotspotOn)
                            retryLater(SLOW_RETRY_MS)
                        }
                        !wifiEnabled() -> set(State.Failed(WIFI_OFF))
                        // Phones on 5 GHz Wi-Fi sometimes can't add a 2.4 GHz network: let Android pick
                        band == WifiP2pConfig.GROUP_OWNER_BAND_2GHZ ->
                            createGroup(manager, ch, WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                        else -> {
                            set(State.Failed("Wi-Fi Direct ${directError(reason)}"))
                            retryLater()
                        }
                    }
                }))
        } catch (e: SecurityException) {
            set(State.Failed("Nearby devices permission missing"))
        }
    }

    /** createGroup's success only means "request accepted"; check the group really exists. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun confirmGroup(manager: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (!wanted) return
        manager.requestGroupInfo(ch) { group ->
            if (group != null && group.isGroupOwner && group.networkName == directName) {
                set(State.On(directName))
            } else if (wanted) {
                set(State.Failed("Wi-Fi Direct network didn't start"))
                retryLater()
            }
        }
    }

    private fun retryLater(delayMs: Long = RETRY_MS) {
        main.removeCallbacksAndMessages(null)
        main.postDelayed({
            if (wanted && state !is State.On && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startWifiDirect()
        }, delayMs)
    }

    /** True if the normal hotspot is on: Android names its interface ap0, softap0, swlan0, ap_br_ap0... */
    private fun normalHotspotOn(): Boolean = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { iface ->
            HOTSPOT_INTERFACE.matches(iface.name) && iface.isUp &&
                iface.inetAddresses.toList().any { it is Inet4Address }
        }
    } catch (e: Exception) {
        false
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (!wanted || reservation != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    when {
                        on && state !is State.On && state !is State.Starting -> startWifiDirect()
                        // Wi-Fi Direct went off: Wi-Fi switched off, or the normal hotspot came on.
                        // Look again shortly, once the hotspot's interface is up, to tell which.
                        !on -> retryLater(2000)
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = IntentCompat.getParcelableExtra(
                        intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java
                    )
                    if (state is State.On && info != null && !info.groupFormed) {
                        // Our network went away (e.g. Wi-Fi was toggled): bring it back
                        set(State.Starting)
                        main.postDelayed({ if (wanted) startWifiDirect() }, 2000)
                    }
                }
            }
        }
    }

    private fun registerReceiver(ctx: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(ctx, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun wifiEnabled() = context?.getSystemService(WifiManager::class.java)?.isWifiEnabled ?: false

    private fun actionListener(onSuccess: () -> Unit, onFailure: (Int) -> Unit) =
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() = onSuccess()
            override fun onFailure(reason: Int) = onFailure(reason)
        }

    private fun directError(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this phone"
        WifiP2pManager.BUSY -> "busy (is the normal hotspot on?)"
        else -> "error $reason"
    }

    private fun hotspotError(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "normal hotspot on, or app not in foreground"
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "no free channel"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "hotspots blocked on this phone"
        else -> "error $reason"
    }

    private fun fitWifiName(name: String): String {
        var s = name
        while (s.toByteArray().size > 32) s = s.dropLast(1)
        return s
    }
}
