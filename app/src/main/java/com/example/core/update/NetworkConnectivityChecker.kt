package com.example.core.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * One-shot internet availability check (no continuous callbacks or polling).
 */
class NetworkConnectivityChecker(
    private val context: Context,
    private val overrideOnline: (() -> Boolean)? = null
) {
    fun isInternetAvailable(): Boolean {
        overrideOnline?.let { return it() }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return false

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) return false

        // On real devices NET_CAPABILITY_VALIDATED confirms captive portals/offline Wi-Fi aren't counted;
        // accept any transport that advertises validated internet, or internet when validation is unavailable.
        val hasTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        return hasTransport && (
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ||
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            )
    }
}
