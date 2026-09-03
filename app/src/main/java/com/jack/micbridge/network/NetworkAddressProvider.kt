package com.jack.micbridge.network

import android.net.LinkProperties
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddressProvider {
    fun privateLanIpv4(): List<Inet4Address> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && isAllowedInterface(it.name) }
            .flatMap { network ->
                network.inetAddresses.toList().filterIsInstance<Inet4Address>()
            }
            .let(::trustedPrivateIpv4)
    }.getOrDefault(emptyList())

    /** Addresses supplied by ConnectivityManager's ordered Wi-Fi callbacks. */
    fun privateWifiIpv4(linkProperties: Collection<LinkProperties>): List<Inet4Address> =
        trustedPrivateIpv4(
            linkProperties.flatMap { properties ->
                properties.linkAddresses.mapNotNull { it.address as? Inet4Address }
            },
        )

    /** Addresses on downstream interfaces identified by the API 36 tethering callback. */
    fun privateIpv4OnInterfaces(interfaceNames: Set<String>): List<Inet4Address> {
        if (interfaceNames.isEmpty()) return emptyList()
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter {
                    it.name in interfaceNames && it.isUp && !it.isLoopback &&
                        isAllowedInterface(it.name)
                }
                .flatMap { network ->
                    network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                }
                .let(::trustedPrivateIpv4)
        }.getOrDefault(emptyList())
    }

    fun merge(vararg groups: List<Inet4Address>): List<Inet4Address> =
        trustedPrivateIpv4(groups.flatMap { it })

    private fun isAllowedInterface(name: String): Boolean {
        val normalized = name.lowercase()
        return BLOCKED_INTERFACE_PREFIXES.none { normalized.startsWith(it) }
    }

    private fun score(address: Inet4Address): Int {
        val value = address.hostAddress.orEmpty()
        return when {
            value == "192.168.43.1" -> 100
            value.startsWith("192.168.") -> 80
            value.startsWith("172.") -> 60
            value.startsWith("10.") -> 40
            else -> 0
        }
    }

    private fun trustedPrivateIpv4(addresses: Collection<Inet4Address>): List<Inet4Address> =
        addresses
            .filter { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .distinctBy { it.hostAddress }
            .sortedWith(compareByDescending<Inet4Address> { score(it) }.thenBy { it.hostAddress })

    private val BLOCKED_INTERFACE_PREFIXES = listOf(
        "lo",
        "rmnet",
        "ccmni",
        "pdp",
        "tun",
        "tap",
        "wg",
        "ipsec",
        "dummy",
    )
}
