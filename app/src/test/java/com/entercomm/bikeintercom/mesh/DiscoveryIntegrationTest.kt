package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.mesh.protocol.PipeDelimitedMeshProtocol
import com.entercomm.bikeintercom.onboarding.GroupCodeUtils
import com.entercomm.bikeintercom.util.Logger
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Integration tests for mesh discovery using localhost UDP sockets.
 * These tests verify that two nodes can discover each other over the network.
 *
 * These tests simulate real network conditions and would have caught:
 * - Protocol serialization mismatches
 * - Discovery message format incompatibilities
 * - Group code filtering bugs
 */
class DiscoveryIntegrationTest {

    private lateinit var protocol: PipeDelimitedMeshProtocol

    // Use high ports to avoid conflicts
    private val nodeAPort = 18_888
    private val nodeBPort = 18_889

    private var socketA: DatagramSocket? = null
    private var socketB: DatagramSocket? = null

    @Before
    fun setUp() {
        Logger.isTestMode = true
        protocol = PipeDelimitedMeshProtocol()
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
        socketA?.close()
        socketB?.close()
    }

    // === Localhost UDP Discovery Tests ===

    @Test
    fun `node A sends discovery and node B receives it`() {
        socketA = DatagramSocket(nodeAPort)
        socketB = DatagramSocket(nodeBPort)

        val nodeAId = "node-aaaaaaaa"
        val nodeAName = "DeviceA"
        val groupCode = "OPEN"

        // Create discovery message from node A
        val payload = "$nodeAId|$nodeAName|$groupCode"
        val message = MeshMessage(
            sourceId = nodeAId,
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = payload.toByteArray(),
        )

        val serialized = protocol.serialize(message)

        // Node A sends to Node B
        val sendPacket = DatagramPacket(
            serialized,
            serialized.size,
            InetAddress.getByName("127.0.0.1"),
            nodeBPort,
        )
        socketA!!.send(sendPacket)

        // Node B receives
        val receiveBuffer = ByteArray(1024)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        socketB!!.soTimeout = 1000 // 1 second timeout
        socketB!!.receive(receivePacket)

        // Node B deserializes
        val received = protocol.deserialize(receiveBuffer, receivePacket.length)

        assertNotNull("Node B should receive and parse message", received)
        assertEquals(nodeAId, received!!.sourceId)
        assertEquals(MeshMessage.MessageType.DISCOVERY, received.messageType)

        // Parse payload
        val parts = String(received.payload).split("|")
        assertEquals(nodeAId, parts[0])
        assertEquals(nodeAName, parts[1])
        assertEquals(groupCode, parts[2])
    }

    @Test
    fun `bidirectional discovery between two nodes`() {
        socketA = DatagramSocket(nodeAPort)
        socketB = DatagramSocket(nodeBPort)

        val nodeAId = "node-aaaaaaaa"
        val nodeBId = "node-bbbbbbbb"
        val groupCode = GroupCodeUtils.generateGroupCode()

        // Prepare messages
        val messageA = createDiscoveryMessage(nodeAId, "DeviceA", groupCode)
        val messageB = createDiscoveryMessage(nodeBId, "DeviceB", groupCode)

        val serializedA = protocol.serialize(messageA)
        val serializedB = protocol.serialize(messageB)

        // A sends to B
        socketA!!.send(
            DatagramPacket(
                serializedA,
                serializedA.size,
                InetAddress.getByName("127.0.0.1"),
                nodeBPort,
            ),
        )

        // B sends to A
        socketB!!.send(
            DatagramPacket(
                serializedB,
                serializedB.size,
                InetAddress.getByName("127.0.0.1"),
                nodeAPort,
            ),
        )

        // A receives from B
        val bufferA = ByteArray(1024)
        val packetA = DatagramPacket(bufferA, bufferA.size)
        socketA!!.soTimeout = 1000
        socketA!!.receive(packetA)
        val receivedAtA = protocol.deserialize(bufferA, packetA.length)

        // B receives from A
        val bufferB = ByteArray(1024)
        val packetB = DatagramPacket(bufferB, bufferB.size)
        socketB!!.soTimeout = 1000
        socketB!!.receive(packetB)
        val receivedAtB = protocol.deserialize(bufferB, packetB.length)

        // Verify A received B's message
        assertNotNull("A should receive B's message", receivedAtA)
        assertEquals(nodeBId, receivedAtA!!.sourceId)

        // Verify B received A's message
        assertNotNull("B should receive A's message", receivedAtB)
        assertEquals(nodeAId, receivedAtB!!.sourceId)

        // Verify group codes match
        val groupAtA = String(receivedAtA.payload).split("|")[2]
        val groupAtB = String(receivedAtB.payload).split("|")[2]
        assertEquals(groupCode, groupAtA)
        assertEquals(groupCode, groupAtB)
    }

    @Test
    fun `concurrent discovery from multiple nodes`() = runBlocking {
        val numNodes = 5
        val receiverPort = 19_000
        val receiverSocket = DatagramSocket(receiverPort)
        receiverSocket.soTimeout = 2000

        val receivedMessages = mutableListOf<MeshMessage>()

        // Launch receiver coroutine
        val receiverJob = launch(Dispatchers.IO) {
            repeat(numNodes) {
                try {
                    val buffer = ByteArray(1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    receiverSocket.receive(packet)
                    val message = protocol.deserialize(buffer, packet.length)
                    if (message != null) {
                        synchronized(receivedMessages) {
                            receivedMessages.add(message)
                        }
                    }
                } catch (e: Exception) {
                    // Timeout or socket error expected during test - continue receiving
                    Logger.d("DiscoveryIntegrationTest") { "Receiver: ${e.message}" }
                }
            }
        }

        // Launch sender nodes
        val senderJobs = (1..numNodes).map { i ->
            launch(Dispatchers.IO) {
                val socket = DatagramSocket()
                try {
                    val nodeId = "node-${String.format(java.util.Locale.US, "%08x", i)}"
                    val message = createDiscoveryMessage(nodeId, "Device$i", "OPEN")
                    val serialized = protocol.serialize(message)

                    socket.send(
                        DatagramPacket(
                            serialized,
                            serialized.size,
                            InetAddress.getByName("127.0.0.1"),
                            receiverPort,
                        ),
                    )
                } finally {
                    socket.close()
                }
            }
        }

        // Wait for all senders
        senderJobs.forEach { it.join() }

        // Give receiver time to process
        delay(500)
        receiverJob.cancel()
        receiverSocket.close()

        // Verify we received messages from multiple nodes
        assertTrue(
            "Should receive messages from multiple nodes, got ${receivedMessages.size}",
            receivedMessages.size >= numNodes - 1, // Allow for some packet loss
        )

        // Verify all received messages have unique source IDs
        val sourceIds = receivedMessages.map { it.sourceId }.toSet()
        assertEquals(
            "Each received message should have unique source",
            receivedMessages.size,
            sourceIds.size,
        )
    }

    // === Group Code Filtering Simulation Tests ===

    @Test
    fun `nodes with same group code can discover each other`() {
        val groupCode = GroupCodeUtils.generateGroupCode()

        val nodeA = SimulatedNode("node-aaaaaaaa", "DeviceA", groupCode)
        val nodeB = SimulatedNode("node-bbbbbbbb", "DeviceB", groupCode)

        // A sends discovery
        val messageFromA = nodeA.createDiscovery()

        // B receives and checks if should accept
        val shouldAccept = nodeB.shouldAcceptDiscovery(messageFromA)

        assertTrue("Node B should accept Node A (same group code)", shouldAccept)
    }

    @Test
    fun `nodes with different group codes reject each other`() {
        val nodeA = SimulatedNode("node-aaaaaaaa", "DeviceA", "ABC123")
        val nodeB = SimulatedNode("node-bbbbbbbb", "DeviceB", "XYZ789")

        val messageFromA = nodeA.createDiscovery()
        val shouldAccept = nodeB.shouldAcceptDiscovery(messageFromA)

        assertFalse("Node B should reject Node A (different group code)", shouldAccept)
    }

    @Test
    fun `OPEN group accepts any other node`() {
        val nodeA = SimulatedNode("node-aaaaaaaa", "DeviceA", "ABC123")
        val nodeB = SimulatedNode("node-bbbbbbbb", "DeviceB", null) // No group = OPEN

        val messageFromA = nodeA.createDiscovery()
        val shouldAccept = nodeB.shouldAcceptDiscovery(messageFromA)

        assertTrue("OPEN node should accept any other node", shouldAccept)
    }

    @Test
    fun `grouped node rejects OPEN nodes`() {
        val nodeA = SimulatedNode("node-aaaaaaaa", "DeviceA", null) // OPEN
        val nodeB = SimulatedNode("node-bbbbbbbb", "DeviceB", "ABC123") // In group

        val messageFromA = nodeA.createDiscovery()
        val shouldAccept = nodeB.shouldAcceptDiscovery(messageFromA)

        assertFalse("Grouped node should reject OPEN nodes", shouldAccept)
    }

    @Test
    fun `group code comparison is case insensitive`() {
        val nodeA = SimulatedNode("node-aaaaaaaa", "DeviceA", "abcdef")
        val nodeB = SimulatedNode("node-bbbbbbbb", "DeviceB", "ABCDEF")

        val messageFromA = nodeA.createDiscovery()
        val shouldAccept = nodeB.shouldAcceptDiscovery(messageFromA)

        assertTrue("Group code comparison should be case insensitive", shouldAccept)
    }

    // === Message Integrity Tests ===

    @Test
    fun `large device names survive network transmission`() {
        socketA = DatagramSocket(nodeAPort)
        socketB = DatagramSocket(nodeBPort)

        val longName = "BikeIntercom-" + "A".repeat(30) // 43 chars, under 50 limit
        val message = createDiscoveryMessage("node-aaaaaaaa", longName, "OPEN")
        val serialized = protocol.serialize(message)

        socketA!!.send(
            DatagramPacket(
                serialized,
                serialized.size,
                InetAddress.getByName("127.0.0.1"),
                nodeBPort,
            ),
        )

        val buffer = ByteArray(1024)
        val packet = DatagramPacket(buffer, buffer.size)
        socketB!!.soTimeout = 1000
        socketB!!.receive(packet)

        val received = protocol.deserialize(buffer, packet.length)
        assertNotNull(received)

        val parts = String(received!!.payload).split("|")
        assertEquals(longName, parts[1])
    }

    @Test
    fun `rapid sequential messages maintain ordering`() {
        socketA = DatagramSocket(nodeAPort)
        socketB = DatagramSocket(nodeBPort)
        socketB!!.soTimeout = 2000

        val messageCount = 10
        val sentIds = mutableListOf<String>()

        // Send multiple messages rapidly
        for (i in 1..messageCount) {
            val nodeId = "node-${String.format(java.util.Locale.US, "%08x", i)}"
            sentIds.add(nodeId)

            val message = createDiscoveryMessage(nodeId, "Device$i", "OPEN")
            val serialized = protocol.serialize(message)

            socketA!!.send(
                DatagramPacket(
                    serialized,
                    serialized.size,
                    InetAddress.getByName("127.0.0.1"),
                    nodeBPort,
                ),
            )
        }

        // Receive all messages
        val receivedIds = mutableListOf<String>()
        repeat(messageCount) {
            try {
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socketB!!.receive(packet)

                val received = protocol.deserialize(buffer, packet.length)
                if (received != null) {
                    receivedIds.add(received.sourceId)
                }
            } catch (e: Exception) {
                // Timeout or socket error expected during test
                Logger.d("DiscoveryIntegrationTest") { "Sequential receive: ${e.message}" }
            }
        }

        // Verify all messages received (UDP may reorder, so just check all arrived)
        assertEquals(
            "All messages should be received",
            messageCount,
            receivedIds.size,
        )
        assertEquals(
            "All unique source IDs should be received",
            sentIds.toSet(),
            receivedIds.toSet(),
        )
    }

    // === Helper Classes and Functions ===

    private fun createDiscoveryMessage(nodeId: String, deviceName: String, groupCode: String): MeshMessage {
        val payload = "$nodeId|$deviceName|$groupCode"
        return MeshMessage(
            sourceId = nodeId,
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = payload.toByteArray(),
        )
    }

    /**
     * Simulates a mesh node for testing group code filtering logic.
     */
    private inner class SimulatedNode(
        val nodeId: String,
        val deviceName: String,
        val groupCode: String?, // null means OPEN
    ) {
        fun createDiscovery(): MeshMessage {
            val code = groupCode ?: "OPEN"
            return createDiscoveryMessage(nodeId, deviceName, code)
        }

        fun shouldAcceptDiscovery(message: MeshMessage): Boolean {
            // Parse the incoming message's group code
            val parts = String(message.payload).split("|")
            if (parts.size < 3) return false

            val theirCode = parts[2].uppercase()
            val ourCode = groupCode?.uppercase()

            // If we have no group code (OPEN), accept everyone
            if (ourCode == null || ourCode == "OPEN") {
                return true
            }

            // If we're in a group, only accept matching codes
            return theirCode == ourCode
        }
    }
}
