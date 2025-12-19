package com.entercomm.bikeintercom.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * Clipboard utility for copying text on Android.
 * Provides a simple API for clipboard operations with proper SDK version handling.
 */
object ClipboardUtils {

    /**
     * Copy text to the system clipboard.
     *
     * @param context Android context for accessing system services
     * @param text The text content to copy
     * @param label A label describing the clipboard content (visible to user in some Android versions)
     * @return true if copy was successful, false otherwise
     */
    fun copyToClipboard(context: Context, text: String, label: String): Boolean {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText(label, text)
            clipboardManager.setPrimaryClip(clipData)
            true
        } catch (e: Exception) {
            logE { "Failed to copy to clipboard: ${e.message}" }
            false
        }
    }

    /**
     * Check if there is text content available in the clipboard.
     *
     * @param context Android context for accessing system services
     * @return true if clipboard contains text, false otherwise
     */
    fun hasClipboardText(context: Context): Boolean {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboardManager.hasPrimaryClip() &&
                clipboardManager.primaryClipDescription?.hasMimeType("text/plain") == true
        } catch (e: Exception) {
            logE { "Failed to check clipboard: ${e.message}" }
            false
        }
    }

    /**
     * Get text content from the clipboard.
     *
     * @param context Android context for accessing system services
     * @return The text content if available, null otherwise
     */
    fun getClipboardText(context: Context): String? {
        return try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboardManager.hasPrimaryClip()) {
                clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            logE { "Failed to get clipboard text: ${e.message}" }
            null
        }
    }
}
