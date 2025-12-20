package com.entercomm.bikeintercom.util

import android.content.Context
import android.content.Intent
import com.entercomm.bikeintercom.R

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
    fun shareText(context: Context, text: String, subject: String? = null, chooserTitle: String? = null): Boolean {
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
     * Uses localized string resources for share subject, message, and chooser title.
     *
     * @param context Android context for starting the share activity
     * @param groupCode The group code to share
     * @return true if share intent was launched successfully, false otherwise
     */
    fun shareGroupCode(context: Context, groupCode: String): Boolean {
        return try {
            val appName = context.getString(R.string.app_name)
            val shareMessage = context.getString(R.string.group_code_share_message, appName, groupCode)
            val subject = context.getString(R.string.group_code_share_subject, appName)
            val chooserTitle = context.getString(R.string.group_code_share_chooser_title)
            shareText(
                context = context,
                text = shareMessage,
                subject = subject,
                chooserTitle = chooserTitle,
            )
        } catch (e: Exception) {
            logE { "Failed to share group code: ${e.message}" }
            false
        }
    }
}
