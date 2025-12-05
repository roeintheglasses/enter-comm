package com.entercomm.bikeintercom.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Voice feedback manager using Android Text-to-Speech.
 * Provides spoken announcements for connection states, errors, and status changes.
 * Optimized for cycling use case with clear, concise messages.
 */
class VoiceFeedback(private val context: Context) {

    enum class Priority {
        LOW, // Can be skipped if queue is full
        NORMAL, // Standard announcement
        HIGH, // Important, plays soon
        IMMEDIATE, // Interrupts current speech
    }

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var volume: Float = 0.8f
    private var speechRate: Float = 1.1f // Slightly faster for cycling

    // Queue for pending announcements
    private val announcementQueue = ConcurrentLinkedQueue<Announcement>()
    private var currentUtteranceId: String? = null

    data class Announcement(
        val message: String,
        val priority: Priority,
        val id: String = UUID.randomUUID().toString(),
    )

    /**
     * Initialize the TTS engine.
     */
    fun initialize() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    logE { "TTS language not supported" }
                    isInitialized = false
                } else {
                    isInitialized = true
                    setupTtsListener()
                    tts?.setSpeechRate(speechRate)
                    logD { "TTS initialized successfully" }
                }
            } else {
                logE { "TTS initialization failed: $status" }
                isInitialized = false
            }
        }
    }

    private fun setupTtsListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
                currentUtteranceId = utteranceId
            }

            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
                currentUtteranceId = null
                processNextInQueue()
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
                currentUtteranceId = null
                processNextInQueue()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                logE { "TTS error: $errorCode for utterance: $utteranceId" }
                _isSpeaking.value = false
                currentUtteranceId = null
                processNextInQueue()
            }
        })
    }

    /**
     * Enable or disable voice feedback.
     */
    fun setEnabled(enabled: Boolean) {
        _isEnabled.value = enabled
        if (!enabled) {
            stop()
        }
        logD { "Voice feedback ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Set the speech volume (0.0 to 1.0).
     */
    fun setVolume(vol: Float) {
        volume = vol.coerceIn(0f, 1f)
    }

    /**
     * Set the speech rate (0.5 to 2.0, default 1.0).
     */
    fun setSpeechRate(rate: Float) {
        speechRate = rate.coerceIn(0.5f, 2.0f)
        tts?.setSpeechRate(speechRate)
    }

    /**
     * Announce a message with the given priority.
     */
    fun announce(message: String, priority: Priority = Priority.NORMAL) {
        if (!_isEnabled.value || !isInitialized) return

        val announcement = Announcement(message, priority)

        when (priority) {
            Priority.IMMEDIATE -> {
                // Stop current speech and speak immediately
                tts?.stop()
                announcementQueue.clear()
                speak(announcement)
            }
            Priority.HIGH -> {
                // Add to front of queue
                val currentQueue = announcementQueue.toList()
                announcementQueue.clear()
                announcementQueue.add(announcement)
                currentQueue.forEach { announcementQueue.add(it) }
                if (!_isSpeaking.value) {
                    processNextInQueue()
                }
            }
            Priority.NORMAL -> {
                announcementQueue.add(announcement)
                if (!_isSpeaking.value) {
                    processNextInQueue()
                }
            }
            Priority.LOW -> {
                // Only add if queue is small
                if (announcementQueue.size < 3) {
                    announcementQueue.add(announcement)
                    if (!_isSpeaking.value) {
                        processNextInQueue()
                    }
                }
            }
        }
    }

    // Pre-defined announcements for common events

    /**
     * Announce connection state change.
     */
    fun announceConnectionState(connected: Boolean, deviceCount: Int) {
        val message = if (connected) {
            when (deviceCount) {
                0 -> "Connected to mesh. No other riders."
                1 -> "Connected. One rider in mesh."
                else -> "Connected. $deviceCount riders in mesh."
            }
        } else {
            "Disconnected from mesh."
        }
        announce(message, Priority.HIGH)
    }

    /**
     * Announce when a rider joins.
     */
    fun announceRiderJoined(riderName: String?, totalCount: Int) {
        val name = riderName ?: "Rider"
        val message = "$name joined. Now $totalCount riders."
        announce(message, Priority.NORMAL)
    }

    /**
     * Announce when a rider leaves.
     */
    fun announceRiderLeft(riderName: String?, totalCount: Int) {
        val name = riderName ?: "Rider"
        val message = "$name left. Now $totalCount riders."
        announce(message, Priority.NORMAL)
    }

    /**
     * Announce recording state.
     */
    fun announceRecordingState(recording: Boolean) {
        val message = if (recording) "Transmitting." else "Transmission ended."
        announce(message, Priority.HIGH)
    }

    /**
     * Announce an error.
     */
    fun announceError(error: String) {
        announce("Error. $error", Priority.IMMEDIATE)
    }

    /**
     * Announce mesh network starting.
     */
    fun announceMeshStarting() {
        announce("Starting mesh network.", Priority.NORMAL)
    }

    /**
     * Announce mesh network stopped.
     */
    fun announceMeshStopped() {
        announce("Mesh network stopped.", Priority.HIGH)
    }

    /**
     * Announce signal quality.
     */
    fun announceSignalQuality(quality: SignalQuality) {
        val message = when (quality) {
            SignalQuality.EXCELLENT -> "Signal excellent."
            SignalQuality.GOOD -> "Signal good."
            SignalQuality.FAIR -> "Signal fair."
            SignalQuality.POOR -> "Signal poor. Move closer."
            SignalQuality.LOST -> "Signal lost."
        }
        announce(message, if (quality == SignalQuality.LOST) Priority.HIGH else Priority.LOW)
    }

    /**
     * Announce group join.
     */
    fun announceGroupJoined(groupName: String) {
        announce("Joined group $groupName.", Priority.HIGH)
    }

    /**
     * Announce group creation.
     */
    fun announceGroupCreated(groupName: String) {
        announce("Created group $groupName.", Priority.HIGH)
    }

    /**
     * Test the voice feedback with a sample message.
     */
    fun test() {
        val wasEnabled = _isEnabled.value
        _isEnabled.value = true
        announce("Voice feedback is working.", Priority.IMMEDIATE)
        if (!wasEnabled) {
            // Re-disable after test
            _isEnabled.value = wasEnabled
        }
    }

    /**
     * Stop current speech and clear queue.
     */
    fun stop() {
        tts?.stop()
        announcementQueue.clear()
        _isSpeaking.value = false
    }

    /**
     * Release TTS resources.
     */
    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        logD { "TTS shutdown" }
    }

    private fun speak(announcement: Announcement) {
        val params = android.os.Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume)
        }
        tts?.speak(announcement.message, TextToSpeech.QUEUE_FLUSH, params, announcement.id)
    }

    private fun processNextInQueue() {
        val next = announcementQueue.poll()
        if (next != null) {
            speak(next)
        }
    }

    enum class SignalQuality {
        EXCELLENT,
        GOOD,
        FAIR,
        POOR,
        LOST,
    }
}
