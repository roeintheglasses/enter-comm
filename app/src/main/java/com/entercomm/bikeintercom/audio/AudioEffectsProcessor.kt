package com.entercomm.bikeintercom.audio

import android.media.AudioRecord
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Audio effects processor using Android's built-in AudioEffect classes.
 * Provides:
 * - Acoustic Echo Cancellation (AEC)
 * - Noise Suppression (NS)
 * - Automatic Gain Control (AGC)
 * - Software-based wind noise reduction for cycling
 */
class AudioEffectsProcessor {
    companion object {
        private const val TAG = "AudioEffectsProcessor"

        // Wind noise detection thresholds
        private const val WIND_NOISE_THRESHOLD = 0.15f
        private const val HIGH_PASS_CUTOFF = 300f  // Hz - filter out low-frequency wind noise
    }

    // Android hardware audio effects (API 16+)
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    // Effect availability flags
    var isAecAvailable = false
        private set
    var isNsAvailable = false
        private set
    var isAgcAvailable = false
        private set

    // Effect enabled states
    var isAecEnabled = false
        private set
    var isNsEnabled = false
        private set
    var isAgcEnabled = false
        private set

    // Software processing state
    private var softwareAgcGain = 1.0f
    private var windNoiseFilter: HighPassFilter? = null
    private var isWindFilterEnabled = true

    // Audio statistics
    private var lastRmsLevel = 0f
    private var windNoiseDetected = false

    /**
     * Initialize audio effects attached to an AudioRecord session.
     * Call this after AudioRecord is created but before recording starts.
     */
    fun initialize(audioSessionId: Int, sampleRate: Int = 48000): Boolean {
        Log.d(TAG, "Initializing audio effects for session: $audioSessionId")

        // Initialize hardware AEC
        isAecAvailable = AcousticEchoCanceler.isAvailable()
        if (isAecAvailable) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    isAecEnabled = true
                    Log.d(TAG, "AEC enabled successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AEC", e)
                isAecAvailable = false
            }
        } else {
            Log.w(TAG, "AEC not available on this device")
        }

        // Initialize hardware Noise Suppressor
        isNsAvailable = NoiseSuppressor.isAvailable()
        if (isNsAvailable) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    isNsEnabled = true
                    Log.d(TAG, "Noise Suppressor enabled successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create Noise Suppressor", e)
                isNsAvailable = false
            }
        } else {
            Log.w(TAG, "Noise Suppressor not available on this device")
        }

        // Initialize hardware AGC
        isAgcAvailable = AutomaticGainControl.isAvailable()
        if (isAgcAvailable) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    isAgcEnabled = true
                    Log.d(TAG, "AGC enabled successfully")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AGC", e)
                isAgcAvailable = false
            }
        } else {
            Log.w(TAG, "AGC not available on this device")
        }

        // Initialize software wind noise filter
        windNoiseFilter = HighPassFilter(sampleRate.toFloat(), HIGH_PASS_CUTOFF)
        Log.d(TAG, "Wind noise filter initialized at ${HIGH_PASS_CUTOFF}Hz")

        Log.d(TAG, "Audio effects initialized - AEC: $isAecEnabled, NS: $isNsEnabled, AGC: $isAgcEnabled")
        return isAecEnabled || isNsEnabled || isAgcEnabled
    }

    /**
     * Process audio samples with software-based effects.
     * Hardware effects are applied automatically by AudioRecord.
     *
     * @param samples Input PCM samples
     * @return Processed PCM samples
     */
    fun process(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples

        var processed = samples

        // Apply wind noise filter (high-pass filter for cycling)
        if (isWindFilterEnabled) {
            processed = applyWindNoiseFilter(processed)
        }

        // Apply software AGC if hardware AGC is not available
        if (!isAgcEnabled) {
            processed = applySoftwareAgc(processed)
        }

        // Update statistics
        lastRmsLevel = calculateRms(processed)

        return processed
    }

    /**
     * Enable/disable specific effects.
     */
    fun setAecEnabled(enabled: Boolean) {
        echoCanceler?.let {
            try {
                it.enabled = enabled
                isAecEnabled = enabled
                Log.d(TAG, "AEC ${if (enabled) "enabled" else "disabled"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set AEC state", e)
            }
        }
    }

    fun setNsEnabled(enabled: Boolean) {
        noiseSuppressor?.let {
            try {
                it.enabled = enabled
                isNsEnabled = enabled
                Log.d(TAG, "NS ${if (enabled) "enabled" else "disabled"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set NS state", e)
            }
        }
    }

    fun setAgcEnabled(enabled: Boolean) {
        gainControl?.let {
            try {
                it.enabled = enabled
                isAgcEnabled = enabled
                Log.d(TAG, "AGC ${if (enabled) "enabled" else "disabled"}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set AGC state", e)
            }
        }
    }

    fun setWindFilterEnabled(enabled: Boolean) {
        isWindFilterEnabled = enabled
        Log.d(TAG, "Wind filter ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Get current audio statistics.
     */
    fun getStats(): AudioEffectsStats {
        return AudioEffectsStats(
            rmsLevel = lastRmsLevel,
            softwareGain = softwareAgcGain,
            windNoiseDetected = windNoiseDetected,
            hardwareAecActive = isAecEnabled,
            hardwareNsActive = isNsEnabled,
            hardwareAgcActive = isAgcEnabled
        )
    }

    /**
     * Release all audio effects.
     */
    fun cleanup() {
        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }

        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        windNoiseFilter = null

        isAecEnabled = false
        isNsEnabled = false
        isAgcEnabled = false

        Log.d(TAG, "Audio effects cleaned up")
    }

    // Software AGC implementation
    private fun applySoftwareAgc(samples: ShortArray): ShortArray {
        val targetLevel = 0.3f  // Target RMS level
        val attackTime = 0.01f  // Fast attack for speech
        val releaseTime = 0.1f  // Slower release

        val rms = calculateRms(samples)
        if (rms < 0.001f) return samples  // Silence, don't adjust

        // Calculate desired gain
        val desiredGain = (targetLevel / rms).coerceIn(0.5f, 4.0f)

        // Smooth gain changes
        val alpha = if (desiredGain > softwareAgcGain) attackTime else releaseTime
        softwareAgcGain = softwareAgcGain + alpha * (desiredGain - softwareAgcGain)

        // Apply gain
        val output = ShortArray(samples.size)
        for (i in samples.indices) {
            val amplified = (samples[i] * softwareAgcGain).toInt()
            output[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    // Wind noise filter (high-pass filter)
    private fun applyWindNoiseFilter(samples: ShortArray): ShortArray {
        val filter = windNoiseFilter ?: return samples

        // Detect wind noise (characterized by high energy in low frequencies)
        val lowFreqEnergy = calculateLowFrequencyEnergy(samples)
        windNoiseDetected = lowFreqEnergy > WIND_NOISE_THRESHOLD

        // Always apply filter for cycling use case
        val output = ShortArray(samples.size)
        for (i in samples.indices) {
            val filtered = filter.process(samples[i].toFloat())
            output[i] = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return output
    }

    private fun calculateRms(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f

        var sum = 0.0
        for (sample in samples) {
            sum += (sample.toDouble() / Short.MAX_VALUE).let { it * it }
        }
        return sqrt(sum / samples.size).toFloat()
    }

    private fun calculateLowFrequencyEnergy(samples: ShortArray): Float {
        if (samples.size < 4) return 0f

        // Simple low-frequency energy estimation using moving average
        var totalEnergy = 0.0

        // Downsample by 4 to focus on low frequencies
        for (i in samples.indices step 4) {
            val sample = abs(samples[i].toDouble())
            totalEnergy += sample * sample
        }

        // Use difference between consecutive samples (approximates high-freq)
        var highFreqEnergy = 0.0
        for (i in 1 until samples.size) {
            val diff = abs((samples[i] - samples[i - 1]).toDouble())
            highFreqEnergy += diff * diff
        }

        if (totalEnergy < 1.0) return 0f

        // Low freq ratio
        return ((totalEnergy - highFreqEnergy / 4) / totalEnergy).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Simple IIR high-pass filter for wind noise reduction.
     */
    private class HighPassFilter(sampleRate: Float, cutoffFreq: Float) {
        private val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffFreq)
        private val dt = 1.0f / sampleRate
        private val alpha = rc / (rc + dt)

        private var previousInput = 0f
        private var previousOutput = 0f

        fun process(input: Float): Float {
            val output = alpha * (previousOutput + input - previousInput)
            previousInput = input
            previousOutput = output
            return output
        }

        fun reset() {
            previousInput = 0f
            previousOutput = 0f
        }
    }
}

/**
 * Audio effects statistics for monitoring.
 */
data class AudioEffectsStats(
    val rmsLevel: Float,
    val softwareGain: Float,
    val windNoiseDetected: Boolean,
    val hardwareAecActive: Boolean,
    val hardwareNsActive: Boolean,
    val hardwareAgcActive: Boolean
)
