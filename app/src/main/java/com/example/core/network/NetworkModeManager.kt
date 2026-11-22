package com.example.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.core.common.HotspotClientStatus
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkInterfaceDescriptor
import com.example.core.common.NetworkModeSnapshot
import com.example.core.common.NetworkTransportMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Locale

data class MulticastProbeResult(
    val joinable: Boolean,
    val interfaceSupportsMulticast: Boolean,
    val detail: String
)

/**
 * Dual-Mode Android Network Manager supporting BOTH:
 * - MODE 1: Conventional Wi-Fi (Phone + Chromecast TV connected to the same Wi-Fi router)
 * - MODE 2: Android Mobile Hotspot (Phone acts as Wi-Fi hotspot; Chromecast TV joins hotspot)
 *
 * Architectural guarantees:
 * - Uses official Android APIs (`ConnectivityManager`, `NetworkCapabilities`, `NetworkCallback`,
 *   `LinkProperties`, `WifiManager`, `MulticastLock`, `NetworkInterface`).
 * - NEVER hardcodes fixed hotspot IPs (like 192.168.43.1) or subnets; dynamically inspects actual
 *   active RFC1918 interface addresses.
 * - NEVER uses hidden Android APIs to programmatically toggle or configure tethering.
 * - Acquires `WifiManager.MulticastLock` ONLY when actively discovering Chromecast devices and
 *   releases it cleanly when discovery stops.
 */
class NetworkModeManager(private val appContext: Context) {

    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _snapshot = MutableStateFlow(NetworkModeSnapshot())
    val snapshot: StateFlow<NetworkModeSnapshot> = _snapshot.asStateFlow()

    @Volatile
    private var monitoring = false

    @Volatile
    private var multicastLock: WifiManager.MulticastLock? = null

    @Volatile
    private var lastMulticastProbe: MulticastProbeResult? = null

    @Volatile
    private var lastKnownHotspotBroadcastActive: Boolean? = null

    @Volatile
    private var lastObservedDiscoveredCastCount: Int = 0

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAndNotify()
        override fun onLost(network: Network) = refreshAndNotify()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshAndNotify()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshAndNotify()
    }

    private val defaultNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshAndNotify()
        override fun onLost(network: Network) = refreshAndNotify()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = refreshAndNotify()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshAndNotify()
    }

    /**
     * Listens for standard Android Wi-Fi, SoftAP (Hotspot), and Tethering state broadcast events.
     * Note: `WIFI_AP_STATE_CHANGED` and `TETHER_STATE_CHANGED` are standard system broadcasts used
     * strictly for read-only observation (never for hidden tethering control).
     */
    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_WIFI_AP_STATE_CHANGED -> {
                    val apState = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                    // 13 == WIFI_AP_STATE_ENABLED, 11 == WIFI_AP_STATE_DISABLED, 14 == FAILED
                    lastKnownHotspotBroadcastActive = when (apState) {
                        13 -> true
                        10, 11, 14 -> false
                        else -> lastKnownHotspotBroadcastActive
                    }
                    refreshAndNotify()
                }

                ACTION_TETHER_STATE_CHANGED -> {
                    val activeTethers = intent.getStringArrayListExtra(EXTRA_ACTIVE_TETHER).orEmpty()
                    if (activeTethers.any { isHotspotInterfaceName(it) }) {
                        lastKnownHotspotBroadcastActive = true
                    } else if (activeTethers.isEmpty()) {
                        lastKnownHotspotBroadcastActive = false
                    }
                    refreshAndNotify()
                }

                WifiManager.WIFI_STATE_CHANGED_ACTION -> refreshAndNotify()
            }
        }
    }

    fun startMonitoring() {
        if (monitoring) return
        val cm = connectivityManager
        if (cm != null) {
            val wifiReq = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            runCatching { cm.registerNetworkCallback(wifiReq, wifiNetworkCallback) }
            runCatching { cm.registerDefaultNetworkCallback(defaultNetworkCallback) }
        }

        val filter = IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(ACTION_WIFI_AP_STATE_CHANGED)
            addAction(ACTION_TETHER_STATE_CHANGED)
        }
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                systemStateReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }
        monitoring = true
        refresh()
    }

    fun stopMonitoring() {
        if (!monitoring) return
        runCatching { connectivityManager?.unregisterNetworkCallback(wifiNetworkCallback) }
        runCatching { connectivityManager?.unregisterNetworkCallback(defaultNetworkCallback) }
        runCatching { appContext.unregisterReceiver(systemStateReceiver) }
        releaseMulticastLock()
        monitoring = false
    }

    /**
     * Acquires a non-reference-counted `WifiManager.MulticastLock` ONLY when Chromecast mDNS
     * discovery is actively running. Does not guarantee OEM hotspot multicast forwarding;
     * callers should pair this with [probeMulticastReachability].
     */
    @Synchronized
    fun acquireMulticastLock(): Boolean {
        val wm = wifiManager ?: return false
        return runCatching {
            val lock = multicastLock ?: wm.createMulticastLock("LumaCast::CastMdnsDiscovery").apply {
                setReferenceCounted(false)
                multicastLock = this
            }
            if (!lock.isHeld) {
                lock.acquire()
            }
            refresh()
            lock.isHeld
        }.getOrDefault(false)
    }

    /**
     * Releases the held `MulticastLock` cleanly when discovery stops or times out so battery
     * and Wi-Fi power saving are preserved.
     */
    @Synchronized
    fun releaseMulticastLock() {
        runCatching {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        }
        if (monitoring) {
            refresh()
        }
    }

    fun isMulticastLockHeld(): Boolean = runCatching { multicastLock?.isHeld == true }.getOrDefault(false)

    fun updateDiscoveredCastCount(count: Int) {
        if (lastObservedDiscoveredCastCount != count) {
            lastObservedDiscoveredCastCount = count
            refresh()
        }
    }

    fun recordMulticastProbeResult(result: MulticastProbeResult) {
        lastMulticastProbe = result
        refresh()
    }

    /**
     * Probes whether the active local interface (Wi-Fi STA or Mobile Hotspot SoftAP) supports
     * multicast and allows joining the mDNS multicast group (`224.0.0.251:5353`).
     * This is a strictly local, non-intrusive socket check — never scans external hosts.
     */
    fun probeMulticastReachability(targetInterfaceName: String? = _snapshot.value.preferredBindInterface): MulticastProbeResult {
        val result = runCatching {
            val netIf = targetInterfaceName?.let { name ->
                runCatching { NetworkInterface.getByName(name) }.getOrNull()
            }
            val ifSupportsMulticast = netIf?.supportsMulticast() ?: true
            val mdnsGroup = InetAddress.getByName(MDNS_MULTICAST_GROUP)
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(0))
                socket.timeToLive = 1
                if (netIf != null) {
                    socket.joinGroup(InetSocketAddress(mdnsGroup, MDNS_PORT), netIf)
                    socket.leaveGroup(InetSocketAddress(mdnsGroup, MDNS_PORT), netIf)
                } else {
                    @Suppress("DEPRECATION")
                    socket.joinGroup(mdnsGroup)
                    @Suppress("DEPRECATION")
                    socket.leaveGroup(mdnsGroup)
                }
            }
            if (!ifSupportsMulticast) {
                MulticastProbeResult(
                    joinable = false,
                    interfaceSupportsMulticast = false,
                    detail = "Interface ${targetInterfaceName ?: "local"} reports multicast disabled by kernel/OEM firmware."
                )
            } else {
                MulticastProbeResult(
                    joinable = true,
                    interfaceSupportsMulticast = true,
                    detail = "mDNS group 224.0.0.251:5353 joinable on ${targetInterfaceName ?: "local interface"}."
                )
            }
        }.getOrElse { err ->
            MulticastProbeResult(
                joinable = false,
                interfaceSupportsMulticast = false,
                detail = "Unable to join mDNS multicast group on ${targetInterfaceName ?: "active interface"}: ${err.message ?: "blocked by OS/hotspot"}."
            )
        }
        recordMulticastProbeResult(result)
        return result
    }

    private fun refreshAndNotify() {
        refresh()
    }

    fun refresh(): NetworkModeSnapshot {
        val snap = readCurrentSnapshot()
        _snapshot.value = snap
        return snap
    }

    private fun readCurrentSnapshot(): NetworkModeSnapshot {
        val cm = connectivityManager
        val isWifiEnabled = runCatching { wifiManager?.isWifiEnabled == true }.getOrDefault(false)

        var staWifiConnected = false
        var staWifiIp: String? = null
        var staWifiIface: String? = null
        var isMobileDataEnabled = false
        var isVpnActive = false

        if (cm != null) {
            // Inspect active + all available networks via supported ConnectivityManager APIs
            val networks = runCatching { cm.allNetworks.toList() }.getOrElse {
                listOfNotNull(cm.activeNetwork)
            }
            for (net in networks) {
                val caps = runCatching { cm.getNetworkCapabilities(net) }.getOrNull() ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    isVpnActive = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    isMobileDataEnabled = true
                }
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                ) {
                    val lp = runCatching { cm.getLinkProperties(net) }.getOrNull()
                    val ipv4 = lp?.linkAddresses
                        ?.map { it.address }
                        ?.filterIsInstance<Inet4Address>()
                        ?.firstOrNull { isPrivateLanIpv4(it) }
                        ?.hostAddress
                    if (ipv4 != null) {
                        staWifiConnected = true
                        staWifiIp = ipv4
                        staWifiIface = lp.interfaceName
                    }
                }
            }
        }

        val enumerated = enumerateLocalLanInterfaces(staInterfaceName = staWifiIface)

        return classifyNetworkState(
            isWifiEnabled = isWifiEnabled,
            staWifiConnected = staWifiConnected,
            staWifiIp = staWifiIp,
            staWifiIface = staWifiIface,
            isMobileDataEnabled = isMobileDataEnabled,
            isVpnActive = isVpnActive,
            hotspotBroadcastHint = lastKnownHotspotBroadcastActive,
            localInterfaces = enumerated,
            multicastLockHeld = isMulticastLockHeld(),
            multicastProbe = lastMulticastProbe,
            discoveredCastCount = lastObservedDiscoveredCastCount
        )
    }

    companion object {
        const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        const val EXTRA_WIFI_AP_STATE = "wifi_state"
        const val ACTION_TETHER_STATE_CHANGED = "android.net.conn.TETHER_STATE_CHANGED"
        const val EXTRA_ACTIVE_TETHER = "tetherArray"

        private const val MDNS_MULTICAST_GROUP = "224.0.0.251"
        private const val MDNS_PORT = 5353

        private val CELLULAR_OR_VPN_PREFIXES = listOf(
            "rmnet", "ccmni", "pdp", "seth_lte", "v4-rmnet", "clat",
            "tun", "tap", "ppp", "wg", "ipsec", "dummy", "lo"
        )

        private val HOTSPOT_IFACE_PREFIXES = listOf(
            "swlan", "ap", "softap", "uap", "sap", "rndis", "tether", "wlan1", "wlan2", "wlan_ap"
        )

        fun isHotspotInterfaceName(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return HOTSPOT_IFACE_PREFIXES.any { lower.startsWith(it) }
        }

        fun isExcludedNonLanInterface(name: String): Boolean {
            val lower = name.lowercase(Locale.US)
            return CELLULAR_OR_VPN_PREFIXES.any { lower.startsWith(it) }
        }

        /**
         * Validates RFC1918 private LAN IPv4 addresses (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`)
         * so public cellular WAN addresses are NEVER selected for local media serving.
         */
        fun isPrivateLanIpv4(address: Inet4Address): Boolean {
            if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isMulticastAddress) {
                return false
            }
            val bytes = address.address
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            return when {
                b0 == 10 -> true
                b0 == 172 && b1 in 16..31 -> true
                b0 == 192 && b1 == 168 -> true
                else -> false
            }
        }

        fun isPrivateLanOrLoopbackHost(host: String): Boolean {
            if (host == "127.0.0.1" || host.equals("localhost", ignoreCase = true)) return true
            val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() as? Inet4Address
                ?: return false
            return isPrivateLanIpv4(parsed)
        }

        /**
         * Enumerates all UP, non-loopback, non-cellular, non-VPN network interfaces that hold
         * an RFC1918 private LAN IPv4 address using standard `java.net.NetworkInterface`.
         */
        fun enumerateLocalLanInterfaces(staInterfaceName: String?): List<NetworkInterfaceDescriptor> {
            val result = mutableListOf<NetworkInterfaceDescriptor>()
            val ifaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                ?: return emptyList()

            while (ifaces.hasMoreElements()) {
                val iface = ifaces.nextElement()
                val isUp = runCatching { iface.isUp }.getOrDefault(false)
                if (!isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name ?: continue
                if (isExcludedNonLanInterface(name)) continue

                val supportsMc = runCatching { iface.supportsMulticast() }.getOrDefault(false)
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && isPrivateLanIpv4(addr)) {
                        val ip = addr.hostAddress ?: continue
                        val matchesSta = staInterfaceName != null && name.equals(staInterfaceName, ignoreCase = true)
                        val lower = name.lowercase(Locale.US)
                        val hotspotLikely = !matchesSta && (
                            isHotspotInterfaceName(lower) ||
                                (staInterfaceName == null && lower.startsWith("wlan") && !lower.equals("wlan0", ignoreCase = true))
                            )
                        val wifiStaLikely = matchesSta ||
                            (!hotspotLikely && (lower.startsWith("wlan") || lower.startsWith("eth")))

                        result += NetworkInterfaceDescriptor(
                            name = name,
                            ipv4Address = ip,
                            supportsMulticast = supportsMc,
                            isHotspotLikely = hotspotLikely,
                            isWifiStaLikely = wifiStaLikely
                        )
                    }
                }
            }
            return result
        }

        /**
         * Pure, deterministic network mode classification engine combining ConnectivityManager,
         * WifiManager, Hotspot broadcasts, and live NetworkInterface enumeration.
         * Never hardcodes any IP address or subnet.
         */
        fun classifyNetworkState(
            isWifiEnabled: Boolean,
            staWifiConnected: Boolean,
            staWifiIp: String?,
            staWifiIface: String?,
            isMobileDataEnabled: Boolean,
            isVpnActive: Boolean,
            hotspotBroadcastHint: Boolean?,
            localInterfaces: List<NetworkInterfaceDescriptor>,
            multicastLockHeld: Boolean,
            multicastProbe: MulticastProbeResult?,
            discoveredCastCount: Int
        ): NetworkModeSnapshot {
            // Identify hotspot interface dynamically from actual live interfaces
            var hotspotDescriptor = localInterfaces.firstOrNull { it.isHotspotLikely }

            // On single-radio Android devices, enabling Mobile Hotspot turns off Wi-Fi STA and
            // binds the SoftAP directly onto `wlan0` (so staWifiConnected is false, but wlan0 has an RFC1918 IP!).
            if (hotspotDescriptor == null && !staWifiConnected) {
                val standaloneWlan = localInterfaces.firstOrNull {
                    it.name.lowercase(Locale.US).startsWith("wlan")
                }
                if (standaloneWlan != null && (hotspotBroadcastHint == true || !isWifiEnabled)) {
                    hotspotDescriptor = standaloneWlan.copy(
                        isHotspotLikely = true,
                        isWifiStaLikely = false
                    )
                }
            }

            // Resolve Conventional Wi-Fi STA interface
            val wifiStaDescriptor = if (staWifiConnected && staWifiIp != null) {
                localInterfaces.firstOrNull {
                    it.ipv4Address == staWifiIp || (staWifiIface != null && it.name.equals(staWifiIface, ignoreCase = true))
                } ?: NetworkInterfaceDescriptor(
                    name = staWifiIface ?: "wlan0",
                    ipv4Address = staWifiIp,
                    supportsMulticast = true,
                    isHotspotLikely = false,
                    isWifiStaLikely = true
                )
            } else if (isWifiEnabled && hotspotDescriptor == null) {
                localInterfaces.firstOrNull { it.isWifiStaLikely }
            } else {
                null
            }

            val isHotspotActive = hotspotDescriptor != null || hotspotBroadcastHint == true
            val isConventionalWifi = wifiStaDescriptor != null

            val mode = when {
                isConventionalWifi && isHotspotActive -> NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT
                isHotspotActive -> NetworkTransportMode.MOBILE_HOTSPOT
                isConventionalWifi -> NetworkTransportMode.CONVENTIONAL_WIFI
                else -> NetworkTransportMode.OFFLINE
            }

            // Choose binding IP dynamically:
            // - In Mobile Hotspot mode, bind to the hotspot-facing interface IP!
            // - In Conventional Wi-Fi mode, bind to the Wi-Fi STA interface IP!
            // - In Dual Wi-Fi + Hotspot mode, prefer the hotspot IP if no router Wi-Fi IP or keep Wi-Fi STA primary while exposing both.
            val preferredIfaceDescriptor = when (mode) {
                NetworkTransportMode.MOBILE_HOTSPOT -> hotspotDescriptor
                NetworkTransportMode.CONVENTIONAL_WIFI -> wifiStaDescriptor
                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> hotspotDescriptor ?: wifiStaDescriptor
                NetworkTransportMode.OFFLINE -> null
            }

            val bindIp = preferredIfaceDescriptor?.ipv4Address
            val bindIface = preferredIfaceDescriptor?.name
            val localAllowed = bindIp != null

            val multicastStatus = when {
                !localAllowed -> MulticastHealthStatus.INACTIVE
                multicastProbe != null && !multicastProbe.joinable -> MulticastHealthStatus.RESTRICTED_OR_BLOCKED
                preferredIfaceDescriptor?.supportsMulticast == false -> MulticastHealthStatus.RESTRICTED_OR_BLOCKED
                discoveredCastCount > 0 -> MulticastHealthStatus.HEALTHY
                multicastProbe != null && multicastProbe.joinable -> MulticastHealthStatus.HEALTHY
                multicastLockHeld -> MulticastHealthStatus.UNVERIFIED
                else -> MulticastHealthStatus.INACTIVE
            }

            val multicastDetail = when {
                multicastProbe != null -> multicastProbe.detail
                !localAllowed -> "No active local Wi-Fi or Hotspot interface."
                preferredIfaceDescriptor?.supportsMulticast == false ->
                    "Interface $bindIface reports multicast is not supported."
                discoveredCastCount > 0 ->
                    "Chromecast mDNS discovery active on $bindIface ($discoveredCastCount TV found)."
                multicastLockHeld ->
                    "MulticastLock held for active scan on $bindIface."
                else ->
                    "MulticastLock idle (acquired automatically during Cast device discovery)."
            }

            val hotspotClientStatus = when {
                !isHotspotActive -> HotspotClientStatus.UNKNOWN_RESTRICTED_BY_OS
                discoveredCastCount > 0 -> HotspotClientStatus.CLIENT_OBSERVED
                else -> HotspotClientStatus.NO_CLIENT_OBSERVED
            }

            val activeLabel = when (mode) {
                NetworkTransportMode.CONVENTIONAL_WIFI -> "Wi-Fi LAN (${bindIface ?: "wlan"})"
                NetworkTransportMode.MOBILE_HOTSPOT -> "Android Mobile Hotspot (${bindIface ?: "ap"})"
                NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> "Wi-Fi + Hotspot (${bindIface ?: "dual"})"
                NetworkTransportMode.OFFLINE -> if (isMobileDataEnabled) "Mobile Data Only" else "Disconnected"
            }

            val (headline, hint) = when {
                mode == NetworkTransportMode.OFFLINE -> {
                    "No local Wi-Fi or Mobile Hotspot active." to
                        "Connect to a shared Wi-Fi network, or turn on your phone's Mobile Hotspot and connect your Chromecast TV to it."
                }

                discoveredCastCount > 0 -> {
                    "Chromecast discovered successfully." to
                        if (isVpnActive) {
                            "A VPN is active; if playback fails to load on the TV, allow local LAN traffic or pause the VPN."
                        } else null
                }

                (mode == NetworkTransportMode.MOBILE_HOTSPOT || mode == NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT) &&
                    multicastStatus == MulticastHealthStatus.RESTRICTED_OR_BLOCKED -> {
                    "TV connected, but Chromecast discovery is unavailable. Your hotspot may be blocking multicast traffic." to
                        "Some Android hotspot firmwares block mDNS forwarding between clients and host. Check Hotspot AP Isolation settings or use a conventional Wi-Fi router."
                }

                mode == NetworkTransportMode.MOBILE_HOTSPOT -> {
                    "Mobile hotspot detected." to
                        "Connect your Chromecast-enabled TV to this phone's hotspot. Media server will bind to ${bindIp ?: "hotspot interface"}."
                }

                mode == NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT -> {
                    "Mobile hotspot detected alongside Wi-Fi." to
                        "Connect your Chromecast-enabled TV to this phone's hotspot or shared Wi-Fi router."
                }

                else -> {
                    "Conventional Wi-Fi active ($bindIp)." to
                        if (isVpnActive) {
                            "VPN detected — ensure local LAN routing is allowed so Chromecast can reach $bindIp."
                        } else {
                            "Ensure your Chromecast TV is connected to the same Wi-Fi network."
                        }
                }
            }

            return NetworkModeSnapshot(
                mode = mode,
                isWifiEnabled = isWifiEnabled,
                isConventionalWifiConnected = isConventionalWifi,
                isHotspotActive = isHotspotActive,
                isMobileDataEnabled = isMobileDataEnabled,
                activeNetworkLabel = activeLabel,
                wifiIpv4Address = wifiStaDescriptor?.ipv4Address,
                wifiInterfaceName = wifiStaDescriptor?.name,
                hotspotIpv4Address = hotspotDescriptor?.ipv4Address,
                hotspotInterfaceName = hotspotDescriptor?.name,
                preferredBindIpv4 = bindIp,
                preferredBindInterface = bindIface,
                localCommunicationAllowed = localAllowed,
                isVpnActive = isVpnActive,
                multicastLockHeld = multicastLockHeld,
                multicastStatus = multicastStatus,
                multicastDiagnosticDetail = multicastDetail,
                hotspotClientStatus = hotspotClientStatus,
                observableHotspotClientCount = if (isHotspotActive && discoveredCastCount > 0) discoveredCastCount else null,
                availableLocalInterfaces = localInterfaces,
                statusHeadline = headline,
                troubleshootingHint = hint
            )
        }

        /**
         * Opens Android's official system Hotspot & Tethering settings safely so the user can
         * enable or configure Mobile Hotspot without using hidden APIs.
         */
        fun openSystemHotspotSettings(context: Context): Boolean {
            val candidates = listOf(
                Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_WIFI_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            for (intent in candidates) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val opened = runCatching {
                    context.startActivity(intent)
                    true
                }.getOrDefault(false)
                if (opened) return true
            }
            return false
        }
    }
}
