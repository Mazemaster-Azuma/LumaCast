package com.example.core.network

import android.content.Context
import android.os.Build
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkDiagnosticReport
import com.example.core.common.NetworkModeSnapshot
import com.example.core.common.NetworkTransportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Network Connectivity & Hotspot Compatibility Diagnostic Module.
 *
 * Checks strictly authorized local signals without unrestricted subnet port-scanning
 * and without assuming that a pingable host is a Chromecast receiver:
 * 1. Active network mode (Conventional Wi-Fi vs Android Mobile Hotspot vs Dual vs Offline)
 * 2. Local RFC1918 interface enumeration & multicast capability
 * 3. mDNS multicast group (`224.0.0.251:5353`) joinability probe
 * 4. Embedded HTTP media server reachability self-test on the actual bound interface IP
 * 5. Official Google Cast SDK & receiver connection status
 * 6. Honest OEM hotspot multicast & client-isolation compatibility guidance
 */
class HotspotNetworkDiagnostics(
    private val context: Context,
    private val networkModeManager: NetworkModeManager,
    private val httpServer: LocalMediaHttpServer
) {

    suspend fun runFullDiagnostics(
        preferredServerPort: Int,
        castSdkAvailable: Boolean,
        castDiscoveryActive: Boolean,
        discoveredCastCount: Int,
        connectedDeviceName: String?,
        isCastingActive: Boolean
    ): NetworkDiagnosticReport = withContext(Dispatchers.IO) {
        val initialSnap = networkModeManager.refresh()
        val multicastProbe = if (initialSnap.isLocalCastNetworkReady) {
            networkModeManager.probeMulticastReachability(initialSnap.preferredBindInterface)
        } else {
            MulticastProbeResult(
                joinable = false,
                interfaceSupportsMulticast = false,
                detail = "No active Wi-Fi or Mobile Hotspot interface available for mDNS multicast."
            )
        }

        val updatedSnap = networkModeManager.refresh()
        val bindIp = updatedSnap.preferredBindIpv4
        var httpReachable = false
        var httpLatencyMs: Long? = null
        var httpEndpoint: String? = null

        if (bindIp != null) {
            val wasRunning = httpServer.isRunning
            val previousHost = httpServer.listeningHost
            runCatching {
                if (!wasRunning) {
                    httpServer.start(bindIp, preferredServerPort)
                } else if (previousHost != null && previousHost != bindIp) {
                    httpServer.rebindIfNeeded(bindIp, preferredServerPort)
                }
                val port = httpServer.listeningPort
                httpEndpoint = if (port != null) "http://$bindIp:$port/health" else null
                val check = httpServer.runSelfHealthCheck()
                httpReachable = check.healthy
                httpLatencyMs = check.latencyMs
            }
            if (!wasRunning && !isCastingActive) {
                httpServer.stop()
            }
        }

        buildReport(
            timestampMs = System.currentTimeMillis(),
            snapshot = updatedSnap,
            multicastProbe = multicastProbe,
            httpServerReachable = httpReachable,
            httpServerLatencyMs = httpLatencyMs,
            httpServerEndpoint = httpEndpoint,
            castSdkAvailable = castSdkAvailable,
            castDiscoveryActive = castDiscoveryActive,
            discoveredCastCount = discoveredCastCount,
            connectedDeviceName = connectedDeviceName,
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty()
        )
    }

    companion object {
        fun buildReport(
            timestampMs: Long,
            snapshot: NetworkModeSnapshot,
            multicastProbe: MulticastProbeResult,
            httpServerReachable: Boolean,
            httpServerLatencyMs: Long?,
            httpServerEndpoint: String?,
            castSdkAvailable: Boolean,
            castDiscoveryActive: Boolean,
            discoveredCastCount: Int,
            connectedDeviceName: String?,
            manufacturer: String,
            model: String
        ): NetworkDiagnosticReport {
            val recommendations = mutableListOf<String>()
            val oemNote = buildOemCompatibilityNote(manufacturer, model)

            val headline = when {
                !castSdkAvailable -> {
                    recommendations += "Install or update Google Play Services to enable official Google Cast discovery."
                    "Google Cast SDK unavailable on this device."
                }

                snapshot.mode == NetworkTransportMode.OFFLINE || !snapshot.isLocalCastNetworkReady -> {
                    recommendations += "Connect to a shared Wi-Fi router, OR enable Android Mobile Hotspot in System Settings and connect your Chromecast TV to it."
                    "No local Wi-Fi or Mobile Hotspot detected."
                }

                connectedDeviceName != null && !httpServerReachable -> {
                    recommendations += "Disable VPN or local firewall rules blocking port access on ${snapshot.preferredBindIpv4}."
                    recommendations += "Set HTTP Server Port to Auto in LumaCast Settings and retry."
                    "TV connected, but local media streaming is blocked."
                }

                discoveredCastCount > 0 && httpServerReachable -> {
                    if (snapshot.isVpnActive) {
                        recommendations += "VPN is active — ensure LAN bypass is enabled so your TV can pull video chunks smoothly."
                    }
                    "Chromecast discovered successfully."
                }

                snapshot.isHotspotActive && (!multicastProbe.joinable || snapshot.multicastStatus == MulticastHealthStatus.RESTRICTED_OR_BLOCKED) -> {
                    recommendations += "Check Android Hotspot settings for 'AP Isolation' or 'Restrict connected devices' and disable it if available."
                    recommendations += "If your phone's hotspot firmware blocks mDNS forwarding, connect both phone and Chromecast TV to a conventional Wi-Fi router."
                    "TV connected, but Chromecast discovery is unavailable. Your hotspot may be blocking multicast traffic."
                }

                snapshot.isHotspotActive -> {
                    recommendations += "Connect your Chromecast-enabled TV to this phone's Wi-Fi hotspot."
                    recommendations += "Keep Mobile Data enabled if your Chromecast requires internet access to load Google Cast receiver apps."
                    recommendations += "Tap Re-Scan TVs after the TV finishes connecting to your hotspot."
                    "Mobile hotspot detected. Connect your Chromecast-enabled TV to this phone's hotspot."
                }

                !multicastProbe.joinable -> {
                    recommendations += "Router or VPN may be blocking mDNS multicast (224.0.0.251:5353). Disable AP/Client Isolation on your router."
                    "Multicast discovery appears restricted on ${snapshot.preferredBindInterface ?: "Wi-Fi"}."
                }

                else -> {
                    recommendations += "Confirm your Chromecast TV is powered on and connected to the same Wi-Fi SSID."
                    recommendations += "Avoid Guest Wi-Fi networks that isolate clients from each other."
                    "Local Wi-Fi ready (${snapshot.preferredBindIpv4}), scanning for Chromecast TVs."
                }
            }

            if (snapshot.isHotspotActive && !snapshot.isMobileDataEnabled) {
                recommendations += "Note: Mobile Data appears off. Some Chromecast firmware versions require brief internet connectivity on the hotspot to authenticate and load the Cast Receiver web app."
            }

            return NetworkDiagnosticReport(
                timestampMs = timestampMs,
                modeSnapshot = snapshot,
                localNetworkReachable = snapshot.isLocalCastNetworkReady,
                httpServerReachable = httpServerReachable,
                httpServerLatencyMs = httpServerLatencyMs,
                httpServerEndpoint = httpServerEndpoint,
                multicastSocketJoinable = multicastProbe.joinable,
                multicastProbeDetail = multicastProbe.detail,
                castSdkAvailable = castSdkAvailable,
                castDiscoveryActive = castDiscoveryActive,
                discoveredCastCount = discoveredCastCount,
                connectedDeviceName = connectedDeviceName,
                oemCompatibilityNote = oemNote,
                headlineMessage = headline,
                actionableRecommendations = recommendations
            )
        }

        fun buildOemCompatibilityNote(manufacturer: String, model: String): String {
            val m = manufacturer.lowercase(Locale.US)
            val deviceLabel = listOf(manufacturer, model).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android device" }
            return when {
                m.contains("google") || m.contains("pixel") ->
                    "$deviceLabel (Pixel/AOSP): Generally permits local hotspot client-to-host HTTP and mDNS forwarding when VPN is inactive."
                m.contains("samsung") ->
                    "$deviceLabel (Samsung One UI): Mobile Hotspot supports local HTTP streaming; if mDNS discovery fails, disable Protected Management Frames / Wi-Fi Sharing conflicts or check 'Band' compatibility (2.4 GHz / 5 GHz)."
                m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") ->
                    "$deviceLabel (MIUI / HyperOS): Some builds restrict mDNS multicast across SoftAP clients or aggressively throttle background hotspot sockets. Set LumaCast battery mode to 'No restrictions'."
                m.contains("oneplus") || m.contains("oppo") || m.contains("realme") ->
                    "$deviceLabel (OxygenOS / ColorOS): Check Hotspot Connection Management settings to ensure connected TVs are not isolated from the host phone."
                m.contains("motorola") ->
                    "$deviceLabel (Motorola): Standard AOSP tethering stack usually allows local hotspot HTTP; verify Chromecast supports the selected hotspot Wi-Fi band."
                else ->
                    "$deviceLabel: Android OEM hotspot multicast forwarding varies by Wi-Fi chipset and firmware. LumaCast tests multicast reachability honestly and reports if the hotspot blocks Cast mDNS."
            }
        }
    }
}
