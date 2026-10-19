package kittoku.osc.control

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import kittoku.osc.SharedBridge
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getBooleanPrefValue
import kittoku.osc.preference.accessor.getStringPrefValue
import kittoku.osc.preference.accessor.setStringPrefValue
import java.net.Inet4Address
import java.net.InetAddress


internal class NetworkObserver(val bridge: SharedBridge) {
    private val manager = bridge.service.getSystemService(ConnectivityManager::class.java)
    private val callback: ConnectivityManager.NetworkCallback
    private val callbackThread = HandlerThread("network-callback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)

    init {
        wipeStatus()

        val request = NetworkRequest.Builder().let {
            it.addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            it.removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            it.build()
        }


        callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                    callbackHandler.post {
                        manager.getLinkProperties(network)?.also {
                            updateSummary(it)
                        }
                    }
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                // Keep the (string-building) summary work off the main looper: on API 26+
                // the callback is already delivered on callbackThread, on API 24-25 it is
                // delivered on main, so hop onto our worker thread there.
                callbackHandler.post { updateSummary(linkProperties) }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.registerNetworkCallback(request, callback, Handler(callbackThread.looper))
        } else {
            // The Handler overload of registerNetworkCallback() was only added in API 26;
            // calling it on API 24-25 throws NoSuchMethodError. Register without a Handler
            // and dispatch the heavy work to the worker thread inside the callbacks above.
            manager.registerNetworkCallback(request, callback)
        }
    }

    private fun updateSummary(properties: LinkProperties) {
        val summary = mutableListOf<String>()

        bridge.sslTerminal!!.getSession().also {
            if (!it.isValid) return

            summary.add("[SSL/TLS Parameters]")
            summary.add("PROTOCOL: ${it.protocol}")
            summary.add("SUITE: ${it.cipherSuite}")
        }
        summary.add("")

        summary.add("[Assigned IP Address]")
        properties.linkAddresses.forEach {
            summary.add(it.address.hostAddress ?: "")
        }
        // HOME_CONNECTED_IP drives the "connected" UI/notification text. It must
        // be the server-assigned IPv4 address: the locally-generated IPv6
        // (link-local FE80::/10 and per-install ULA fd00::/8) is never an
        // assigned IP and must not be reported as such. Fall back to a global
        // IPv6 address only when no IPv4 was negotiated.
        val assignedIp = properties.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address }
            ?.hostAddress
            ?: properties.linkAddresses
                .map { it.address }
                .firstOrNull { it !is Inet4Address && isGlobalIpv6(it) }
                ?.hostAddress
            ?: ""
        if (assignedIp != "") {
            setStringPrefValue(assignedIp, OscPrefKey.HOME_CONNECTED_IP, bridge.prefs)
        }
        summary.add("")

        summary.add("[DNS Server Address]")
        if (properties.dnsServers.isNotEmpty()) {
            properties.dnsServers.forEach {
                summary.add(it.hostAddress ?: "")
            }
        } else {
            summary.add("Not specified")
        }
        summary.add("")

        summary.add("[Routing]")
        properties.routes.forEach {
            summary.add(it.toString())
        }
        summary.add("")

        val doEnableAppBasedRule = getBooleanPrefValue(OscPrefKey.ROUTE_DO_ENABLE_APP_BASED_RULE, bridge.prefs)
        if (doEnableAppBasedRule) {
            summary.add("[${getStringPrefValue(OscPrefKey.ROUTE_APP_LIST_TYPE, bridge.prefs)}]")
            bridge.selectedApps.forEach { summary.add(it.label) }
        }

        summary.reduce { acc, s ->
            acc + "\n" + s
        }.also {
            setStringPrefValue(it, OscPrefKey.HOME_STATUS, bridge.prefs)
        }
    }

    private fun isGlobalIpv6(address: InetAddress): Boolean {
        return !address.isAnyLocalAddress &&
            !address.isLinkLocalAddress &&
            !address.isSiteLocalAddress &&
            !address.isLoopbackAddress
    }

    private fun wipeStatus() {
        setStringPrefValue("", OscPrefKey.HOME_STATUS, bridge.prefs)
    }

    internal fun close() {
        try {
            manager.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {} // already unregistered

        if (callbackThread.isAlive) {
            callbackThread.quitSafely()
        }
        wipeStatus()
    }
}
