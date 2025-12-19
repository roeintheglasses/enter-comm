package com.entercomm.bikeintercom.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClipboardUtilsTest {

    private lateinit var mockContext: Context
    private lateinit var mockClipboardManager: ClipboardManager

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockClipboardManager = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockClipboardManager
    }

    // === copyToClipboard Tests ===

    @Test
    fun `copyToClipboard sets primary clip with correct data`() {
        val text = "ABC123"
        val label = "Group Code"
        val clipDataSlot = slot<ClipData>()

        every { mockClipboardManager.setPrimaryClip(capture(clipDataSlot)) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, text, label)

        assertTrue("Should return true on success", result)
        verify { mockClipboardManager.setPrimaryClip(any()) }

        val capturedClipData = clipDataSlot.captured
        assertEquals("Clip data should have one item", 1, capturedClipData.itemCount)
        assertEquals("Clip data text should match", text, capturedClipData.getItemAt(0).text)
        assertEquals("Clip data label should match", label, capturedClipData.description.label)
    }

    @Test
    fun `copyToClipboard returns false when clipboard manager throws exception`() {
        every { mockContext.getSystemService(Context.CLIPBOARD_SERVICE) } throws RuntimeException("No clipboard service")

        val result = ClipboardUtils.copyToClipboard(mockContext, "text", "label")

        assertFalse("Should return false on exception", result)
    }

    @Test
    fun `copyToClipboard returns false when setPrimaryClip throws exception`() {
        every { mockClipboardManager.setPrimaryClip(any()) } throws SecurityException("Permission denied")

        val result = ClipboardUtils.copyToClipboard(mockContext, "text", "label")

        assertFalse("Should return false on exception", result)
    }

    @Test
    fun `copyToClipboard handles empty text`() {
        val clipDataSlot = slot<ClipData>()
        every { mockClipboardManager.setPrimaryClip(capture(clipDataSlot)) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, "", "label")

        assertTrue("Should handle empty text", result)
        assertEquals("", clipDataSlot.captured.getItemAt(0).text)
    }

    @Test
    fun `copyToClipboard handles special characters`() {
        val specialText = "ABC-123!@#$%^&*()"
        val clipDataSlot = slot<ClipData>()
        every { mockClipboardManager.setPrimaryClip(capture(clipDataSlot)) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, specialText, "label")

        assertTrue("Should handle special characters", result)
        assertEquals(specialText, clipDataSlot.captured.getItemAt(0).text)
    }

    // === hasClipboardText Tests ===

    @Test
    fun `hasClipboardText returns true when clipboard has plain text`() {
        val mockClipDescription = mockk<ClipDescription>()
        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClipDescription } returns mockClipDescription
        every { mockClipDescription.hasMimeType("text/plain") } returns true

        val result = ClipboardUtils.hasClipboardText(mockContext)

        assertTrue("Should return true when clipboard has text", result)
    }

    @Test
    fun `hasClipboardText returns false when clipboard is empty`() {
        every { mockClipboardManager.hasPrimaryClip() } returns false

        val result = ClipboardUtils.hasClipboardText(mockContext)

        assertFalse("Should return false when clipboard is empty", result)
    }

    @Test
    fun `hasClipboardText returns false when clipboard has non-text content`() {
        val mockClipDescription = mockk<ClipDescription>()
        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClipDescription } returns mockClipDescription
        every { mockClipDescription.hasMimeType("text/plain") } returns false

        val result = ClipboardUtils.hasClipboardText(mockContext)

        assertFalse("Should return false for non-text content", result)
    }

    @Test
    fun `hasClipboardText returns false when clipboard description is null`() {
        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClipDescription } returns null

        val result = ClipboardUtils.hasClipboardText(mockContext)

        assertFalse("Should return false when description is null", result)
    }

    @Test
    fun `hasClipboardText returns false when exception is thrown`() {
        every { mockClipboardManager.hasPrimaryClip() } throws SecurityException("Permission denied")

        val result = ClipboardUtils.hasClipboardText(mockContext)

        assertFalse("Should return false on exception", result)
    }

    // === getClipboardText Tests ===

    @Test
    fun `getClipboardText returns text when clipboard has content`() {
        val expectedText = "Clipboard content"
        val mockClipData = mockk<ClipData>()
        val mockClipItem = mockk<ClipData.Item>()

        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClip } returns mockClipData
        every { mockClipData.getItemAt(0) } returns mockClipItem
        every { mockClipItem.text } returns expectedText

        val result = ClipboardUtils.getClipboardText(mockContext)

        assertEquals("Should return clipboard text", expectedText, result)
    }

    @Test
    fun `getClipboardText returns null when clipboard is empty`() {
        every { mockClipboardManager.hasPrimaryClip() } returns false

        val result = ClipboardUtils.getClipboardText(mockContext)

        assertNull("Should return null when clipboard is empty", result)
    }

    @Test
    fun `getClipboardText returns null when primaryClip is null`() {
        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClip } returns null

        val result = ClipboardUtils.getClipboardText(mockContext)

        assertNull("Should return null when primary clip is null", result)
    }

    @Test
    fun `getClipboardText returns null when exception is thrown`() {
        every { mockClipboardManager.hasPrimaryClip() } throws SecurityException("Permission denied")

        val result = ClipboardUtils.getClipboardText(mockContext)

        assertNull("Should return null on exception", result)
    }

    @Test
    fun `getClipboardText handles null text in clip item`() {
        val mockClipData = mockk<ClipData>()
        val mockClipItem = mockk<ClipData.Item>()

        every { mockClipboardManager.hasPrimaryClip() } returns true
        every { mockClipboardManager.primaryClip } returns mockClipData
        every { mockClipData.getItemAt(0) } returns mockClipItem
        every { mockClipItem.text } returns null

        val result = ClipboardUtils.getClipboardText(mockContext)

        assertNull("Should return null when clip item text is null", result)
    }
}
