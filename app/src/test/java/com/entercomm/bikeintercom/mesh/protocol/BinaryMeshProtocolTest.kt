package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.util.Logger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class BinaryMeshProtocolTest {

    private lateinit var protocol: BinaryMeshProtocol

    @Before
    fun setUp() {
        Logger.isTestMode = true
        protocol = BinaryMeshProtocol()
        NodeIdEncoder.clearCache()

        // Register test node IDs
        NodeIdEncoder.register("node-source")
        NodeIdEncoder.register("node-dest")
    }

    @After
    fun tearDown() {
        Logger.isTestMode = false
        NodeIdEncoder.clearCache()
    }

    @Test
    fun `serialize produces correct header size`() {
        val message = MeshMessage(
            messageId = "550e8400-e29b-41d4-a716-446655440000",
            sourceId = "node-source",
            destinationId = "node-dest",
            messageType = MeshMessage.MessageType.DISCOVERY,
            ttl = 5,
            timestamp = 1_234_567_890L,
            payload = ByteArray(0),
        )

        val serialized = protocol.serialize(message)

        // Header should be 39 bytes with empty payload
        assertEquals(BinaryMeshProtocol.HEADER_SIZE, serialized.size)
    }

    @Test
    fun `serialize starts with magic byte`() {
        val message = createTestMessage()

        val serialized = protocol.serialize(message)

        assertEquals(BinaryMeshProtocol.MAGIC_BYTE, serialized[0])
    }

    @Test
    fun `serialize includes version byte`() {
        val message = createTestMessage()

        val serialized = protocol.serialize(message)

        assertEquals(BinaryMeshProtocol.VERSION, serialized[1])
    }

    @Test
    fun `serialize includes message type`() {
        val message = createTestMessage(messageType = MeshMessage.MessageType.AUDIO_DATA)

        val serialized = protocol.serialize(message)

        // Message type is at offset 3
        assertEquals(MeshMessage.MessageType.AUDIO_DATA.ordinal.toByte(), serialized[3])
    }

    @Test
    fun `serialize includes TTL`() {
        val message = createTestMessage(ttl = 7)

        val serialized = protocol.serialize(message)

        // TTL is at offset 4
        assertEquals(7.toByte(), serialized[4])
    }

    @Test
    fun `serialize includes payload`() {
        val payload = "Hello, World!".toByteArray()
        val message = createTestMessage(payload = payload)

        val serialized = protocol.serialize(message)

        // Total size should be header + payload
        assertEquals(BinaryMeshProtocol.HEADER_SIZE + payload.size, serialized.size)

        // Verify payload bytes at end
        val extractedPayload = serialized.copyOfRange(BinaryMeshProtocol.HEADER_SIZE, serialized.size)
        assertArrayEquals(payload, extractedPayload)
    }

    @Test
    fun `deserialize parses valid binary message`() {
        val message = createTestMessage()
        val serialized = protocol.serialize(message)

        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals(message.sourceId, deserialized!!.sourceId)
        assertEquals(message.destinationId, deserialized.destinationId)
        assertEquals(message.messageType, deserialized.messageType)
        assertEquals(message.ttl, deserialized.ttl)
        assertEquals(message.timestamp, deserialized.timestamp)
    }

    @Test
    fun `round trip preserves all message fields`() {
        val payload = byteArrayOf(0x00, 0x01, 0x02, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val message = MeshMessage(
            messageId = "550e8400-e29b-41d4-a716-446655440000",
            sourceId = "node-source",
            destinationId = "node-dest",
            messageType = MeshMessage.MessageType.CONTROL,
            ttl = 10,
            timestamp = 1_703_176_800_000L,
            payload = payload,
        )

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals(message.sourceId, deserialized!!.sourceId)
        assertEquals(message.destinationId, deserialized.destinationId)
        assertEquals(message.messageType, deserialized.messageType)
        assertEquals(message.ttl, deserialized.ttl)
        assertEquals(message.timestamp, deserialized.timestamp)
        assertArrayEquals(message.payload, deserialized.payload)
    }

    @Test
    fun `round trip works for all message types`() {
        for (messageType in MeshMessage.MessageType.entries) {
            val message = createTestMessage(messageType = messageType)

            val serialized = protocol.serialize(message)
            val deserialized = protocol.deserialize(serialized, serialized.size)

            assertNotNull("Failed for message type: $messageType", deserialized)
            assertEquals(messageType, deserialized!!.messageType)
        }
    }

    @Test
    fun `handles empty payload`() {
        val message = createTestMessage(payload = ByteArray(0))

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals(0, deserialized!!.payload.size)
    }

    @Test
    fun `handles large payload`() {
        val largePayload = ByteArray(10_000) { it.toByte() }
        val message = createTestMessage(payload = largePayload)

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertArrayEquals(largePayload, deserialized!!.payload)
    }

    @Test
    fun `handles binary payload with all byte values`() {
        // Create payload with all possible byte values
        val payload = ByteArray(256) { it.toByte() }
        val message = createTestMessage(payload = payload)

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertArrayEquals(payload, deserialized!!.payload)
    }

    @Test
    fun `rejects invalid magic byte`() {
        val message = createTestMessage()
        val serialized = protocol.serialize(message)

        // Corrupt magic byte
        serialized[0] = 0x00

        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNull(deserialized)
    }

    @Test
    fun `rejects truncated header`() {
        val message = createTestMessage()
        val serialized = protocol.serialize(message)

        // Try to deserialize with truncated data
        val deserialized = protocol.deserialize(serialized, BinaryMeshProtocol.HEADER_SIZE - 5)

        assertNull(deserialized)
    }

    @Test
    fun `rejects message with unknown version`() {
        val message = createTestMessage()
        val serialized = protocol.serialize(message)

        // Set major version to 2 (unsupported)
        serialized[1] = 0x20.toByte()

        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNull(deserialized)
    }

    @Test
    fun `handles broadcast destination`() {
        NodeIdEncoder.register("broadcast") // Ensure broadcast is registered
        val message = createTestMessage(destinationId = "broadcast")

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals("broadcast", deserialized!!.destinationId)
    }

    @Test
    fun `protocol version is correct`() {
        assertEquals("2.0", protocol.protocolVersion)
    }

    @Test
    fun `protocol description is non-empty`() {
        assertNotNull(protocol.protocolDescription)
        assertEquals(true, protocol.protocolDescription.isNotEmpty())
    }

    @Test
    fun `deserialize respects length parameter`() {
        val message = createTestMessage(payload = "Hello".toByteArray())
        val serialized = protocol.serialize(message)

        // Create larger buffer with garbage after valid data
        val largerBuffer = ByteArray(serialized.size + 100)
        serialized.copyInto(largerBuffer)
        for (i in serialized.size until largerBuffer.size) {
            largerBuffer[i] = 0xFF.toByte()
        }

        // Deserialize with correct length
        val deserialized = protocol.deserialize(largerBuffer, serialized.size)

        assertNotNull(deserialized)
        assertArrayEquals("Hello".toByteArray(), deserialized!!.payload)
    }

    @Test
    fun `serialize handles maximum TTL value`() {
        val message = createTestMessage(ttl = 255)

        val serialized = protocol.serialize(message)
        val deserialized = protocol.deserialize(serialized, serialized.size)

        assertNotNull(deserialized)
        assertEquals(255, deserialized!!.ttl)
    }

    private fun createTestMessage(
        messageType: MeshMessage.MessageType = MeshMessage.MessageType.DISCOVERY,
        sourceId: String = "node-source",
        destinationId: String = "node-dest",
        ttl: Int = 10,
        payload: ByteArray = "test".toByteArray(),
    ): MeshMessage {
        return MeshMessage(
            messageId = "550e8400-e29b-41d4-a716-446655440000",
            sourceId = sourceId,
            destinationId = destinationId,
            messageType = messageType,
            ttl = ttl,
            timestamp = System.currentTimeMillis(),
            payload = payload,
        )
    }
}
