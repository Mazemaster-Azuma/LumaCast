package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.common.HotspotClientStatus
import com.example.core.common.MulticastHealthStatus
import com.example.core.common.NetworkInterfaceDescriptor
import com.example.core.common.NetworkTransportMode
import com.example.core.network.HotspotNetworkDiagnostics
import com.example.core.network.InMemoryTextSource
import com.example.core.network.LocalMediaHttpServer
import com.example.core.network.MulticastProbeResult
import com.example.core.network.NetworkModeManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HotspotAndNetworkModeTest {

    private val server = LocalMediaHttpServer()

    @After
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `mode 1 conventional wifi selects sta interface ip dynamically`() {
        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = true,
            staWifiConnected = true,
            staWifiIp = "192.168.1.42",
            staWifiIface = "wlan0",
            isMobileDataEnabled = true,
            isVpnActive = false,
            hotspotBroadcastHint = false,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor(
                    name = "wlan0",
                    ipv4Address = "192.168.1.42",
                    supportsMulticast = true,
                    isHotspotLikely = false,
                    isWifiStaLikely = true
                )
            ),
            multicastLockHeld = false,
            multicastProbe = null,
            discoveredCastCount = 0
        )

        assertEquals(NetworkTransportMode.CONVENTIONAL_WIFI, snap.mode)
        assertTrue(snap.isConventionalWifiConnected)
        assertFalse(snap.isHotspotActive)
        assertTrue(snap.isLocalCastNetworkReady)
        assertEquals("192.168.1.42", snap.preferredBindIpv4)
        assertEquals("wlan0", snap.preferredBindInterface)
        assertTrue(snap.statusHeadline.contains("192.168.1.42"))
    }

    @Test
    fun `mode 2 mobile hotspot binds dynamically to arbitrary non-hardcoded softap ips`() {
        val dynamicHotspotCases = listOf(
            Triple("swlan0", "192.168.137.1", true),
            Triple("ap0", "10.142.0.1", true),
            Triple("wlan1", "172.20.10.1", true)
        )

        for ((iface, ip, isHotspot) in dynamicHotspotCases) {
            val snap = NetworkModeManager.classifyNetworkState(
                isWifiEnabled = false,
                staWifiConnected = false,
                staWifiIp = null,
                staWifiIface = null,
                isMobileDataEnabled = true,
                isVpnActive = false,
                hotspotBroadcastHint = true,
                localInterfaces = listOf(
                    NetworkInterfaceDescriptor(
                        name = iface,
                        ipv4Address = ip,
                        supportsMulticast = true,
                        isHotspotLikely = isHotspot,
                        isWifiStaLikely = false
                    )
                ),
                multicastLockHeld = true,
                multicastProbe = MulticastProbeResult(
                    joinable = true,
                    interfaceSupportsMulticast = true,
                    detail = "mDNS group 224.0.0.251:5353 joinable on $iface."
                ),
                discoveredCastCount = 0
            )

            assertEquals("Expected MOBILE_HOTSPOT for $iface/$ip", NetworkTransportMode.MOBILE_HOTSPOT, snap.mode)
            assertTrue(snap.isHotspotActive)
            assertFalse(snap.isConventionalWifiConnected)
            assertEquals(ip, snap.preferredBindIpv4)
            assertEquals(ip, snap.hotspotIpv4Address)
            assertEquals(iface, snap.preferredBindInterface)
            assertEquals(MulticastHealthStatus.HEALTHY, snap.multicastStatus)
            assertEquals(HotspotClientStatus.NO_CLIENT_OBSERVED, snap.hotspotClientStatus)
            assertEquals("Mobile hotspot detected.", snap.statusHeadline)
        }
    }

    @Test
    fun `single radio android hotspot on wlan0 without wifi sta is classified as mobile hotspot`() {
        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = false,
            staWifiConnected = false,
            staWifiIp = null,
            staWifiIface = null,
            isMobileDataEnabled = true,
            isVpnActive = false,
            hotspotBroadcastHint = true,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor(
                    name = "wlan0",
                    ipv4Address = "192.168.214.1",
                    supportsMulticast = true,
                    isHotspotLikely = false,
                    isWifiStaLikely = true
                )
            ),
            multicastLockHeld = false,
            multicastProbe = null,
            discoveredCastCount = 1
        )

        assertEquals(NetworkTransportMode.MOBILE_HOTSPOT, snap.mode)
        assertTrue(snap.isHotspotActive)
        assertFalse(snap.isConventionalWifiConnected)
        assertEquals("192.168.214.1", snap.preferredBindIpv4)
        assertEquals("wlan0", snap.hotspotInterfaceName)
        assertEquals(HotspotClientStatus.CLIENT_OBSERVED, snap.hotspotClientStatus)
        assertEquals("Chromecast discovered successfully.", snap.statusHeadline)
    }

    @Test
    fun `dual concurrent wifi and hotspot mode preserves both addresses`() {
        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = true,
            staWifiConnected = true,
            staWifiIp = "192.168.0.15",
            staWifiIface = "wlan0",
            isMobileDataEnabled = false,
            isVpnActive = false,
            hotspotBroadcastHint = true,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor(
                    name = "wlan0",
                    ipv4Address = "192.168.0.15",
                    supportsMulticast = true,
                    isHotspotLikely = false,
                    isWifiStaLikely = true
                ),
                NetworkInterfaceDescriptor(
                    name = "swlan0",
                    ipv4Address = "192.168.88.1",
                    supportsMulticast = true,
                    isHotspotLikely = true,
                    isWifiStaLikely = false
                )
            ),
            multicastLockHeld = true,
            multicastProbe = null,
            discoveredCastCount = 0
        )

        assertEquals(NetworkTransportMode.BOTH_WIFI_AND_HOTSPOT, snap.mode)
        assertTrue(snap.isConventionalWifiConnected)
        assertTrue(snap.isHotspotActive)
        assertEquals("192.168.0.15", snap.wifiIpv4Address)
        assertEquals("192.168.88.1", snap.hotspotIpv4Address)
        assertEquals("192.168.88.1", snap.preferredBindIpv4)
        assertEquals("swlan0", snap.preferredBindInterface)
    }

    @Test
    fun `mobile data only without wifi or hotspot classifies as offline`() {
        assertTrue(NetworkModeManager.isExcludedNonLanInterface("rmnet0"))
        assertTrue(NetworkModeManager.isExcludedNonLanInterface("tun0"))
        assertTrue(NetworkModeManager.isExcludedNonLanInterface("v4-rmnet_data1"))
        assertFalse(NetworkModeManager.isExcludedNonLanInterface("swlan0"))
        assertFalse(NetworkModeManager.isExcludedNonLanInterface("ap0"))

        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = false,
            staWifiConnected = false,
            staWifiIp = null,
            staWifiIface = null,
            isMobileDataEnabled = true,
            isVpnActive = false,
            hotspotBroadcastHint = false,
            localInterfaces = emptyList(),
            multicastLockHeld = false,
            multicastProbe = null,
            discoveredCastCount = 0
        )

        assertEquals(NetworkTransportMode.OFFLINE, snap.mode)
        assertFalse(snap.isLocalCastNetworkReady)
        assertNull(snap.preferredBindIpv4)
        assertEquals("Mobile Data Only", snap.activeNetworkLabel)
    }

    @Test
    fun `hotspot multicast blocked surfaces honest exact troubleshooting message`() {
        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = false,
            staWifiConnected = false,
            staWifiIp = null,
            staWifiIface = null,
            isMobileDataEnabled = true,
            isVpnActive = false,
            hotspotBroadcastHint = true,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor(
                    name = "ap0",
                    ipv4Address = "10.90.0.1",
                    supportsMulticast = false,
                    isHotspotLikely = true,
                    isWifiStaLikely = false
                )
            ),
            multicastLockHeld = true,
            multicastProbe = MulticastProbeResult(
                joinable = false,
                interfaceSupportsMulticast = false,
                detail = "Interface ap0 reports multicast disabled by kernel/OEM firmware."
            ),
            discoveredCastCount = 0
        )

        assertEquals(MulticastHealthStatus.RESTRICTED_OR_BLOCKED, snap.multicastStatus)
        assertEquals(
            "TV connected, but Chromecast discovery is unavailable. Your hotspot may be blocking multicast traffic.",
            snap.statusHeadline
        )
    }

    @Test
    fun `vpn active with discovered chromecast provides lan bypass hint`() {
        val snap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = true,
            staWifiConnected = true,
            staWifiIp = "192.168.10.24",
            staWifiIface = "wlan0",
            isMobileDataEnabled = false,
            isVpnActive = true,
            hotspotBroadcastHint = false,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor(
                    name = "wlan0",
                    ipv4Address = "192.168.10.24",
                    supportsMulticast = true,
                    isHotspotLikely = false,
                    isWifiStaLikely = true
                )
            ),
            multicastLockHeld = false,
            multicastProbe = null,
            discoveredCastCount = 1
        )

        assertEquals("Chromecast discovered successfully.", snap.statusHeadline)
        assertNotNull(snap.troubleshootingHint)
        assertTrue(snap.troubleshootingHint!!.contains("VPN", ignoreCase = true))
    }

    @Test
    fun `rfc1918 private lan guard allows private addresses and rejects public wan addresses`() {
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("127.0.0.1"))
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("10.0.0.1"))
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("10.255.254.1"))
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("172.16.0.1"))
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("172.31.255.254"))
        assertTrue(NetworkModeManager.isPrivateLanOrLoopbackHost("192.168.137.1"))

        assertFalse(NetworkModeManager.isPrivateLanOrLoopbackHost("8.8.8.8"))
        assertFalse(NetworkModeManager.isPrivateLanOrLoopbackHost("172.15.255.1"))
        assertFalse(NetworkModeManager.isPrivateLanOrLoopbackHost("172.32.0.1"))
        assertFalse(NetworkModeManager.isPrivateLanOrLoopbackHost("203.0.113.5"))
        assertFalse(NetworkModeManager.isPrivateLanOrLoopbackHost("169.254.1.1"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `local media http server refuses binding to public wan address`() {
        server.start("8.8.8.8", 0)
    }

    @Test
    fun `local media http server rebindIfNeeded preserves media and subtitle tokens and serves range requests`() = runBlocking {
        val firstPort = server.start("127.0.0.1", 0)
        assertTrue(firstPort > 0)

        val payload = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val mediaReg = server.registerMedia(
            InMemoryTextSource(payload, "video/mp4", "sample.mp4")
        )
        val subReg = server.registerSubtitle(
            InMemoryTextSource("WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nHello Hotspot\n", "text/vtt; charset=utf-8", "en.vtt"),
            extension = "vtt"
        )

        // Same host -> no rebind needed
        val (reboundSame, portSame) = server.rebindIfNeeded("127.0.0.1", firstPort)
        assertFalse(reboundSame)
        assertEquals(firstPort, portSame)

        // Force rebind by switching loopback alias "localhost" -> preserves registered tokens!
        val (reboundAlias, newPort) = server.rebindIfNeeded("localhost", 0)
        assertTrue(reboundAlias)
        assertTrue(newPort > 0)

        val updatedMediaUrl = server.buildUrlForMediaToken(mediaReg.token)
        val updatedSubUrl = server.buildUrlForSubtitleToken(subReg.token)
        assertNotNull(updatedMediaUrl)
        assertNotNull(updatedSubUrl)
        assertTrue(updatedMediaUrl!!.contains("http://localhost:$newPort/stream/${mediaReg.token}"))
        assertTrue(updatedSubUrl!!.contains("http://localhost:$newPort/subtitle/${subReg.token}"))

        // Verify 206 Partial Content still serves cleanly after rebind
        val rangeResp = sendRawHttp("127.0.0.1", newPort, "GET /stream/${mediaReg.token} HTTP/1.1\r\nHost: localhost:$newPort\r\nRange: bytes=10-15\r\nConnection: close\r\n\r\n")
        assertTrue("Expected 206 Partial Content, got:\n$rangeResp", rangeResp.startsWith("HTTP/1.1 206 Partial Content"))
        assertTrue(rangeResp.endsWith("ABCDEF"))

        // Verify subtitle still serves 200 OK after rebind
        val subResp = sendRawHttp("127.0.0.1", newPort, "GET /subtitle/${subReg.token} HTTP/1.1\r\nHost: localhost:$newPort\r\nConnection: close\r\n\r\n")
        assertTrue("Expected 200 OK for subtitle, got:\n$subResp", subResp.startsWith("HTTP/1.1 200 OK"))
        assertTrue(subResp.contains("Hello Hotspot"))

        val health = server.runSelfHealthCheck()
        assertTrue(health.healthy)
    }

    @Test
    fun `hotspot diagnostics builds exact messages for all key states and oem devices`() {
        val hotspotSnap = NetworkModeManager.classifyNetworkState(
            isWifiEnabled = false,
            staWifiConnected = false,
            staWifiIp = null,
            staWifiIface = null,
            isMobileDataEnabled = false,
            isVpnActive = false,
            hotspotBroadcastHint = true,
            localInterfaces = listOf(
                NetworkInterfaceDescriptor("swlan0", "192.168.149.1", true, isHotspotLikely = true, isWifiStaLikely = false)
            ),
            multicastLockHeld = true,
            multicastProbe = MulticastProbeResult(true, true, "OK"),
            discoveredCastCount = 0
        )

        // 1. Hotspot active, waiting for TV, mobile data off
        val waitingReport = HotspotNetworkDiagnostics.buildReport(
            timestampMs = 1_000L,
            snapshot = hotspotSnap,
            multicastProbe = MulticastProbeResult(true, true, "OK"),
            httpServerReachable = true,
            httpServerLatencyMs = 4L,
            httpServerEndpoint = "http://192.168.149.1:8080/health",
            castSdkAvailable = true,
            castDiscoveryActive = true,
            discoveredCastCount = 0,
            connectedDeviceName = null,
            manufacturer = "Google",
            model = "Pixel 9"
        )
        assertEquals(
            "Mobile hotspot detected. Connect your Chromecast-enabled TV to this phone's hotspot.",
            waitingReport.headlineMessage
        )
        assertTrue(waitingReport.actionableRecommendations.any { it.contains("Mobile Data", ignoreCase = true) })
        assertTrue(waitingReport.oemCompatibilityNote.contains("Pixel/AOSP"))

        // 2. Hotspot multicast blocked
        val blockedMulticastReport = HotspotNetworkDiagnostics.buildReport(
            timestampMs = 2_000L,
            snapshot = hotspotSnap.copy(multicastStatus = MulticastHealthStatus.RESTRICTED_OR_BLOCKED),
            multicastProbe = MulticastProbeResult(false, false, "mDNS blocked"),
            httpServerReachable = true,
            httpServerLatencyMs = 5L,
            httpServerEndpoint = "http://192.168.149.1:8080/health",
            castSdkAvailable = true,
            castDiscoveryActive = true,
            discoveredCastCount = 0,
            connectedDeviceName = null,
            manufacturer = "Xiaomi",
            model = "14 Ultra"
        )
        assertEquals(
            "TV connected, but Chromecast discovery is unavailable. Your hotspot may be blocking multicast traffic.",
            blockedMulticastReport.headlineMessage
        )
        assertTrue(blockedMulticastReport.oemCompatibilityNote.contains("MIUI / HyperOS"))

        // 3. TV connected, HTTP blocked
        val httpBlockedReport = HotspotNetworkDiagnostics.buildReport(
            timestampMs = 3_000L,
            snapshot = hotspotSnap,
            multicastProbe = MulticastProbeResult(true, true, "OK"),
            httpServerReachable = false,
            httpServerLatencyMs = null,
            httpServerEndpoint = "http://192.168.149.1:8080/health",
            castSdkAvailable = true,
            castDiscoveryActive = false,
            discoveredCastCount = 1,
            connectedDeviceName = "Living Room TV",
            manufacturer = "Samsung",
            model = "Galaxy S24"
        )
        assertEquals(
            "TV connected, but local media streaming is blocked.",
            httpBlockedReport.headlineMessage
        )
        assertTrue(httpBlockedReport.oemCompatibilityNote.contains("Samsung One UI"))

        // 4. Discovered & healthy
        val healthyReport = HotspotNetworkDiagnostics.buildReport(
            timestampMs = 4_000L,
            snapshot = hotspotSnap,
            multicastProbe = MulticastProbeResult(true, true, "OK"),
            httpServerReachable = true,
            httpServerLatencyMs = 3L,
            httpServerEndpoint = "http://192.168.149.1:8080/health",
            castSdkAvailable = true,
            castDiscoveryActive = true,
            discoveredCastCount = 1,
            connectedDeviceName = "Bedroom TV",
            manufacturer = "OnePlus",
            model = "12"
        )
        assertEquals("Chromecast discovered successfully.", healthyReport.headlineMessage)
        assertTrue(healthyReport.oemCompatibilityNote.contains("OxygenOS / ColorOS"))
    }

    @Test
    fun `network mode manager multicast lock acquire and release are idempotent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = NetworkModeManager(context)
        manager.startMonitoring()

        assertFalse(manager.isMulticastLockHeld())
        val acquired = manager.acquireMulticastLock()
        assertTrue(acquired)
        assertTrue(manager.isMulticastLockHeld())

        // Second acquire should stay idempotent
        assertTrue(manager.acquireMulticastLock())
        assertTrue(manager.isMulticastLockHeld())

        // Clean release
        manager.releaseMulticastLock()
        assertFalse(manager.isMulticastLockHeld())

        // Second release should not throw
        manager.releaseMulticastLock()
        assertFalse(manager.isMulticastLockHeld())

        manager.stopMonitoring()
    }

    private fun sendRawHttp(host: String, port: Int, rawRequest: String): String {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), 2000)
            socket.soTimeout = 2000
            socket.getOutputStream().write(rawRequest.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val sb = StringBuilder()
            val buf = CharArray(1024)
            while (true) {
                val read = reader.read(buf)
                if (read == -1) break
                sb.append(buf, 0, read)
            }
            return sb.toString()
        }
    }
}
