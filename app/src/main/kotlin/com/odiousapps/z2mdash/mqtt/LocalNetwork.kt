package com.odiousapps.z2mdash.mqtt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet6Address
import java.net.InetAddress

object LocalNetwork {
    /**
     * True if any of these broker hosts is on the LAN - the addresses Android 17's
     * ACCESS_LOCAL_NETWORK permission gates. Hostnames are resolved (hence IO), since
     * "homeassistant" or "mqtt.example.com" can just as easily point at a 192.168.x.x address.
     */
    suspend fun anyLocal(hosts: Collection<String>): Boolean = withContext(Dispatchers.IO) {
        hosts.any { isLocal(it) }
    }

    private fun isLocal(host: String): Boolean {
        val h = host.trim().removeSurrounding("[", "]")
        if (h.isEmpty()) return false
        // mDNS names are LAN-only by definition, and resolving them may itself need the permission.
        if (h.endsWith(".local", ignoreCase = true)) return true
        // An unresolvable host can't be classified - it gets rechecked when the broker list changes.
        val addresses = runCatching { InetAddress.getAllByName(h) }.getOrNull() ?: return false
        return addresses.any { isLocalAddress(it) }
    }

    // isSiteLocalAddress covers 10/8, 172.16/12 and 192.168/16, but for IPv6 only the deprecated
    // fec0::/10 - so unique local fc00::/7 is checked by hand.
    private fun isLocalAddress(address: InetAddress): Boolean =
        address.isSiteLocalAddress || address.isLinkLocalAddress ||
            (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)
}
