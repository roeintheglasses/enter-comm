package com.entercomm.bikeintercom.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.entercomm.bikeintercom.util.*
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
        // Wind noise detection thresholds
        private const val WIND_NOISE_THRESHOLD = 0.15f
        private const val HIGH_PASS_CUTOFF = 300f // Hz - filter out low-frequency wind noise
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
        logD { "Initializing audio effects for session: $audioSessionId" }

        // Initialize hardware AEC
        isAecAvailable = AcousticEchoCanceler.isAvailable()
        if (isAecAvailable) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                    isAecEnabled = true
                    logD { "AEC enabled successfully" }
                }
            } catch (e: Exception) {
                logE({ "Failed to create AEC" }, e)
                isAecAvailable = false
            }
        } else {
            logW { "AEC not available on this device" }
        }

        // Initialize hardware Noise Suppressor
        isNsAvailable = NoiseSuppressor.isAvailable()
        if (isNsAvailable) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                    isNsEnabled = true
                    logD { "Noise Suppressor enabled successfully" }
                }
            } catch (e: Exception) {
                logE({ "Failed to create Noise Suppressor" }, e)
                isNsAvailable = false
            }
        } else {
            logW { "Noise Suppressor not available on this device" }
        }

        // Initialize hardware AGC
        isAgcAvailable = AutomaticGainControl.isAvailable()
        if (isAgcAvailable) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId)?.apply {
                    enabled = true
                    isAgcEnabled = true
                    logD { "AGC enabled successfully" }
                }
            } catch (e: Exception) {
                logE({ "Failed to create AGC" }, e)
                isAgcAvailable = false
            }
        } else {
            logW { "AGC not available on this device" }
        }

        // Initialize software wind noise filter
        windNoiseFilter = HighPassFilter(sampleRate.toFloat(), HIGH_PASS_CUTOFF)
        logD { "Wind noise filter initialized at ${HIGH_PASS_CUTOFF}Hz" }

        logD { "Audio effects initialized - AEC: $isAecEnabled, NS: $isNsEnabled, AGC: $isAgcEnabled" }
        return isAecEnabled || isNsEnabled || isAgcEnabled
    }

    /**
     * Process audio samples with software-based effects.
     * Hardware effects are applied automatically by AudioRecord.
     *
     * Uses pooled buffers to avoid allocations on hot path.
     * Note: This method may return a different array than input when wind filter is enabled.
     *
     * @param samples Input PCM samples
     * @return Processed PCM samples (may be same array or pooled buffer)
     */
    fun process(samples: ShortArray): ShortArray {
        if (samples.isEmpty()) return samples

        var current = samples

        // Apply wind noise filter (high-pass filter for cycling)
        // Requires intermediate buffer since filter has state and can't process in-place
        if (isWindFilterEnabled) {
            val windOutput = AudioBufferPool.getEffectsBuffer(0)
            applyWindNoiseFilterInto(current, windOutput, samples.size)
            current = windOutput
        }

        // Apply software AGC if hardware AGC is not available
        // Can work in-place since it's a simple gain operation
        if (!isAgcEnabled) {
            applySoftwareAgcInPlace(current, samples.size)
        }

        // Update statistics
        lastRmsLevel = calculateRms(current, samples.size)

        return current
    }

    /**
     * Enable/disable specific effects.
     */
    fun setAecEnabled(enabled: Boolean) {
        echoCanceler?.let {
            try {
                it.enabled = enabled
                isAecEnabled = enabled
                logD { "AEC ${if (enabled) "enabled" else "disabled"}" }
            } catch (e: Exception) {
                logE({ "Failed to set AEC state" }, e)
            }
        }
    }

    fun setNsEnabled(enabled: Boolean) {
        noiseSuppressor?.let {
            try {
                it.enabled = enabled
                isNsEnabled = enabled
                logD { "NS ${if (enabled) "enabled" else "disabled"}" }
            } catch (e: Exception) {
                logE({ "Failed to set NS state" }, e)
            }
        }
    }

    fun setAgcEnabled(enabled: Boolean) {
        gainControl?.let {
            try {
                it.enabled = enabled
                isAgcEnabled = enabled
                logD { "AGC ${if (enabled) "enabled" else "disabled"}" }
            } catch (e: Exception) {
                logE({ "Failed to set AGC state" }, e)
            }
        }
    }

    fun setWindFilterEnabled(enabled: Boolean) {
        isWindFilterEnabled = enabled
        logD { "Wind filter ${if (enabled) "enabled" else "disabled"}" }
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
            hardwareAgcActive = isAgcEnabled,
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
            logE({ "Error releasing audio effects" }, e)
        }

        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
        windNoiseFilter = null

        isAecEnabled = false
        isNsEnabled = false
        isAgcEnabled = false

        logD { "Audio effects cleaned up" }
    }

    // Software AGC implementation - in-place version to avoid allocations
    private fun applySoftwareAgcInPlace(samples: ShortArray, length: Int) {
        val targetLevel = 0.3f // Target RMS level
        val attackTime = 0.01f // Fast attack for speech
        val releaseTime = 0.1f // Slower release

        val rms = calculateRms(samples, length)
        if (rms < 0.001f) return // Silence, don't adjust

        // Calculate desired gain
        val desiredGain = (targetLevel / rms).coerceIn(0.5f, 4.0f)

        // Smooth gain changes
        val alpha = if (desiredGain > softwareAgcGain) attackTime else releaseTime
        softwareAgcGain = softwareAgcGain + alpha * (desiredGain - softwareAgcGain)

        // Apply gain in-place
        for (i in 0 until length) {
            val amplified = (samples[i] * softwareAgcGain).toInt()
            samples[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    // Wind noise filter - writes to output buffer to avoid allocations
    private fun applyWindNoiseFilterInto(input: ShortArray, output: ShortArray, length: Int) {
        val filter = windNoiseFilter ?: run {
            // No filter, copy input to output
            input.copyInto(output, 0, 0, length)
            return
        }

        // Detect wind noise (characterized by high energy in low frequencies)
        val lowFreqEnergy = calculateLowFrequencyEnergy(input, length)
        windNoiseDetected = lowFreqEnergy > WIND_NOISE_THRESHOLD

        // Apply filter, writing to output buffer
        for (i in 0 until length) {
            val filtered = filter.process(input[i].toFloat())
            output[i] = filtered.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun calculateRms(samples: ShortArray, length: Int = samples.size): Float {
        if (length == 0) return 0f

        var sum = 0.0
        for (i in 0 until length) {
            sum += (samples[i].toDouble() / Short.MAX_VALUE).let { it * it }
        }
        return sqrt(sum / length).toFloat()
    }

    private fun calculateLowFrequencyEnergy(samples: ShortArray, length: Int = samples.size): Float {
        if (length < 4) return 0f

        // Simple low-frequency energy estimation using moving average
        var totalEnergy = 0.0

        // Downsample by 4 to focus on low frequencies
        for (i in 0 until length step 4) {
            val sample = abs(samples[i].toDouble())
            totalEnergy += sample * sample
        }

        // Use difference between consecutive samples (approximates high-freq)
        var highFreqEnergy = 0.0
        for (i in 1 until length) {
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
    val hardwareAgcActive: Boolean,
)
