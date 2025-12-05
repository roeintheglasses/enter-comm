package com.entercomm.bikeintercom.util

import android.util.Log

/**
 * Centralized logging utility.
 * - Only logs in debug builds
 * - Provides consistent tag formatting
 * - Avoids string concatenation when logging is disabled
 * - Supports test mode (no actual logging)
 */
object Logger {
    private const val APP_TAG = "EnterComm"

    // Controlled by BuildConfig - check once at init
    var isDebugEnabled: Boolean = true

    // Test mode - disables actual Android logging for unit tests
    var isTestMode: Boolean = false

    fun d(tag: String, message: () -> String) {
        if (isDebugEnabled && !isTestMode) {
            Log.d("$APP_TAG:$tag", message())
        }
    }

    fun i(tag: String, message: () -> String) {
        if (isDebugEnabled && !isTestMode) {
            Log.i("$APP_TAG:$tag", message())
        }
    }

    fun w(tag: String, message: () -> String) {
        if (isDebugEnabled && !isTestMode) {
            Log.w("$APP_TAG:$tag", message())
        }
    }

    fun w(tag: String, message: () -> String, throwable: Throwable) {
        if (isDebugEnabled && !isTestMode) {
            Log.w("$APP_TAG:$tag", message(), throwable)
        }
    }

    fun e(tag: String, message: () -> String) {
        // Always log errors, even in release builds (unless in test mode)
        if (!isTestMode) {
            Log.e("$APP_TAG:$tag", message())
        }
    }

    fun e(tag: String, message: () -> String, throwable: Throwable) {
        // Always log errors, even in release builds (unless in test mode)
        if (!isTestMode) {
            Log.e("$APP_TAG:$tag", message(), throwable)
        }
    }
}

/**
 * Extension functions for easier logging from any class.
 * Call syntax: logD { "message" } or logE({ "message" }, exception)
 */
fun Any.logD(message: () -> String) = Logger.d(this::class.java.simpleName, message)
fun Any.logI(message: () -> String) = Logger.i(this::class.java.simpleName, message)
fun Any.logW(message: () -> String) = Logger.w(this::class.java.simpleName, message)
fun Any.logW(message: () -> String, throwable: Throwable) = Logger.w(this::class.java.simpleName, message, throwable)
fun Any.logE(message: () -> String) = Logger.e(this::class.java.simpleName, message)
fun Any.logE(message: () -> String, throwable: Throwable) = Logger.e(this::class.java.simpleName, message, throwable)
