package com.entercomm.bikeintercom.onboarding

import org.junit.Assert.*
import org.junit.Test

class GroupCodeUtilsTest {

    @Test
    fun `generateGroupCode returns formatted code`() {
        val code = GroupCodeUtils.generateGroupCode()

        // Should be in format XXXX-XX
        assertTrue("Code should contain dash", code.contains("-"))
        assertEquals("Code should have correct format length", 7, code.length)

        // All characters should be valid
        val normalized = code.replace("-", "")
        assertTrue(
            "All characters should be valid",
            normalized.all { it in GroupCodeUtils.CODE_CHARS }
        )
    }

    @Test
    fun `generateGroupCode generates unique codes`() {
        val codes = (1..100).map { GroupCodeUtils.generateGroupCode() }.toSet()

        // With 30 possible characters and 6 positions, probability of duplicates is very low
        assertTrue("Should generate mostly unique codes", codes.size > 95)
    }

    @Test
    fun `isValidGroupCode returns true for valid codes`() {
        assertTrue(GroupCodeUtils.isValidGroupCode("ABCD-EF"))
        assertTrue(GroupCodeUtils.isValidGroupCode("ABCDEF"))
        assertTrue(GroupCodeUtils.isValidGroupCode("abcd-ef"))  // lowercase
        assertTrue(GroupCodeUtils.isValidGroupCode("abcdef"))
        assertTrue(GroupCodeUtils.isValidGroupCode("ABC DEF"))  // with space
        assertTrue(GroupCodeUtils.isValidGroupCode("2345-67"))
        assertTrue(GroupCodeUtils.isValidGroupCode("RIDE-4K"))
    }

    @Test
    fun `isValidGroupCode returns false for invalid codes`() {
        assertFalse(GroupCodeUtils.isValidGroupCode(""))  // empty
        assertFalse(GroupCodeUtils.isValidGroupCode("ABC"))  // too short
        assertFalse(GroupCodeUtils.isValidGroupCode("ABCDEFGH"))  // too long
        assertFalse(GroupCodeUtils.isValidGroupCode("ABC0EF"))  // contains 0 (invalid)
        assertFalse(GroupCodeUtils.isValidGroupCode("ABC1EF"))  // contains 1 (invalid)
        assertFalse(GroupCodeUtils.isValidGroupCode("ABCIEF"))  // contains I (invalid)
        assertFalse(GroupCodeUtils.isValidGroupCode("ABCLEF"))  // contains L (invalid)
        assertFalse(GroupCodeUtils.isValidGroupCode("ABCOEF"))  // contains O (invalid)
    }

    @Test
    fun `normalizeGroupCode removes dashes and uppercases`() {
        assertEquals("ABCDEF", GroupCodeUtils.normalizeGroupCode("abcd-ef"))
        assertEquals("ABCDEF", GroupCodeUtils.normalizeGroupCode("ABCD-EF"))
        assertEquals("ABCDEF", GroupCodeUtils.normalizeGroupCode("abc def"))
        assertEquals("ABCDEF", GroupCodeUtils.normalizeGroupCode("abcdef"))
        assertEquals("ABCDEF", GroupCodeUtils.normalizeGroupCode("  abcd-ef  ".trim()))
    }

    @Test
    fun `formatGroupCode adds dash in correct position`() {
        assertEquals("ABCD-EF", GroupCodeUtils.formatGroupCode("abcdef"))
        assertEquals("ABCD-EF", GroupCodeUtils.formatGroupCode("ABCDEF"))
        assertEquals("ABCD-EF", GroupCodeUtils.formatGroupCode("abcd-ef"))
        assertEquals("ABCD-EF", GroupCodeUtils.formatGroupCode("ABCD-EF"))
    }

    @Test
    fun `formatGroupCode handles short codes`() {
        assertEquals("ABC", GroupCodeUtils.formatGroupCode("abc"))
        assertEquals("AB", GroupCodeUtils.formatGroupCode("ab"))
    }

    @Test
    fun `CODE_CHARS does not contain ambiguous characters`() {
        val ambiguous = listOf('0', '1', 'I', 'L', 'O')
        ambiguous.forEach { char ->
            assertFalse(
                "CODE_CHARS should not contain $char",
                char in GroupCodeUtils.CODE_CHARS
            )
        }
    }

    @Test
    fun `CODE_LENGTH is correct`() {
        assertEquals(6, GroupCodeUtils.CODE_LENGTH)
    }

    @Test
    fun `round trip validation works`() {
        // Generate a code, normalize it, format it, and validate
        repeat(50) {
            val generated = GroupCodeUtils.generateGroupCode()
            val normalized = GroupCodeUtils.normalizeGroupCode(generated)
            val formatted = GroupCodeUtils.formatGroupCode(normalized)

            assertTrue("Generated code should be valid", GroupCodeUtils.isValidGroupCode(generated))
            assertTrue("Normalized code should be valid", GroupCodeUtils.isValidGroupCode(normalized))
            assertTrue("Formatted code should be valid", GroupCodeUtils.isValidGroupCode(formatted))
            assertEquals("Format should be consistent", generated, formatted)
        }
    }
}
