package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface

/**
 * Utility object for network interface operations.
 * Handles IP address detection, broadcast calculation, and WiFi Direct interface identification.
 */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions")
object NetworkInterfaceHelper {

    /**
     * Get local IP addresses, prioritizing WiFi Direct interfaces.
     * @return List of IP addresses with WiFi Direct IPs first
     */
    @Suppress("NestedBlockDepth")
    fun getLocalIPAddresses(): List<String> {
        val wifiDirectIpAddresses = mutableListOf<String>()
        val regularIpAddresses = mutableListOf<String>()

        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()

            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                val interfaceName = networkInterface.name
                val isP2pInterface = isWiFiDirectInterface(interfaceName)

                for (interfaceAddress in networkInterface.interfaceAddresses) {
                    val inetAddress = interfaceAddress.address
                    if (inetAddress is Inet4Address) {
                        val ipAddress = inetAddress.hostAddress
                        if (ipAddress != null && !ipAddress.startsWith("127.")) {
                            if (isP2pInterface) {
                                wifiDirectIpAddresses.add(ipAddress)
                                logD { "Found WiFi Direct IP: $ipAddress on interface $interfaceName" }
                            } else {
                                regularIpAddresses.add(ipAddress)
                                logD { "Found local IP: $ipAddress on interface $interfaceName" }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error getting local IP addresses" }, e)
        }

        return wifiDirectIpAddresses + regularIpAddresses
    }

    /**
     * Get network broadcast addresses for all active interfaces.
     * When bound to a P2P interface, only returns that interface's broadcast.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun getNetworkBroadcastAddresses(boundInterfaceName: String? = null, boundAddress: InetAddress? = null): List<String> {
        val broadcastAddresses = mutableListOf<String>()

        try {
            // If bound to a P2P interface, only use that interface's broadcast
            if (boundInterfaceName != null && boundAddress != null) {
                val networkInterface = NetworkInterface.getByName(boundInterfaceName)
                if (networkInterface != null && networkInterface.isUp) {
                    collectBroadcastAddressesFromInterface(networkInterface, broadcastAddresses)
                    logD { "Using only P2P interface broadcasts: ${broadcastAddresses.joinToString(", ")}" }

                    // If we couldn't get a broadcast from the interface, calculate from bound address
                    if (broadcastAddresses.isEmpty()) {
                        val calculatedBroadcast = calculateBroadcastFromAddress(boundAddress)
                        if (calculatedBroadcast != null) {
                            broadcastAddresses.add(calculatedBroadcast)
                            logD { "Calculated P2P broadcast from bound address: $calculatedBroadcast" }
                        }
                    }

                    // Return early - only use P2P broadcasts
                    if (broadcastAddresses.isNotEmpty()) {
                        return broadcastAddresses
                    }
                }
            }

            // Not bound to P2P interface - use all interfaces
            broadcastAddresses.add("255.255.255.255")

            val networkInterfaces = NetworkInterface.getNetworkInterfaces()

            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) {
                    continue
                }

                collectBroadcastAddressesFromInterface(networkInterface, broadcastAddresses)
            }

            // Always include common WiFi Direct broadcast address as a fallback
            if (!broadcastAddresses.contains("192.168.49.255")) {
                broadcastAddresses.add("192.168.49.255")
                logD { "Added default WiFi Direct broadcast: 192.168.49.255" }
            }
        } catch (e: Exception) {
            logE({ "Error getting network broadcast addresses" }, e)
            // Fallback to common addresses if dynamic detection fails
            if (broadcastAddresses.size <= 1) {
                broadcastAddresses.add("192.168.49.255") // WiFi Direct common subnet
                broadcastAddresses.add("192.168.1.255") // Common home network
                broadcastAddresses.add("10.0.0.255") // Common mobile hotspot
            }
        }

        return broadcastAddresses
    }

    /**
     * Get the IP address of the WiFi Direct P2P interface if available.
     * @return Pair of (interface name, IP address) or null if no P2P interface is active
     */
    @Suppress("NestedBlockDepth")
    fun getWiFiDirectInterfaceAddress(): Pair<String, InetAddress>? {
        try {
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in networkInterfaces) {
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                if (isWiFiDirectInterface(networkInterface.name)) {
                    for (interfaceAddress in networkInterface.interfaceAddresses) {
                        val inetAddress = interfaceAddress.address
                        if (inetAddress is Inet4Address && !inetAddress.isLoopbackAddress) {
                            logD { "Found P2P interface: ${networkInterface.name} -> ${inetAddress.hostAddress}" }
                            return Pair(networkInterface.name, inetAddress)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logE({ "Error finding P2P interface" }, e)
        }
        logD { "No P2P interface found" }
        return null
    }

    /**
     * Checks if a network interface is a WiFi Direct (P2P) interface.
     * WiFi Direct interfaces are typically named:
     * - p2p0 (older devices)
     * - p2p-wlan0-* (newer devices, dynamic naming)
     * - p2p-p2p0-* (some devices)
     */
    fun isWiFiDirectInterface(interfaceName: String): Boolean {
        val lowerName = interfaceName.lowercase()
        return lowerName == "p2p0" ||
            lowerName.startsWith("p2p-") ||
            lowerName.contains("p2p")
    }

    /**
     * Log all network interfaces for debugging.
     */
    @Suppress("NestedBlockDepth")
    fun logNetworkInterfaces(boundInterfaceName: String? = null, boundAddress: InetAddress? = null) {
        logD { "=== Network Interface Diagnostic ===" }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in interfaces) {
                if (!networkInterface.isUp) continue

                val isP2P = isWiFiDirectInterface(networkInterface.name)
                val prefix = if (isP2P) "[P2P] " else ""

                logD { "${prefix}Interface: ${networkInterface.name}" }
                for (addr in networkInterface.interfaceAddresses) {
                    val inetAddr = addr.address
                    if (inetAddr is Inet4Address) {
                        val broadcast = addr.broadcast?.hostAddress ?: "N/A"
                        logD { "  - ${inetAddr.hostAddress}/${addr.networkPrefixLength} broadcast=$broadcast" }
                    }
                }
            }
            logD { "Bound interface: $boundInterfaceName, Bound address: ${boundAddress?.hostAddress ?: "none"}" }
            logD { "=== End Network Interface Diagnostic ===" }
        } catch (e: Exception) {
            logE({ "Error logging interfaces" }, e)
        }
    }

    /**
     * Calculate broadcast address from an IP address assuming /24 subnet.
     * Used as fallback when the network interface doesn't report broadcast.
     */
    fun calculateBroadcastFromAddress(address: InetAddress): String? {
        return try {
            if (address !is Inet4Address) return null
            val addressBytes = address.address
            // Assume /24 subnet for P2P
            addressBytes[3] = 0xFF.toByte()
            addressBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } catch (e: Exception) {
            logW({ "Failed to calculate broadcast from address" }, e)
            null
        }
    }

    /**
     * Calculates the broadcast address from an IP address and network prefix length.
     * For example: 192.168.49.1/24 -> 192.168.49.255
     */
    fun calculateBroadcastAddress(address: Inet4Address, prefixLength: Int): String? {
        return try {
            val addressBytes = address.address
            val maskBytes = ByteArray(4)

            // Build the network mask
            for (i in 0 until 4) {
                val bitsInOctet = (prefixLength - i * 8).coerceIn(0, 8)
                maskBytes[i] = (0xFF shl 8 - bitsInOctet).toByte()
            }

            // Calculate broadcast address: (IP | ~mask)
            val broadcastBytes = ByteArray(4)
            for (i in 0 until 4) {
                broadcastBytes[i] = (addressBytes[i].toInt() or maskBytes[i].toInt().inv()).toByte()
            }

            // Convert to dotted decimal
            broadcastBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
        } catch (e: Exception) {
            logW({ "Failed to calculate broadcast address" }, e)
            null
        }
    }

    /**
     * Collects broadcast addresses from a single network interface.
     */
    private fun collectBroadcastAddressesFromInterface(networkInterface: NetworkInterface, broadcastAddresses: MutableList<String>) {
        val interfaceName = networkInterface.name
        logD { "Checking network interface: $interfaceName" }

        val isP2pInterface = isWiFiDirectInterface(interfaceName)
        if (isP2pInterface) {
            logD { "Detected WiFi Direct interface: $interfaceName" }
        }

        for (interfaceAddress in networkInterface.interfaceAddresses) {
            val broadcast = interfaceAddress.broadcast
            if (broadcast != null) {
                addBroadcastIfNew(broadcast.hostAddress, interfaceName, broadcastAddresses)
            } else if (isP2pInterface) {
                tryAddCalculatedBroadcast(interfaceAddress, interfaceName, broadcastAddresses)
            }
        }
    }

    /**
     * Adds a broadcast address to the list if it's new and valid.
     */
    private fun addBroadcastIfNew(broadcastAddr: String?, interfaceName: String, broadcastAddresses: MutableList<String>) {
        if (broadcastAddr != null && !broadcastAddresses.contains(broadcastAddr)) {
            broadcastAddresses.add(broadcastAddr)
            logD { "Found broadcast address: $broadcastAddr for interface $interfaceName" }
        }
    }

    /**
     * Attempts to calculate and add a broadcast address for WiFi Direct interfaces.
     */
    private fun tryAddCalculatedBroadcast(interfaceAddress: InterfaceAddress, interfaceName: String, broadcastAddresses: MutableList<String>) {
        val address = interfaceAddress.address
        if (address is Inet4Address) {
            val calculatedBroadcast = calculateBroadcastAddress(
                address,
                interfaceAddress.networkPrefixLength.toInt(),
            )
            if (calculatedBroadcast != null && !broadcastAddresses.contains(calculatedBroadcast)) {
                broadcastAddresses.add(calculatedBroadcast)
                logD { "Calculated WiFi Direct broadcast: $calculatedBroadcast for interface $interfaceName" }
            }
        }
    }
}
