package com.entercomm.bikeintercom.mesh

import org.junit.Assert.*
import org.junit.Test

class MeshMessageTest {

    // === MessageType Enum Tests ===

    @Test
    fun `all expected message types exist`() {
        val expectedTypes = listOf(
            "DISCOVERY",
            "ROUTE_UPDATE",
            "AUDIO_DATA",
            "CONTROL",
            "HEARTBEAT",
            "GROUP",
            "LOCATION",
        )

        val actualTypes = MeshMessage.MessageType.values().map { it.name }

        for (expected in expectedTypes) {
            assertTrue(
                "Missing message type: $expected",
                actualTypes.contains(expected),
            )
        }
    }

    @Test
    fun `message types can be converted to string and back`() {
        for (type in MeshMessage.MessageType.values()) {
            val asString = type.name
            val fromString = MeshMessage.MessageType.valueOf(asString)
            assertEquals(type, fromString)
        }
    }

    // === Message Creation Tests ===

    @Test
    fun `message id is auto-generated when not provided`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = ByteArray(0),
        )

        assertNotNull(message.messageId)
        assertTrue(message.messageId.isNotEmpty())
    }

    @Test
    fun `each message gets unique id`() {
        val message1 = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            payload = ByteArray(0),
        )

        val message2 = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            payload = ByteArray(0),
        )

        assertNotEquals(
            "Each message should have unique ID",
            message1.messageId,
            message2.messageId,
        )
    }

    @Test
    fun `message can use custom id`() {
        val customId = "custom-message-id-123"
        val message = MeshMessage(
            messageId = customId,
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = ByteArray(0),
        )

        assertEquals(customId, message.messageId)
    }

    @Test
    fun `default TTL is 10`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = ByteArray(0),
        )

        assertEquals(10, message.ttl)
    }

    @Test
    fun `custom TTL is preserved`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = ByteArray(0),
            ttl = 5,
        )

        assertEquals(5, message.ttl)
    }

    @Test
    fun `timestamp is auto-generated`() {
        val before = System.currentTimeMillis()
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            payload = ByteArray(0),
        )
        val after = System.currentTimeMillis()

        assertTrue(message.timestamp >= before)
        assertTrue(message.timestamp <= after)
    }

    @Test
    fun `custom timestamp is preserved`() {
        val customTimestamp = 1234567890L
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            payload = ByteArray(0),
            timestamp = customTimestamp,
        )

        assertEquals(customTimestamp, message.timestamp)
    }

    // === Payload Tests ===

    @Test
    fun `empty payload is allowed`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            payload = ByteArray(0),
        )

        assertEquals(0, message.payload.size)
    }

    @Test
    fun `binary payload is preserved`() {
        val payload = byteArrayOf(0x00, 0x7F, 0xFF.toByte(), 0x01)
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            payload = payload,
        )

        assertArrayEquals(payload, message.payload)
    }

    @Test
    fun `string payload can be stored and retrieved`() {
        val text = "Hello, Mesh!"
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = text.toByteArray(),
        )

        assertEquals(text, String(message.payload))
    }

    @Test
    fun `large payload is handled`() {
        val largePayload = ByteArray(16384) { it.toByte() } // 16KB
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            payload = largePayload,
        )

        assertEquals(16384, message.payload.size)
        assertArrayEquals(largePayload, message.payload)
    }

    // === Copy Tests ===

    @Test
    fun `copy preserves all fields`() {
        val original = MeshMessage(
            messageId = "test-id",
            sourceId = "source",
            destinationId = "dest",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = "data".toByteArray(),
            ttl = 7,
            timestamp = 12345L,
        )

        val copy = original.copy()

        assertEquals(original.messageId, copy.messageId)
        assertEquals(original.sourceId, copy.sourceId)
        assertEquals(original.destinationId, copy.destinationId)
        assertEquals(original.messageType, copy.messageType)
        assertArrayEquals(original.payload, copy.payload)
        assertEquals(original.ttl, copy.ttl)
        assertEquals(original.timestamp, copy.timestamp)
    }

    @Test
    fun `copy with decreased TTL for forwarding`() {
        val original = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = ByteArray(0),
            ttl = 10,
        )

        val forwarded = original.copy(ttl = original.ttl - 1)

        assertEquals(9, forwarded.ttl)
        assertEquals(original.sourceId, forwarded.sourceId)
    }

    // === Message Type Specific Tests ===

    @Test
    fun `discovery message can be created`() {
        val message = MeshMessage(
            sourceId = "node-123",
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.DISCOVERY,
            payload = "node-123|DeviceName|GROUPCODE".toByteArray(),
        )

        assertEquals(MeshMessage.MessageType.DISCOVERY, message.messageType)
    }

    @Test
    fun `audio data message can be created`() {
        val audioData = ByteArray(960) { (it % 256).toByte() }
        val message = MeshMessage(
            sourceId = "node-A",
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.AUDIO_DATA,
            payload = audioData,
        )

        assertEquals(MeshMessage.MessageType.AUDIO_DATA, message.messageType)
        assertEquals(960, message.payload.size)
    }

    @Test
    fun `heartbeat message typically has empty payload`() {
        val message = MeshMessage(
            sourceId = "node-A",
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.HEARTBEAT,
            payload = ByteArray(0),
        )

        assertEquals(MeshMessage.MessageType.HEARTBEAT, message.messageType)
        assertEquals(0, message.payload.size)
    }

    @Test
    fun `group message can carry group data`() {
        val groupData = "JOIN|group-abc|nickname".toByteArray()
        val message = MeshMessage(
            sourceId = "node-A",
            destinationId = "node-B",
            messageType = MeshMessage.MessageType.GROUP,
            payload = groupData,
        )

        assertEquals(MeshMessage.MessageType.GROUP, message.messageType)
    }

    @Test
    fun `location message can carry coordinates`() {
        val locationData = "Nickname|40.7128|-74.0060|0.0|10.0|180.0|5.0|12345".toByteArray()
        val message = MeshMessage(
            sourceId = "node-A",
            destinationId = "broadcast",
            messageType = MeshMessage.MessageType.LOCATION,
            payload = locationData,
        )

        assertEquals(MeshMessage.MessageType.LOCATION, message.messageType)
    }

    // === Edge Cases ===

    @Test
    fun `source and destination can be same for self-addressed messages`() {
        val message = MeshMessage(
            sourceId = "node-A",
            destinationId = "node-A",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = ByteArray(0),
        )

        assertEquals(message.sourceId, message.destinationId)
    }

    @Test
    fun `zero TTL is valid`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = ByteArray(0),
            ttl = 0,
        )

        assertEquals(0, message.ttl)
    }

    @Test
    fun `negative TTL is valid but indicates expired`() {
        val message = MeshMessage(
            sourceId = "src",
            destinationId = "dst",
            messageType = MeshMessage.MessageType.CONTROL,
            payload = ByteArray(0),
            ttl = -1,
        )

        assertTrue(message.ttl < 0)
    }
}
