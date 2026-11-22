package com.example.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.NetworkInterface

data class WifiNetworkSnapshot(
    val isWifiConnected: Boolean = false,
    val ipv4Address: String? = null,
    val interfaceName: String? = null
)

/**
 * Monitors local Wi-Fi network connectivity and resolves the active LAN IPv4 address
 * so the embedded HTTP server binds strictly to the Wi-Fi LAN interface and detects
 * IP changes or drops during active casting.
 */
class LocalNetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _snapshot = MutableStateFlow(readCurrentSnapshot())
    val snapshot: StateFlow<WifiNetworkSnapshot> = _snapshot.asStateFlow()

    private var registered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            refresh()
        }

        override fun onLost(network: Network) {
            refresh()
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            val ipv4 = linkProperties.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
            _snapshot.value = WifiNetworkSnapshot(
                isWifiConnected = ipv4 != null,
                ipv4Address = ipv4,
                interfaceName = linkProperties.interfaceName
            )
        }
    }

    fun startMonitoring() {
        if (registered) return
        val cm = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, networkCallback)
            registered = true
        }
        refresh()
    }

    fun stopMonitoring() {
        if (!registered) return
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
        registered = false
    }

    fun refresh(): WifiNetworkSnapshot {
        val current = readCurrentSnapshot()
        _snapshot.value = current
        return current
    }

    private fun readCurrentSnapshot(): WifiNetworkSnapshot {
        val cm = connectivityManager
        if (cm != null) {
            val active = cm.activeNetwork
            val caps = active?.let { cm.getNetworkCapabilities(it) }
            val hasWifiOrEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
            if (hasWifiOrEthernet) {
                val linkProps = cm.getLinkProperties(active)
                val ipv4 = linkProps?.linkAddresses
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
                if (ipv4 != null) {
                    return WifiNetworkSnapshot(
                        isWifiConnected = true,
                        ipv4Address = ipv4,
                        interfaceName = linkProps.interfaceName
                    )
                }
            }
        }

        // Fallback: inspect wlan/eth network interfaces directly for non-loopback IPv4
        val fallback = findLanIpv4ViaInterfaces()
        return if (fallback != null) {
            WifiNetworkSnapshot(
                isWifiConnected = true,
                ipv4Address = fallback.first,
                interfaceName = fallback.second
            )
        } else {
            WifiNetworkSnapshot(isWifiConnected = false, ipv4Address = null, interfaceName = null)
        }
    }

    companion object {
        fun findLanIpv4ViaInterfaces(): Pair<String, String>? {
            val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
                ?: return null
            val candidates = mutableListOf<Pair<String, String>>()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                val name = iface.name.lowercase()
                // Skip cellular interfaces (rmnet, ccmni, pdp) and VPN tun interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("tun") || name.startsWith("ppp")
                ) {
                    continue
                }
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (name.startsWith("wlan") || name.startsWith("eth") || name.startsWith("ap")) {
                            return ip to iface.name
                        }
                        candidates += ip to iface.name
                    }
                }
            }
            return candidates.firstOrNull()
        }
    }
}
