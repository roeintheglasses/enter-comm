package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.Intent
import com.entercomm.bikeintercom.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ShareUtilsTest {

    private lateinit var mockContext: Context
    private lateinit var mockChooserIntent: Intent

    @Before
    fun setUp() {
        // Enable test mode to avoid Android Log calls
        Logger.isTestMode = true

        mockContext = mockk(relaxed = true)
        mockChooserIntent = mockk(relaxed = true)

        // Mock Intent constructor and static methods
        mockkConstructor(Intent::class)
        mockkStatic(Intent::class)

        // Make Intent constructor return a relaxed mock that chains properly
        every { anyConstructed<Intent>().setType(any()) } answers { self as Intent }
        every { anyConstructed<Intent>().putExtra(any<String>(), any<String>()) } answers { self as Intent }
        every { Intent.createChooser(any(), any()) } returns mockChooserIntent
    }

    @After
    fun tearDown() {
        unmockkAll()
        Logger.isTestMode = false
    }

    // === shareText Tests ===

    @Test
    fun `shareText creates intent and starts activity`() {
        val text = "Share this text"

        every { mockContext.startActivity(mockChooserIntent) } returns Unit

        val result = ShareUtils.shareText(mockContext, text)

        assertTrue("Should return true on success", result)
        verify { mockContext.startActivity(mockChooserIntent) }
    }

    @Test
    fun `shareText returns false when startActivity throws exception`() {
        every { mockContext.startActivity(any()) } throws RuntimeException("No activity found")

        val result = ShareUtils.shareText(mockContext, "text")

        assertFalse("Should return false on exception", result)
    }

    @Test
    fun `shareText handles empty text`() {
        every { mockContext.startActivity(mockChooserIntent) } returns Unit

        val result = ShareUtils.shareText(mockContext, "")

        assertTrue("Should handle empty text", result)
    }

    @Test
    fun `shareText adds NEW_TASK flag to chooser intent`() {
        every { mockContext.startActivity(mockChooserIntent) } returns Unit

        ShareUtils.shareText(mockContext, "text")

        verify { mockChooserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }

    // === shareGroupCode Tests ===

    @Test
    fun `shareGroupCode uses localized strings from resources`() {
        val groupCode = "ABC123"
        val appName = "EnterComm"
        val shareMessage = "Join my EnterComm group! Enter code: ABC123"
        val shareSubject = "EnterComm Group Code"
        val chooserTitle = "Share group code"

        every { mockContext.getString(R.string.app_name) } returns appName
        every { mockContext.getString(R.string.group_code_share_message, appName, groupCode) } returns shareMessage
        every { mockContext.getString(R.string.group_code_share_subject, appName) } returns shareSubject
        every { mockContext.getString(R.string.group_code_share_chooser_title) } returns chooserTitle
        every { mockContext.startActivity(mockChooserIntent) } returns Unit

        val result = ShareUtils.shareGroupCode(mockContext, groupCode)

        assertTrue("Should return true on success", result)
        verify { mockContext.getString(R.string.app_name) }
        verify { mockContext.getString(R.string.group_code_share_message, appName, groupCode) }
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

        every { mockContext.getString(R.string.app_name) } returns appName
        every { mockContext.getString(R.string.group_code_share_message, appName, groupCode) } returns shareMessage
        every { mockContext.getString(R.string.group_code_share_subject, appName) } returns shareSubject
        every { mockContext.getString(R.string.group_code_share_chooser_title) } returns chooserTitle
        every { mockContext.startActivity(mockChooserIntent) } returns Unit

        val result = ShareUtils.shareGroupCode(mockContext, groupCode)

        assertTrue("Should handle special characters in group code", result)
    }
}
