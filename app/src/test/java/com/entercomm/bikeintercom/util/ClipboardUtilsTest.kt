package com.entercomm.bikeintercom.util

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ClipboardUtilsTest {

    private lateinit var mockContext: Context
    private lateinit var mockClipboardManager: ClipboardManager
    private lateinit var mockClipData: ClipData
    private lateinit var mockClipItem: ClipData.Item
    private lateinit var mockClipDescription: ClipDescription

    @Before
    fun setUp() {
        // Enable test mode to avoid Android Log calls
        Logger.isTestMode = true

        mockContext = mockk(relaxed = true)
        mockClipboardManager = mockk(relaxed = true)
        mockClipData = mockk(relaxed = true)
        mockClipItem = mockk(relaxed = true)
        mockClipDescription = mockk(relaxed = true)

        every { mockContext.getSystemService(Context.CLIPBOARD_SERVICE) } returns mockClipboardManager

        // Mock the static ClipData.newPlainText method
        mockkStatic(ClipData::class)
        every { ClipData.newPlainText(any(), any()) } returns mockClipData
    }

    @After
    fun tearDown() {
        unmockkAll()
        Logger.isTestMode = false
    }

    // === copyToClipboard Tests ===

    @Test
    fun `copyToClipboard sets primary clip with correct data`() {
        val text = "ABC123"
        val label = "Group Code"
        val labelSlot = slot<CharSequence>()
        val textSlot = slot<CharSequence>()

        every { ClipData.newPlainText(capture(labelSlot), capture(textSlot)) } returns mockClipData
        every { mockClipboardManager.setPrimaryClip(mockClipData) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, text, label)

        assertTrue("Should return true on success", result)
        verify { mockClipboardManager.setPrimaryClip(mockClipData) }
        assertEquals("Label should match", label, labelSlot.captured.toString())
        assertEquals("Text should match", text, textSlot.captured.toString())
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
        val textSlot = slot<CharSequence>()
        every { ClipData.newPlainText(any(), capture(textSlot)) } returns mockClipData
        every { mockClipboardManager.setPrimaryClip(mockClipData) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, "", "label")

        assertTrue("Should handle empty text", result)
        assertEquals("Empty text should be captured", "", textSlot.captured.toString())
    }

    @Test
    fun `copyToClipboard handles special characters`() {
        val specialText = "ABC-123!@#\$%^&*()"
        val textSlot = slot<CharSequence>()
        every { ClipData.newPlainText(any(), capture(textSlot)) } returns mockClipData
        every { mockClipboardManager.setPrimaryClip(mockClipData) } returns Unit

        val result = ClipboardUtils.copyToClipboard(mockContext, specialText, "label")

        assertTrue("Should handle special characters", result)
        assertEquals("Special characters should be preserved", specialText, textSlot.captured.toString())
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
