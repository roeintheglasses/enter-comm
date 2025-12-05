package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PipeDelimitedMeshProtocolTest {

    private lateinit var protocol: PipeDelimitedMeshProtocol

    @Before
    fun setUp() {
        Logger.isTestMode = true
        protocol = PipeDelimitedMeshProtocol()
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
    }

    // === Serialization Tests ===

    @Test
    fun `serialize creates valid pipe-delimited format`() {
        val message = MeshMessage(
            messageId = "test-msg-123",
            sourceId = "node-A",
            destinationId = "node-B",
            messageType = MeshMessage.MessageType.DISCOVERY,
            ttl = 5,
            timestamp = 1234567890L,
            payload = "hello".toByteArray(),
        )

        val serialized = protocol.serialize(message)
        val asString = String(serialized, Charsets.UTF_8)

        // Should contain 6 pipe-delimited fields followed by payload
        assertTrue("Should start with messageId", asString.startsWith("test-msg-123|"))
        assertTrue("Should contain sourceId", asString.contains("|node-A|"))
        assertTrue("Should contain destinationId", asString.contains("|node-B|"))
        assertTrue("Should contain message type", asString.contains("|DISCOVERY|"))
        assertTrue("Should contain TTL", asString.contains("|5|"))
        assertTrue("Should contain timestamp", asString.contains("|1234567890|"))
        assertTrue("Should end with payload", asString.endsWith("hello"))
    }

    @Test
    fun `serialize handles empty payload`() {
        val message = MeshMessage(
            messageId = "msg-1",
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            ttl = 10,
            timestamp = 0L,
            payload = ByteArray(0),
        )

        val serialized = protocol.serialize(message)
        assertNotNull(serialized)
        assertTrue("Should have header even with empty payload", serialized.isNotEmpty())
    }

    @Test
    fun `serialize handles binary payload`() {
        val binaryPayload = byteArrayOf(0x00, 0x01, 0x7F, 0xFF.toByte(), 0x80.toByte())
        val message = MeshMessage(
            messageId = "msg-bin",
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            ttl = 3,
            timestamp = 999L,
            payload = binaryPayload,
        )

        val serialized = protocol.serialize(message)
        assertNotNull(serialized)

        // The payload should be preserved exactly
        val headerSize = "msg-bin|src|dst|AUDIO_DATA|3|999|".length
        val extractedPayload = serialized.copyOfRange(headerSize, serialized.size)
        assertArrayEquals("Binary payload should be preserved", binaryPayload, extractedPayload)
    }

    // === Deserialization Tests ===

    @Test
    fun `deserialize parses valid message correctly`() {
        val rawData = "msg-123|nodeA|nodeB|DISCOVERY|5|1234567890|hello".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNotNull(message)
        assertEquals("msg-123", message!!.messageId)
        assertEquals("nodeA", message.sourceId)
        assertEquals("nodeB", message.destinationId)
        assertEquals(MeshMessage.MessageType.DISCOVERY, message.messageType)
        assertEquals(5, message.ttl)
        assertEquals(1234567890L, message.timestamp)
        assertEquals("hello", String(message.payload))
    }

    @Test
    fun `deserialize handles empty payload`() {
        val rawData = "msg-1|src|dst|HEARTBEAT|10|0|".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNotNull(message)
        assertEquals(0, message!!.payload.size)
    }

    @Test
    fun `deserialize returns null for too-short data`() {
        val rawData = "short".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNull("Should return null for too-short data", message)
    }

    @Test
    fun `deserialize returns null for missing fields`() {
        // Only 4 pipe characters instead of required 6
        val rawData = "msg|src|dst|DISCOVERY".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNull("Should return null when fields are missing", message)
    }

    @Test
    fun `deserialize returns null for invalid message type`() {
        val rawData = "msg|src|dst|INVALID_TYPE|5|123|data".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNull("Should return null for invalid message type", message)
    }

    @Test
    fun `deserialize returns null for invalid TTL`() {
        val rawData = "msg|src|dst|DISCOVERY|not_a_number|123|data".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNull("Should return null for invalid TTL", message)
    }

    @Test
    fun `deserialize returns null for invalid timestamp`() {
        val rawData = "msg|src|dst|DISCOVERY|5|not_a_number|data".toByteArray()

        val message = protocol.deserialize(rawData, rawData.size)

        assertNull("Should return null for invalid timestamp", message)
    }

    // === Round-trip Tests ===

    @Test
    fun `serialize and deserialize round trip produces same message`() {
        val original = MeshMessage(
            messageId = "round-trip-test",
            sourceId = "source-node",
            destinationId = "dest-node",
            messageType = MeshMessage.MessageType.CONTROL,
            ttl = 7,
            timestamp = 9876543210L,
            payload = "test payload data".toByteArray(),
        )

        val serialized = protocol.serialize(original)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals(original.messageId, deserialized!!.messageId)
        assertEquals(original.sourceId, deserialized.sourceId)
        assertEquals(original.destinationId, deserialized.destinationId)
        assertEquals(original.messageType, deserialized.messageType)
        assertEquals(original.ttl, deserialized.ttl)
        assertEquals(original.timestamp, deserialized.timestamp)
        assertArrayEquals(original.payload, deserialized.payload)
    }

    @Test
    fun `round trip preserves binary payload with pipe characters`() {
        // Create payload that contains pipe character in binary data
        val payloadWithPipes = byteArrayOf(0x7C, 0x41, 0x7C, 0x42) // |A|B
        val original = MeshMessage(
            messageId = "binary-test",
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            ttl = 5,
            timestamp = 123L,
            payload = payloadWithPipes,
        )

        val serialized = protocol.serialize(original)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertArrayEquals(
            "Binary payload with pipe characters should be preserved",
            payloadWithPipes,
            deserialized!!.payload,
        )
    }

    @Test
    fun `round trip works for all message types`() {
        val messageTypes = MeshMessage.MessageType.values()

        for (type in messageTypes) {
            val original = MeshMessage(
                messageId = "type-test-${type.name}",
                sourceId = "src",
                destinationId = "dst",
                messageType = type,
                ttl = 5,
                timestamp = System.currentTimeMillis(),
                payload = "payload".toByteArray(),
            )

            val serialized = protocol.serialize(original)
            val deserialized = protocol.deserialize(serialized, serialized.size)

            assertNotNull("Should deserialize ${type.name}", deserialized)
            assertEquals("Message type should match for ${type.name}", type, deserialized!!.messageType)
        }
    }

    // === Protocol Metadata Tests ===

    @Test
    fun `protocol version is set`() {
        assertNotNull(protocol.protocolVersion)
        assertTrue(protocol.protocolVersion.isNotEmpty())
    }

    @Test
    fun `protocol description is set`() {
        assertNotNull(protocol.protocolDescription)
        assertTrue(protocol.protocolDescription.isNotEmpty())
    }

    // === Edge Cases ===

    @Test
    fun `deserialize respects length parameter`() {
        val fullData = "msg|src|dst|DISCOVERY|5|123|hello world extra data".toByteArray()
        // Only deserialize up to "hello"
        val truncatedLength = "msg|src|dst|DISCOVERY|5|123|hello".length

        val message = protocol.deserialize(fullData, truncatedLength)

        assertNotNull(message)
        assertEquals("hello", String(message!!.payload))
    }

    @Test
    fun `handles large payload`() {
        val largePayload = ByteArray(10000) { it.toByte() }
        val original = MeshMessage(
            messageId = "large-payload",
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            ttl = 5,
            timestamp = 123L,
            payload = largePayload,
        )

        val serialized = protocol.serialize(original)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertArrayEquals(largePayload, deserialized!!.payload)
    }
}
