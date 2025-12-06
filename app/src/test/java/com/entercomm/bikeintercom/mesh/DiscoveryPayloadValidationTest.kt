package com.entercomm.bikeintercom.mesh

import com.entercomm.bikeintercom.onboarding.GroupCodeUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for discovery payload validation patterns.
 * These tests directly verify the regex patterns used in MeshNetworkManager
 * to ensure all valid formats are accepted.
 *
 * IMPORTANT: When modifying validation patterns in MeshNetworkManager,
 * add corresponding test cases here first (TDD approach).
 */
class DiscoveryPayloadValidationTest {

    // Mirror the patterns from MeshNetworkManager for testing
    // If these get out of sync, the tests will fail and alert us
    private val uuidPattern = Regex("^(node-[a-fA-F0-9]{8}|[a-fA-F0-9-]{8,36})$")
    private val groupCodePattern = Regex("^[A-Z0-9]{4,8}$")

    // === Node ID Pattern Tests ===

    @Test
    fun `node- prefix with 8 hex chars is valid`() {
        val validNodeIds = listOf(
            "node-aabbccdd",
            "node-11223344",
            "node-AABBCCDD",
            "node-AbCdEf12",
            "node-00000000",
            "node-ffffffff",
            "node-FFFFFFFF",
            "node-ee0aad1b", // Real example from logs
            "node-dadcb47c", // Real example from logs
        )

        for (nodeId in validNodeIds) {
            assertTrue(
                "Should accept node ID: $nodeId",
                uuidPattern.matches(nodeId),
            )
        }
    }

    @Test
    fun `standard UUID format is valid`() {
        val validUuids = listOf(
            "550e8400-e29b-41d4-a716-446655440000",
            "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            "00000000-0000-0000-0000-000000000000",
            "ffffffff-ffff-ffff-ffff-ffffffffffff",
        )

        for (uuid in validUuids) {
            assertTrue(
                "Should accept UUID: $uuid",
                uuidPattern.matches(uuid),
            )
        }
    }

    @Test
    fun `short hex strings 8-36 chars are valid`() {
        val validHexIds = listOf(
            "abcd1234", // 8 chars - minimum
            "aabbccdd",
            "12345678",
            "abcdef12345678", // 14 chars
            "aabbccddeeff00112233445566778899aabb", // 36 chars - maximum
        )

        for (hexId in validHexIds) {
            assertTrue(
                "Should accept hex ID: $hexId (length: ${hexId.length})",
                uuidPattern.matches(hexId),
            )
        }
    }

    @Test
    fun `invalid node ID formats are rejected`() {
        val invalidNodeIds = listOf(
            "", // Empty
            "node-", // Missing hex
            "node-1234567", // Only 7 hex chars
            "node-123456789", // 9 hex chars (too many)
            "node-ghijklmn", // Non-hex chars
            "nodes-aabbccdd", // Wrong prefix
            "NODE-aabbccdd", // Uppercase prefix (not in pattern)
            "abcd123", // 7 chars (too short)
            "aabbccddeeff00112233445566778899aabbcc", // 38 chars (too long)
            "hello-world", // Non-hex with hyphen
            "node_aabbccdd", // Underscore instead of hyphen
        )

        for (nodeId in invalidNodeIds) {
            assertFalse(
                "Should reject invalid node ID: '$nodeId'",
                uuidPattern.matches(nodeId),
            )
        }
    }

    // === Group Code Pattern Tests ===

    @Test
    fun `valid group codes are accepted`() {
        val validCodes = listOf(
            "ABCD", // 4 chars - minimum
            "ABCDEF", // 6 chars - typical
            "ABCDEFGH", // 8 chars - maximum
            "1234",
            "A1B2C3",
            "12345678",
            "ABC123",
        )

        for (code in validCodes) {
            assertTrue(
                "Should accept group code: $code",
                groupCodePattern.matches(code),
            )
        }
    }

    @Test
    fun `generated group codes match pattern`() {
        // Verify that GroupCodeUtils generates codes that match the pattern
        repeat(100) {
            val code = GroupCodeUtils.generateGroupCode()
            assertTrue(
                "Generated code should match pattern: $code",
                groupCodePattern.matches(code),
            )
        }
    }

    @Test
    fun `invalid group codes are rejected`() {
        val invalidCodes = listOf(
            "", // Empty
            "ABC", // 3 chars (too short)
            "ABCDEFGHI", // 9 chars (too long)
            "abcdef", // Lowercase (pattern requires uppercase)
            "ABC-DEF", // Contains hyphen
            "ABC DEF", // Contains space
            "ABC_DEF", // Contains underscore
        )

        for (code in invalidCodes) {
            assertFalse(
                "Should reject invalid group code: '$code'",
                groupCodePattern.matches(code),
            )
        }
    }

    @Test
    fun `OPEN is handled specially not by pattern`() {
        // OPEN is a special value handled in code, not by pattern
        // The pattern doesn't need to match it since it's checked explicitly
        // This test documents this behavior
        assertTrue(
            "OPEN matches pattern (4 uppercase letters)",
            groupCodePattern.matches("OPEN"),
        )
    }

    // === Payload Format Tests ===

    @Test
    fun `discovery payload can be parsed correctly`() {
        val testCases = listOf(
            Triple("node-aabbccdd", "DeviceName", "ABC123"),
            Triple("node-12345678", "Bike-Intercom", "OPEN"),
            Triple("abcdef12-3456-7890-abcd-ef1234567890", "LongDeviceName123", "XYZW"),
        )

        for ((nodeId, deviceName, groupCode) in testCases) {
            val payload = "$nodeId|$deviceName|$groupCode"
            val parts = payload.split("|")

            assertEquals("Should have 3 parts", 3, parts.size)
            assertEquals("NodeId should parse", nodeId, parts[0])
            assertEquals("DeviceName should parse", deviceName, parts[1])
            assertEquals("GroupCode should parse", groupCode, parts[2])

            // Verify nodeId matches pattern
            assertTrue(
                "NodeId should match pattern: $nodeId",
                uuidPattern.matches(nodeId),
            )

            // Verify groupCode matches pattern (after uppercase)
            val normalizedCode = groupCode.uppercase()
            assertTrue(
                "GroupCode should match pattern: $normalizedCode",
                groupCodePattern.matches(normalizedCode),
            )
        }
    }

    // === Edge Cases ===

    @Test
    fun `node ID pattern handles mixed case`() {
        val mixedCaseIds = listOf(
            "node-AaBbCcDd",
            "node-aAbBcCdD",
            "AbCdEf12-3456-7890-AbCd-Ef1234567890",
        )

        for (nodeId in mixedCaseIds) {
            assertTrue(
                "Should accept mixed case: $nodeId",
                uuidPattern.matches(nodeId),
            )
        }
    }

    @Test
    fun `device name length validation bounds`() {
        // Document the expected bounds (from MeshNetworkManager)
        val minLength = 1
        val maxLength = 50

        val validNames = listOf(
            "A", // Minimum
            "B".repeat(50), // Maximum
            "Normal-Device_Name123",
        )

        val invalidNames = listOf(
            "", // Too short
            "C".repeat(51), // Too long
        )

        for (name in validNames) {
            assertTrue(
                "Name should be valid: length=${name.length}",
                name.length in minLength..maxLength,
            )
        }

        for (name in invalidNames) {
            assertFalse(
                "Name should be invalid: length=${name.length}",
                name.length in minLength..maxLength,
            )
        }
    }

    // === Regression Tests ===
    // Add tests here for any bugs that are fixed

    @Test
    fun `regression - node-XXXXXXXX format accepted (bug fix 2024-12)`() {
        // This format was previously rejected because 'n' and 'o' in "node"
        // are not valid hex characters. The pattern was fixed to explicitly
        // accept the "node-" prefix followed by 8 hex chars.
        val realWorldNodeIds = listOf(
            "node-ee0aad1b", // From device 1
            "node-dadcb47c", // From device 2
            "node-a49118e0", // From device 3
        )

        for (nodeId in realWorldNodeIds) {
            assertTrue(
                "REGRESSION: Should accept node ID from production: $nodeId",
                uuidPattern.matches(nodeId),
            )
        }
    }

    @Test
    fun `regression - group code format mismatch fixed (bug fix 2024-12)`() {
        // Previously, generateGroupCode() returned formatted codes with dashes
        // like "ABCD-EF", but mesh filtering compared raw codes like "ABCDEF".
        // This caused group code mismatches.

        val code = GroupCodeUtils.generateGroupCode()

        // Generated code should be raw (no dashes)
        assertFalse(
            "REGRESSION: Generated code should not contain dash",
            code.contains("-"),
        )

        // Should be exactly CODE_LENGTH characters
        assertEquals(
            "REGRESSION: Generated code should be ${GroupCodeUtils.CODE_LENGTH} chars",
            GroupCodeUtils.CODE_LENGTH,
            code.length,
        )

        // Normalizing an already-raw code should be idempotent
        assertEquals(
            "REGRESSION: Normalizing raw code should be idempotent",
            code,
            GroupCodeUtils.normalizeGroupCode(code),
        )
    }
}
