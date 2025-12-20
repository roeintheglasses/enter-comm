package com.entercomm.bikeintercom.mesh.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NodeIdEncoderTest {

    @Before
    fun setUp() {
        NodeIdEncoder.clearCache()
    }

    @After
    fun tearDown() {
        NodeIdEncoder.clearCache()
    }

    @Test
    fun `encode produces consistent hash for same input`() {
        val nodeId = "node-A1B2C3D4"
        val encoded1 = NodeIdEncoder.encode(nodeId)
        val encoded2 = NodeIdEncoder.encode(nodeId)

        assertEquals(encoded1, encoded2)
    }

    @Test
    fun `encode produces different hashes for different inputs`() {
        val nodeId1 = "node-A1B2C3D4"
        val nodeId2 = "node-E5F6G7H8"

        val encoded1 = NodeIdEncoder.encode(nodeId1)
        val encoded2 = NodeIdEncoder.encode(nodeId2)

        assertNotEquals(encoded1, encoded2)
    }

    @Test
    fun `encode handles broadcast keyword`() {
        val encoded = NodeIdEncoder.encode("broadcast")

        assertEquals(NodeIdEncoder.BROADCAST_ID, encoded)
    }

    @Test
    fun `decode returns broadcast string for broadcast ID`() {
        val decoded = NodeIdEncoder.decode(NodeIdEncoder.BROADCAST_ID)

        assertEquals("broadcast", decoded)
    }

    @Test
    fun `decode returns original string after encode`() {
        val nodeId = "node-A1B2C3D4"
        val encoded = NodeIdEncoder.encode(nodeId)
        val decoded = NodeIdEncoder.decode(encoded)

        assertEquals(nodeId, decoded)
    }

    @Test
    fun `decode returns null for unknown encoded value`() {
        // Encode nothing, then try to decode an arbitrary value
        val decoded = NodeIdEncoder.decode(12_345_678L)

        assertNull(decoded)
    }

    @Test
    fun `register populates cache and allows decode`() {
        val nodeId = "node-DEADBEEF"

        val encoded = NodeIdEncoder.register(nodeId)
        val decoded = NodeIdEncoder.decode(encoded)

        assertEquals(nodeId, decoded)
    }

    @Test
    fun `isRegistered returns true for registered node ID`() {
        val nodeId = "node-A1B2C3D4"
        NodeIdEncoder.register(nodeId)

        assertTrue(NodeIdEncoder.isRegistered(nodeId))
    }

    @Test
    fun `isRegistered returns false for unregistered node ID`() {
        val result = NodeIdEncoder.isRegistered("node-unknown")

        assertEquals(false, result)
    }

    @Test
    fun `cacheSize returns correct count`() {
        assertEquals(0, NodeIdEncoder.cacheSize())

        NodeIdEncoder.register("node-1")
        assertEquals(1, NodeIdEncoder.cacheSize())

        NodeIdEncoder.register("node-2")
        assertEquals(2, NodeIdEncoder.cacheSize())

        // Same node doesn't increase count
        NodeIdEncoder.register("node-1")
        assertEquals(2, NodeIdEncoder.cacheSize())
    }

    @Test
    fun `clearCache removes all entries`() {
        NodeIdEncoder.register("node-1")
        NodeIdEncoder.register("node-2")
        assertEquals(2, NodeIdEncoder.cacheSize())

        NodeIdEncoder.clearCache()

        assertEquals(0, NodeIdEncoder.cacheSize())
    }

    @Test
    fun `encode handles UUID format node IDs`() {
        val uuid = "550e8400-e29b-41d4-a716-446655440000"
        val encoded = NodeIdEncoder.encode(uuid)
        val decoded = NodeIdEncoder.decode(encoded)

        assertEquals(uuid, decoded)
    }

    @Test
    fun `encode handles empty string`() {
        val encoded = NodeIdEncoder.encode("")
        val decoded = NodeIdEncoder.decode(encoded)

        // Empty string should still work
        assertNotNull(decoded)
    }

    @Test
    fun `encode handles special characters`() {
        val nodeId = "node-with-special_chars.123"
        val encoded = NodeIdEncoder.encode(nodeId)
        val decoded = NodeIdEncoder.decode(encoded)

        assertEquals(nodeId, decoded)
    }

    @Test
    fun `encode produces 8-byte values`() {
        // The encoded value should fit in a Long (8 bytes)
        val nodeId = "node-A1B2C3D4"
        val encoded = NodeIdEncoder.encode(nodeId)

        // Just verify it doesn't throw and produces a value
        assertNotNull(encoded)
    }

    @Test
    fun `multiple different node IDs can be cached and decoded`() {
        val nodes = listOf(
            "node-AAAAAAAA",
            "node-BBBBBBBB",
            "node-CCCCCCCC",
            "node-DDDDDDDD",
            "node-EEEEEEEE",
        )

        // Register all nodes
        val encodedMap = nodes.associateWith { NodeIdEncoder.register(it) }

        // Verify all can be decoded
        for ((nodeId, encoded) in encodedMap) {
            assertEquals(nodeId, NodeIdEncoder.decode(encoded))
        }
    }
}
