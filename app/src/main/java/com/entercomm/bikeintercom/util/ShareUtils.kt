package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.Intent

/**
 * Share utility for sharing text via Android share sheet.
 * Provides a simple API for sharing content using Intent.ACTION_SEND.
 */
object ShareUtils {

    /**
     * Share text content via the Android share sheet.
     *
     * @param context Android context for starting the share activity
     * @param text The text content to share
     * @param subject Optional subject line for the share (used by email apps, etc.)
     * @param chooserTitle Optional title for the share chooser dialog
     * @return true if share intent was launched successfully, false otherwise
     */
    fun shareText(
        context: Context,
        text: String,
        subject: String? = null,
        chooserTitle: String? = null
    ): Boolean {
        return try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            }
            val shareIntent = Intent.createChooser(sendIntent, chooserTitle)
            shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(shareIntent)
            true
        } catch (e: Exception) {
            logE { "Failed to share text: ${e.message}" }
            false
        }
    }

    /**
     * Share a group code with a pre-formatted message for riders.
     *
     * @param context Android context for starting the share activity
     * @param groupCode The group code to share
     * @param appName The app name to include in the message
     * @return true if share intent was launched successfully, false otherwise
     */
    fun shareGroupCode(
        context: Context,
        groupCode: String,
        appName: String = "EnterComm"
    ): Boolean {
        val shareMessage = buildGroupCodeShareMessage(groupCode, appName)
        val subject = "$appName Group Code"
        return shareText(
            context = context,
            text = shareMessage,
            subject = subject,
            chooserTitle = "Share group code"
        )
    }

    /**
     * Build the share message for a group code.
     * Exposed for customization and testing.
     *
     * @param groupCode The group code
     * @param appName The app name
     * @return The formatted share message
     */
    fun buildGroupCodeShareMessage(groupCode: String, appName: String = "EnterComm"): String {
        return "Join my $appName group! Enter code: $groupCode"
    }
}
