package com.entercomm.bikeintercom.mesh.network

import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Manages UDP socket lifecycle for the mesh network.
 * Handles socket creation, binding, and cleanup for both discovery and audio channels.
 */
@Suppress("TooGenericExceptionCaught", "TooManyFunctions", "ClassOrdering")
class SocketManager {
    private companion object {
        const val SOCKET_TIMEOUT_MS = 1000 // 1 second timeout for socket receive operations
    }

    private var _discoverySocket: DatagramSocket? = null
    private var _audioSocket: DatagramSocket? = null
    private var _boundInterfaceName: String? = null
    private var _boundAddress: InetAddress? = null

    /** Read-only access to discovery socket for message listeners */
    val discoverySocket: DatagramSocket? get() = _discoverySocket

    /** Read-only access to audio socket for audio listeners */
    val audioSocket: DatagramSocket? get() = _audioSocket

    /** Currently bound interface name, if any */
    val boundInterfaceName: String? get() = _boundInterfaceName

    /** Currently bound address, if any */
    val boundAddress: InetAddress? get() = _boundAddress

    /**
     * Create sockets bound to all interfaces on the specified port.
     * @param discoveryPort The port for discovery messages
     * @return true if sockets were created successfully
     */
    fun createSockets(discoveryPort: Int): Boolean {
        return try {
            closeSockets()

            _discoverySocket = DatagramSocket(discoveryPort).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
            }
            _audioSocket = DatagramSocket(discoveryPort + 1).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
            }

            logD { "Mesh network sockets created: discovery=$discoveryPort, audio=${discoveryPort + 1}" }
            true
        } catch (e: Exception) {
            logE({ "Failed to create sockets on port $discoveryPort" }, e)
            closeSockets()
            false
        }
    }

    /**
     * Create sockets bound to a specific network interface.
     * Used for WiFi Direct P2P groups where we need to bind to the p2p interface.
     *
     * @param interfaceName The network interface name (e.g., "p2p-wlan0-0")
     * @param discoveryPort The port for discovery messages
     * @return true if sockets were created successfully, false to fall back to default
     */
    fun createSocketsOnInterface(interfaceName: String, discoveryPort: Int): Boolean {
        val networkInterface = try {
            NetworkInterface.getByName(interfaceName)
        } catch (e: Exception) {
            logE({ "Failed to get interface by name: $interfaceName" }, e)
            return false
        }

        if (networkInterface == null) {
            logE { "Interface $interfaceName not found" }
            return false
        }

        val localAddress = networkInterface.interfaceAddresses
            .firstOrNull { it.address is Inet4Address }
            ?.address as? Inet4Address

        if (localAddress == null) {
            logE { "No IPv4 address on interface $interfaceName" }
            return false
        }

        return createSocketsWithAddress(localAddress, discoveryPort, interfaceName)
    }

    /**
     * Create sockets bound to a specific IP address.
     */
    private fun createSocketsWithAddress(bindAddress: InetAddress, discoveryPort: Int, interfaceName: String? = null): Boolean {
        return try {
            closeSockets()

            val discoverySocketAddress = InetSocketAddress(bindAddress, discoveryPort)
            _discoverySocket = DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(discoverySocketAddress)
            }

            val audioSocketAddress = InetSocketAddress(bindAddress, discoveryPort + 1)
            _audioSocket = DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = SOCKET_TIMEOUT_MS
                bind(audioSocketAddress)
            }

            _boundInterfaceName = interfaceName
            _boundAddress = bindAddress

            logD { "Mesh network sockets bound to ${bindAddress.hostAddress}: discovery=$discoveryPort, audio=${discoveryPort + 1}" }
            true
        } catch (e: Exception) {
            logE({ "Failed to create sockets on ${bindAddress.hostAddress}:$discoveryPort" }, e)
            closeSockets()
            false
        }
    }

    /**
     * Send data via the discovery socket.
     */
    fun sendDiscovery(data: ByteArray, address: InetAddress, port: Int): Boolean {
        val socket = _discoverySocket
        if (socket == null || socket.isClosed) {
            logW { "Discovery socket not available" }
            return false
        }
        return try {
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            true
        } catch (e: Exception) {
            logE({ "Failed to send discovery packet to ${address.hostAddress}:$port" }, e)
            false
        }
    }

    /**
     * Send data via the audio socket.
     */
    fun sendAudio(data: ByteArray, address: InetAddress, port: Int): Boolean {
        val socket = _audioSocket
        if (socket == null || socket.isClosed) {
            logW { "Audio socket not available" }
            return false
        }
        return try {
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            true
        } catch (e: Exception) {
            logE({ "Failed to send audio packet to ${address.hostAddress}:$port" }, e)
            false
        }
    }

    /**
     * Check if sockets are bound to a P2P interface.
     */
    fun isBoundToP2PInterface(): Boolean {
        return _boundInterfaceName != null && _boundAddress != null
    }

    /**
     * Close all sockets and clear binding state.
     */
    fun closeSockets() {
        try {
            _discoverySocket?.close()
        } catch (e: Exception) {
            logW({ "Error closing discovery socket" }, e)
        }
        try {
            _audioSocket?.close()
        } catch (e: Exception) {
            logW({ "Error closing audio socket" }, e)
        }
        _discoverySocket = null
        _audioSocket = null
        _boundInterfaceName = null
        _boundAddress = null

        logD { "Sockets closed" }
    }
}
