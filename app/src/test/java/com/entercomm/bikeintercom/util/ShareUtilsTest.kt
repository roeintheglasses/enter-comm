package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.Intent
import com.entercomm.bikeintercom.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShareUtilsTest {

    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockkStatic(Intent::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(Intent::class)
    }

    // === shareText Tests ===

    @Test
    fun `shareText creates intent with correct type and text`() {
        val text = "Share this text"
        val chooserIntent = mockk<Intent>(relaxed = true)

        every { Intent.createChooser(any(), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        val result = ShareUtils.shareText(mockContext, text)

        assertTrue("Should return true on success", result)
        verify { mockContext.startActivity(chooserIntent) }
    }

    @Test
    fun `shareText includes subject when provided`() {
        val text = "Share text"
        val subject = "Share Subject"
        val chooserIntent = mockk<Intent>(relaxed = true)
        val sendIntentSlot = slot<Intent>()

        every { Intent.createChooser(capture(sendIntentSlot), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        ShareUtils.shareText(mockContext, text, subject = subject)

        val capturedIntent = sendIntentSlot.captured
        assertEquals("Intent should have EXTRA_SUBJECT", subject, capturedIntent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `shareText includes chooser title when provided`() {
        val text = "Share text"
        val chooserTitle = "Choose an app"
        val chooserIntent = mockk<Intent>(relaxed = true)
        val titleSlot = slot<CharSequence>()

        every { Intent.createChooser(any(), capture(titleSlot)) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        ShareUtils.shareText(mockContext, text, chooserTitle = chooserTitle)

        assertEquals("Chooser title should match", chooserTitle, titleSlot.captured)
    }

    @Test
    fun `shareText returns false when startActivity throws exception`() {
        val chooserIntent = mockk<Intent>(relaxed = true)

        every { Intent.createChooser(any(), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } throws RuntimeException("No activity found")

        val result = ShareUtils.shareText(mockContext, "text")

        assertFalse("Should return false on exception", result)
    }

    @Test
    fun `shareText handles empty text`() {
        val chooserIntent = mockk<Intent>(relaxed = true)
        val sendIntentSlot = slot<Intent>()

        every { Intent.createChooser(capture(sendIntentSlot), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        val result = ShareUtils.shareText(mockContext, "")

        assertTrue("Should handle empty text", result)
        assertEquals("", sendIntentSlot.captured.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `shareText creates intent with text plain mime type`() {
        val sendIntentSlot = slot<Intent>()
        val chooserIntent = mockk<Intent>(relaxed = true)

        every { Intent.createChooser(capture(sendIntentSlot), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        ShareUtils.shareText(mockContext, "text")

        assertEquals("text/plain", sendIntentSlot.captured.type)
    }

    @Test
    fun `shareText adds NEW_TASK flag to chooser intent`() {
        val chooserIntent = mockk<Intent>(relaxed = true)

        every { Intent.createChooser(any(), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        ShareUtils.shareText(mockContext, "text")

        verify { chooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    // === shareGroupCode Tests ===

    @Test
    fun `shareGroupCode uses localized strings from resources`() {
        val groupCode = "ABC123"
        val appName = "EnterComm"
        val shareMessage = "Join my EnterComm group! Enter code: ABC123"
        val shareSubject = "EnterComm Group Code"
        val chooserTitle = "Share group code"
        val chooserIntent = mockk<Intent>(relaxed = true)

        every { mockContext.getString(R.string.app_name) } returns appName
        every { mockContext.getString(R.string.group_code_share_message, appName, groupCode) } returns shareMessage
        every { mockContext.getString(R.string.group_code_share_subject, appName) } returns shareSubject
        every { mockContext.getString(R.string.group_code_share_chooser_title) } returns chooserTitle
        every { Intent.createChooser(any(), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        val result = ShareUtils.shareGroupCode(mockContext, groupCode)

        assertTrue("Should return true on success", result)
        verify { mockContext.getString(R.string.app_name) }
        verify { mockContext.getString(R.string.group_code_share_message, appName, groupCode) }
        verify { mockContext.getString(R.string.group_code_share_subject, appName) }
        verify { mockContext.getString(R.string.group_code_share_chooser_title) }
    }

    @Test
    fun `shareGroupCode creates intent with formatted share message`() {
        val groupCode = "XYZ789"
        val appName = "TestApp"
        val shareMessage = "Join my TestApp group! Enter code: XYZ789"
        val shareSubject = "TestApp Group Code"
        val chooserTitle = "Share group code"
        val chooserIntent = mockk<Intent>(relaxed = true)
        val sendIntentSlot = slot<Intent>()

        every { mockContext.getString(R.string.app_name) } returns appName
        every { mockContext.getString(R.string.group_code_share_message, appName, groupCode) } returns shareMessage
        every { mockContext.getString(R.string.group_code_share_subject, appName) } returns shareSubject
        every { mockContext.getString(R.string.group_code_share_chooser_title) } returns chooserTitle
        every { Intent.createChooser(capture(sendIntentSlot), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        ShareUtils.shareGroupCode(mockContext, groupCode)

        val capturedIntent = sendIntentSlot.captured
        assertEquals("Should include share message", shareMessage, capturedIntent.getStringExtra(Intent.EXTRA_TEXT))
        assertEquals("Should include subject", shareSubject, capturedIntent.getStringExtra(Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `shareGroupCode returns false when exception is thrown`() {
        every { mockContext.getString(any<Int>()) } throws RuntimeException("Resource not found")

        val result = ShareUtils.shareGroupCode(mockContext, "ABC123")

        assertFalse("Should return false on exception", result)
    }

    @Test
    fun `shareGroupCode handles special characters in group code`() {
        val groupCode = "ABC-123"
        val appName = "EnterComm"
        val shareMessage = "Join my EnterComm group! Enter code: ABC-123"
        val shareSubject = "EnterComm Group Code"
        val chooserTitle = "Share group code"
        val chooserIntent = mockk<Intent>(relaxed = true)
        val sendIntentSlot = slot<Intent>()

        every { mockContext.getString(R.string.app_name) } returns appName
        every { mockContext.getString(R.string.group_code_share_message, appName, groupCode) } returns shareMessage
        every { mockContext.getString(R.string.group_code_share_subject, appName) } returns shareSubject
        every { mockContext.getString(R.string.group_code_share_chooser_title) } returns chooserTitle
        every { Intent.createChooser(capture(sendIntentSlot), any()) } returns chooserIntent
        every { mockContext.startActivity(chooserIntent) } returns Unit

        val result = ShareUtils.shareGroupCode(mockContext, groupCode)

        assertTrue("Should handle special characters in group code", result)
        assertEquals(shareMessage, sendIntentSlot.captured.getStringExtra(Intent.EXTRA_TEXT))
    }
}
