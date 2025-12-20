package com.entercomm.bikeintercom.util

import org.junit.Assert.*
import org.junit.Test

class MeshErrorTest {

    // === MeshError Sealed Class Structure Tests ===

    @Test
    fun `all expected MeshError types can be instantiated`() {
        // Verify all expected MeshError types exist and can be created
        val allErrorTypes: List<MeshError> = listOf(
            MeshError.ConnectionFailed("test"),
            MeshError.PermissionDenied("test"),
            MeshError.NetworkUnavailable("test"),
            MeshError.AudioError("test"),
            MeshError.ServiceError("test"),
            MeshError.Timeout("test"),
            MeshError.Unknown("test"),
            MeshError.InvalidGroupCode("test"),
            MeshError.GroupFull("test"),
            MeshError.LocationUnavailable("test"),
            MeshError.EncryptionFailed("test"),
            MeshError.BannedFromGroup("test"),
        )

        assertEquals("Should have 12 MeshError types", 12, allErrorTypes.size)

        // Verify all are instances of MeshError
        for (error in allErrorTypes) {
            assertTrue(
                "All types should be instances of MeshError",
                error is MeshError,
            )
        }
    }

    // === ConnectionFailed Error Tests ===

    @Test
    fun `ConnectionFailed stores message correctly`() {
        val message = "Unable to connect to peer"
        val error = MeshError.ConnectionFailed(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `ConnectionFailed stores cause when provided`() {
        val message = "Connection timeout"
        val cause = RuntimeException("Socket timeout")
        val error = MeshError.ConnectionFailed(message, cause)

        assertEquals(message, error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `ConnectionFailed has null cause when not provided`() {
        val error = MeshError.ConnectionFailed("Error")

        assertNull(error.cause)
    }

    // === PermissionDenied Error Tests ===

    @Test
    fun `PermissionDenied stores message correctly`() {
        val message = "Bluetooth permission not granted"
        val error = MeshError.PermissionDenied(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `PermissionDenied has null cause`() {
        val error = MeshError.PermissionDenied("Permission denied")

        assertNull(error.cause)
    }

    // === NetworkUnavailable Error Tests ===

    @Test
    fun `NetworkUnavailable stores message correctly`() {
        val message = "WiFi Direct not available"
        val error = MeshError.NetworkUnavailable(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `NetworkUnavailable has null cause`() {
        val error = MeshError.NetworkUnavailable("Network unavailable")

        assertNull(error.cause)
    }

    // === AudioError Tests ===

    @Test
    fun `AudioError stores message correctly`() {
        val message = "Failed to initialize audio"
        val error = MeshError.AudioError(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `AudioError stores cause when provided`() {
        val message = "Audio recording failed"
        val cause = IllegalStateException("Audio track not initialized")
        val error = MeshError.AudioError(message, cause)

        assertEquals(message, error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `AudioError has null cause when not provided`() {
        val error = MeshError.AudioError("Audio error")

        assertNull(error.cause)
    }

    // === ServiceError Tests ===

    @Test
    fun `ServiceError stores message correctly`() {
        val message = "Mesh service failed to start"
        val error = MeshError.ServiceError(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `ServiceError stores cause when provided`() {
        val message = "Service binding failed"
        val cause = Exception("Context not available")
        val error = MeshError.ServiceError(message, cause)

        assertEquals(message, error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `ServiceError has null cause when not provided`() {
        val error = MeshError.ServiceError("Service error")

        assertNull(error.cause)
    }

    // === Timeout Error Tests ===

    @Test
    fun `Timeout stores message correctly`() {
        val message = "Connection timeout after 30 seconds"
        val error = MeshError.Timeout(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `Timeout has null cause`() {
        val error = MeshError.Timeout("Operation timed out")

        assertNull(error.cause)
    }

    // === Unknown Error Tests ===

    @Test
    fun `Unknown stores message correctly`() {
        val message = "An unexpected error occurred"
        val error = MeshError.Unknown(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `Unknown stores cause when provided`() {
        val message = "Unknown error"
        val cause = Throwable("Root cause")
        val error = MeshError.Unknown(message, cause)

        assertEquals(message, error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `Unknown has null cause when not provided`() {
        val error = MeshError.Unknown("Unknown error")

        assertNull(error.cause)
    }

    // === InvalidGroupCode Error Tests ===

    @Test
    fun `InvalidGroupCode stores message correctly`() {
        val message = "Group code must be 6 characters"
        val error = MeshError.InvalidGroupCode(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `InvalidGroupCode has null cause`() {
        val error = MeshError.InvalidGroupCode("Invalid code")

        assertNull(error.cause)
    }

    @Test
    fun `InvalidGroupCode is instance of MeshError`() {
        val error = MeshError.InvalidGroupCode("Invalid")

        assertTrue(error is MeshError)
    }

    // === GroupFull Error Tests ===

    @Test
    fun `GroupFull stores message correctly`() {
        val message = "Group has reached maximum capacity of 8 members"
        val error = MeshError.GroupFull(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `GroupFull has null cause`() {
        val error = MeshError.GroupFull("Group is full")

        assertNull(error.cause)
    }

    @Test
    fun `GroupFull is instance of MeshError`() {
        val error = MeshError.GroupFull("Full")

        assertTrue(error is MeshError)
    }

    // === LocationUnavailable Error Tests ===

    @Test
    fun `LocationUnavailable stores message correctly`() {
        val message = "GPS provider is disabled"
        val error = MeshError.LocationUnavailable(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `LocationUnavailable has null cause`() {
        val error = MeshError.LocationUnavailable("Location unavailable")

        assertNull(error.cause)
    }

    @Test
    fun `LocationUnavailable is instance of MeshError`() {
        val error = MeshError.LocationUnavailable("Unavailable")

        assertTrue(error is MeshError)
    }

    // === EncryptionFailed Error Tests ===

    @Test
    fun `EncryptionFailed stores message correctly`() {
        val message = "Failed to encrypt message"
        val error = MeshError.EncryptionFailed(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `EncryptionFailed stores cause when provided`() {
        val message = "Decryption failed"
        val cause = SecurityException("Invalid key")
        val error = MeshError.EncryptionFailed(message, cause)

        assertEquals(message, error.message)
        assertEquals(cause, error.cause)
    }

    @Test
    fun `EncryptionFailed has null cause when not provided`() {
        val error = MeshError.EncryptionFailed("Encryption error")

        assertNull(error.cause)
    }

    @Test
    fun `EncryptionFailed is instance of MeshError`() {
        val error = MeshError.EncryptionFailed("Error")

        assertTrue(error is MeshError)
    }

    // === BannedFromGroup Error Tests ===

    @Test
    fun `BannedFromGroup stores message correctly`() {
        val message = "You have been banned from this group"
        val error = MeshError.BannedFromGroup(message)

        assertEquals(message, error.message)
    }

    @Test
    fun `BannedFromGroup has null cause`() {
        val error = MeshError.BannedFromGroup("Banned")

        assertNull(error.cause)
    }

    @Test
    fun `BannedFromGroup is instance of MeshError`() {
        val error = MeshError.BannedFromGroup("Banned")

        assertTrue(error is MeshError)
    }

    // === Pattern Matching Tests ===

    @Test
    fun `MeshError types can be matched with when expression`() {
        val errors: List<MeshError> = listOf(
            MeshError.ConnectionFailed("Connection failed"),
            MeshError.PermissionDenied("Permission denied"),
            MeshError.NetworkUnavailable("Network unavailable"),
            MeshError.AudioError("Audio error"),
            MeshError.ServiceError("Service error"),
            MeshError.Timeout("Timeout"),
            MeshError.Unknown("Unknown"),
            MeshError.InvalidGroupCode("Invalid code"),
            MeshError.GroupFull("Group full"),
            MeshError.LocationUnavailable("Location unavailable"),
            MeshError.EncryptionFailed("Encryption failed"),
            MeshError.BannedFromGroup("Banned"),
        )

        for (error in errors) {
            val result = when (error) {
                is MeshError.ConnectionFailed -> "ConnectionFailed"
                is MeshError.PermissionDenied -> "PermissionDenied"
                is MeshError.NetworkUnavailable -> "NetworkUnavailable"
                is MeshError.AudioError -> "AudioError"
                is MeshError.ServiceError -> "ServiceError"
                is MeshError.Timeout -> "Timeout"
                is MeshError.Unknown -> "Unknown"
                is MeshError.InvalidGroupCode -> "InvalidGroupCode"
                is MeshError.GroupFull -> "GroupFull"
                is MeshError.LocationUnavailable -> "LocationUnavailable"
                is MeshError.EncryptionFailed -> "EncryptionFailed"
                is MeshError.BannedFromGroup -> "BannedFromGroup"
            }

            assertNotNull("Pattern matching should work for all types", result)
        }
    }

    // === Edge Cases ===

    @Test
    fun `MeshError with empty message is valid`() {
        val error = MeshError.Unknown("")

        assertEquals("", error.message)
    }

    @Test
    fun `MeshError with special characters in message is valid`() {
        val specialMessage = "Error: !@#$%^&*()_+-={}|[]\\:\";'<>?,./`~"
        val error = MeshError.ServiceError(specialMessage)

        assertEquals(specialMessage, error.message)
    }

    @Test
    fun `MeshError with unicode in message is valid`() {
        val unicodeMessage = "Error with unicode: \u4e2d\u6587 \u0435\u0432\u0440\u043e"
        val error = MeshError.Unknown(unicodeMessage)

        assertEquals(unicodeMessage, error.message)
    }

    @Test
    fun `MeshError with multiline message is valid`() {
        val multilineMessage = "Error on line 1\nError on line 2\nError on line 3"
        val error = MeshError.ConnectionFailed(multilineMessage)

        assertEquals(multilineMessage, error.message)
    }

    @Test
    fun `nested exception cause chain is preserved`() {
        val rootCause = Exception("Root cause")
        val middleCause = RuntimeException("Middle cause", rootCause)
        val error = MeshError.ServiceError("Service failed", middleCause)

        assertEquals(middleCause, error.cause)
        assertEquals(rootCause, error.cause?.cause)
    }
}
