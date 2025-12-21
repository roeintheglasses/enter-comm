package com.entercomm.bikeintercom.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.entercomm.bikeintercom.util.*
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WebRTC-based audio processor with Opus codec and advanced audio processing.
 *
 * Provides superior audio quality compared to ADPCM with built-in:
 * - Acoustic Echo Cancellation (AEC)
 * - Noise Suppression (NS) - multi-band, superior to Android native
 * - Automatic Gain Control (AGC)
 * - High-pass filter for wind noise reduction
 * - Opus codec at 32kbps with FEC for packet loss resilience
 *
 * Benefits:
 * - 10-20x compression ratio vs raw PCM (32kbps vs 768kbps at 48kHz)
 * - Hardware-independent audio processing (consistent across all devices)
 * - Built-in transient suppressor for sudden loud noises
 * - End-to-end latency target: <150ms
 *
 * Note: Must be initialized on the main thread per WebRTC requirements.
 */
@Suppress("TooManyFunctions")
class WebRTCAudioProcessor(
    private val sampleRate: Int = SAMPLE_RATE,
    private val bitrate: Int = BITRATE,
) {
    // WebRTC components
    private var audioDeviceModule: AudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null

    // Audio processing configuration
    private var mediaConstraints: MediaConstraints? = null

    // Effect enabled states (matching AudioEffectsProcessor pattern)
    var isAecEnabled = true
        private set
    var isNsEnabled = true
        private set
    var isAgcEnabled = true
        private set
    var isHighPassFilterEnabled = true
        private set

    // Thread safety
    private val initLock = Any()

    @Volatile
    private var isInitialized = false

    // Context reference for WebRTC initialization
    private var applicationContext: Context? = null

    // ==================== Opus Codec State ====================

    // Opus encoder/decoder handles (native pointers, 0 when not initialized)
    private var opusEncoderHandle: Long = 0
    private var opusDecoderHandle: Long = 0

    // Thread safety for encode/decode operations (matching AdpcmCodec pattern)
    private val encodeLock = Any()
    private val decodeLock = Any()

    // Codec initialization state
    @Volatile
    private var isCodecInitialized = false

    // FEC (Forward Error Correction) enabled for packet loss resilience
    private var fecEnabled = true

    // Decode buffer for PLC (Packet Loss Concealment)
    private var lastDecodedSamples: ShortArray? = null

    /**
     * Initialize the WebRTC audio processor.
     *
     * IMPORTANT: Must be called on the main thread (Android UI thread).
     * If called from a background thread, the initialization will be posted
     * to the main thread and this method will block until completion.
     *
     * @param context Application context (will use applicationContext internally)
     * @return true if initialization succeeded, false otherwise
     */
    @Suppress("TooGenericExceptionCaught")
    fun initialize(context: Context): Boolean {
        return synchronized(initLock) {
            if (isInitialized) {
                logD { "WebRTC audio processor already initialized" }
                return@synchronized true
            }

            try {
                applicationContext = context.applicationContext

                // Ensure initialization happens on main thread
                if (Looper.myLooper() != Looper.getMainLooper()) {
                    logW { "WebRTC initialization called from background thread, posting to main thread" }
                    var initResult = false
                    val latch = java.util.concurrent.CountDownLatch(1)

                    Handler(Looper.getMainLooper()).post {
                        try {
                            initResult = initializeInternal()
                        } finally {
                            latch.countDown()
                        }
                    }

                    latch.await()
                    return@synchronized initResult
                }

                initializeInternal()
            } catch (e: RuntimeException) {
                logE({ "Failed to initialize WebRTC audio processor" }, e)
                false
            } catch (e: InterruptedException) {
                logE({ "WebRTC initialization interrupted" }, e)
                Thread.currentThread().interrupt()
                false
            }
        }
    }

    /**
     * Internal initialization - must be called on main thread.
     * @return true if initialization succeeded, false otherwise
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun initializeInternal(): Boolean {
        val context = applicationContext ?: run {
            logE { "Application context is null" }
            return false
        }

        return try {
            // Initialize PeerConnectionFactory (singleton)
            initializePeerConnectionFactory(context)

            // Create audio device module with hardware AEC/NS if available
            audioDeviceModule = createAudioDeviceModule(context)

            // Create media constraints for audio processing
            mediaConstraints = createMediaConstraints()

            // Create audio source with constraints
            val factory = peerConnectionFactory ?: run {
                logE { "PeerConnectionFactory is null" }
                return false
            }

            audioSource = factory.createAudioSource(mediaConstraints)
            if (audioSource == null) {
                logE { "Failed to create audio source" }
                return false
            }

            // Create audio track
            audioTrack = factory.createAudioTrack("webrtc-audio", audioSource)
            if (audioTrack == null) {
                logE { "Failed to create audio track" }
                return false
            }

            // Enable the audio track
            audioTrack?.setEnabled(true)

            // Initialize Opus codec
            initializeOpusCodec()

            isInitialized = true
            logD {
                "WebRTC audio processor initialized: ${sampleRate}Hz, ${bitrate}bps, " +
                    "AEC: $isAecEnabled, NS: $isNsEnabled, AGC: $isAgcEnabled, HP: $isHighPassFilterEnabled, " +
                    "Opus: $isCodecInitialized, FEC: $fecEnabled"
            }
            true
        } catch (e: RuntimeException) {
            logE({ "WebRTC initialization failed" }, e)
            cleanup()
            false
        }
    }

    /**
     * Initialize the Opus codec for encoding/decoding.
     *
     * Uses native JNI calls to libopus bundled with WebRTC SDK.
     * Configured for voice communication at 32kbps with FEC enabled.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun initializeOpusCodec() {
        synchronized(encodeLock) {
            synchronized(decodeLock) {
                try {
                    // Create Opus encoder
                    opusEncoderHandle = nativeCreateEncoder(
                        sampleRate,
                        CHANNELS,
                        OPUS_APPLICATION_VOIP,
                        bitrate,
                        if (fecEnabled) 1 else 0,
                    )

                    if (opusEncoderHandle == 0L) {
                        logW { "Native Opus encoder creation failed, using software fallback" }
                    }

                    // Create Opus decoder
                    opusDecoderHandle = nativeCreateDecoder(sampleRate, CHANNELS)

                    if (opusDecoderHandle == 0L) {
                        logW { "Native Opus decoder creation failed, using software fallback" }
                    }

                    isCodecInitialized = opusEncoderHandle != 0L || opusDecoderHandle != 0L
                    lastDecodedSamples = ShortArray(FRAME_SIZE)

                    logD { "Opus codec initialized: encoder=${opusEncoderHandle != 0L}, decoder=${opusDecoderHandle != 0L}" }
                } catch (e: UnsatisfiedLinkError) {
                    logW({ "Native Opus library not available, using software fallback" }, e)
                    isCodecInitialized = true // Use software fallback
                    lastDecodedSamples = ShortArray(FRAME_SIZE)
                } catch (e: RuntimeException) {
                    logE({ "Failed to initialize Opus codec" }, e)
                    isCodecInitialized = false
                }
            }
        }
    }

    /**
     * Initialize PeerConnectionFactory singleton.
     * Must be called on the main thread.
     */
    private fun initializePeerConnectionFactory(context: Context) {
        synchronized(factoryLock) {
            if (peerConnectionFactory != null) {
                logD { "PeerConnectionFactory already initialized" }
                return
            }

            // Initialize WebRTC
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions()

            PeerConnectionFactory.initialize(initOptions)

            // Create audio device module
            val adm = audioDeviceModule ?: createAudioDeviceModule(context)
            audioDeviceModule = adm

            // Build factory with audio device module (no video components)
            val options = PeerConnectionFactory.Options()
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()

            logD { "PeerConnectionFactory initialized" }
        }
    }

    /**
     * Create audio device module with hardware AEC/NS if available.
     */
    private fun createAudioDeviceModule(context: Context): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    logE { "WebRTC AudioRecord init error: $errorMessage" }
                }

                override fun onWebRtcAudioRecordStartError(errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode, errorMessage: String) {
                    logE { "WebRTC AudioRecord start error: $errorCode - $errorMessage" }
                }

                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    logE { "WebRTC AudioRecord error: $errorMessage" }
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    logE { "WebRTC AudioTrack init error: $errorMessage" }
                }

                override fun onWebRtcAudioTrackStartError(errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode, errorMessage: String) {
                    logE { "WebRTC AudioTrack start error: $errorCode - $errorMessage" }
                }

                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    logE { "WebRTC AudioTrack error: $errorMessage" }
                }
            })
            .setAudioRecordStateCallback(object : JavaAudioDeviceModule.AudioRecordStateCallback {
                override fun onWebRtcAudioRecordStart() {
                    logD { "WebRTC AudioRecord started" }
                }

                override fun onWebRtcAudioRecordStop() {
                    logD { "WebRTC AudioRecord stopped" }
                }
            })
            .setAudioTrackStateCallback(object : JavaAudioDeviceModule.AudioTrackStateCallback {
                override fun onWebRtcAudioTrackStart() {
                    logD { "WebRTC AudioTrack started" }
                }

                override fun onWebRtcAudioTrackStop() {
                    logD { "WebRTC AudioTrack stopped" }
                }
            })
            .createAudioDeviceModule()
    }

    /**
     * Create media constraints for audio processing.
     *
     * Configures WebRTC's audio processing features via MediaConstraints:
     * - googEchoCancellation: Multi-band AEC, superior to Android native
     * - googNoiseSuppression: Multi-band NS, handles wind and engine noise
     * - googAutoGainControl: Adaptive gain, maintains consistent voice levels
     * - googHighpassFilter: Removes low-frequency noise (replaces custom 300Hz filter)
     *
     * Note: These constraints are applied at audio source creation time.
     * Changes require recreating the audio source via updateAudioProcessing().
     */
    private fun createMediaConstraints(): MediaConstraints {
        logD { "Creating MediaConstraints - AEC: $isAecEnabled, NS: $isNsEnabled, AGC: $isAgcEnabled, HP: $isHighPassFilterEnabled" }

        return MediaConstraints().apply {
            // Echo cancellation - prevents feedback loops when riders are in proximity
            mandatory.add(MediaConstraints.KeyValuePair(ECHO_CANCELLATION, isAecEnabled.toString()))

            // Noise suppression - multi-band, handles wind noise at 60+ km/h and engine sounds
            mandatory.add(MediaConstraints.KeyValuePair(NOISE_SUPPRESSION, isNsEnabled.toString()))

            // Automatic gain control - maintains consistent voice levels despite varying microphone distances
            mandatory.add(MediaConstraints.KeyValuePair(AUTO_GAIN_CONTROL, isAgcEnabled.toString()))

            // High-pass filter - removes low-frequency rumble (wind, engine vibration)
            mandatory.add(MediaConstraints.KeyValuePair(HIGH_PASS_FILTER, isHighPassFilterEnabled.toString()))
        }
    }

    /**
     * Update audio processing settings and recreate audio source if needed.
     *
     * WebRTC MediaConstraints cannot be modified after audio source creation,
     * so this method recreates the audio source with new constraints.
     *
     * @param aecEnabled Enable/disable acoustic echo cancellation
     * @param nsEnabled Enable/disable noise suppression
     * @param agcEnabled Enable/disable automatic gain control
     * @param highPassEnabled Enable/disable high-pass filter
     * @return true if update succeeded, false otherwise
     */
    @Suppress("TooGenericExceptionCaught")
    fun updateAudioProcessing(aecEnabled: Boolean = isAecEnabled, nsEnabled: Boolean = isNsEnabled, agcEnabled: Boolean = isAgcEnabled, highPassEnabled: Boolean = isHighPassFilterEnabled): Boolean {
        return synchronized(initLock) {
            if (!isInitialized) {
                logW { "Cannot update audio processing - not initialized" }
                return@synchronized false
            }

            // Check if any settings changed
            val hasChanges = aecEnabled != isAecEnabled ||
                nsEnabled != isNsEnabled ||
                agcEnabled != isAgcEnabled ||
                highPassEnabled != isHighPassFilterEnabled

            if (!hasChanges) {
                logD { "No audio processing changes needed" }
                return@synchronized true
            }

            // Update state
            isAecEnabled = aecEnabled
            isNsEnabled = nsEnabled
            isAgcEnabled = agcEnabled
            isHighPassFilterEnabled = highPassEnabled

            logD {
                "Updating audio processing - AEC: $isAecEnabled, NS: $isNsEnabled, " +
                    "AGC: $isAgcEnabled, HP: $isHighPassFilterEnabled"
            }

            try {
                val factory = peerConnectionFactory ?: run {
                    logE { "PeerConnectionFactory is null" }
                    return@synchronized false
                }

                // Dispose old audio track and source
                audioTrack?.setEnabled(false)
                audioTrack?.dispose()
                audioSource?.dispose()

                // Create new constraints with updated settings
                mediaConstraints = createMediaConstraints()

                // Create new audio source with updated constraints
                audioSource = factory.createAudioSource(mediaConstraints)
                if (audioSource == null) {
                    logE { "Failed to create audio source with updated constraints" }
                    return@synchronized false
                }

                // Create new audio track
                audioTrack = factory.createAudioTrack("webrtc-audio", audioSource)
                if (audioTrack == null) {
                    logE { "Failed to create audio track with updated constraints" }
                    return@synchronized false
                }

                audioTrack?.setEnabled(true)

                logD { "Audio processing updated successfully" }
                true
            } catch (e: RuntimeException) {
                logE({ "Failed to update audio processing" }, e)
                false
            }
        }
    }

    /**
     * Get a summary of current audio processing configuration.
     * Useful for debugging and logging.
     */
    fun getAudioProcessingConfig(): AudioProcessingConfig {
        return AudioProcessingConfig(
            aecEnabled = isAecEnabled,
            nsEnabled = isNsEnabled,
            agcEnabled = isAgcEnabled,
            highPassFilterEnabled = isHighPassFilterEnabled,
            hardwareAecAvailable = true, // WebRTC handles this internally
            hardwareNsAvailable = true, // WebRTC handles this internally
            usingWebRtcProcessing = isInitialized,
        )
    }

    /**
     * Check if WebRTC audio processor is initialized and ready.
     */
    fun isReady(): Boolean = isInitialized

    /**
     * Get the WebRTC AudioTrack for integration with audio pipeline.
     */
    fun getAudioTrack(): AudioTrack? = audioTrack

    /**
     * Get the WebRTC AudioSource for integration with audio pipeline.
     */
    fun getAudioSource(): AudioSource? = audioSource

    /**
     * Get the PeerConnectionFactory for advanced use cases.
     */
    fun getPeerConnectionFactory(): PeerConnectionFactory? = peerConnectionFactory

    /**
     * Enable/disable echo cancellation.
     * Automatically recreates audio source with updated constraints.
     *
     * @param enabled Whether to enable AEC
     * @return true if the change was applied successfully
     */
    fun setAecEnabled(enabled: Boolean): Boolean {
        if (enabled == isAecEnabled) {
            logD { "AEC already ${if (enabled) "enabled" else "disabled"}" }
            return true
        }

        return if (isInitialized) {
            updateAudioProcessing(aecEnabled = enabled)
        } else {
            isAecEnabled = enabled
            logD { "AEC ${if (enabled) "enabled" else "disabled"} (will apply on init)" }
            true
        }
    }

    /**
     * Enable/disable noise suppression.
     * Automatically recreates audio source with updated constraints.
     *
     * @param enabled Whether to enable noise suppression
     * @return true if the change was applied successfully
     */
    fun setNsEnabled(enabled: Boolean): Boolean {
        if (enabled == isNsEnabled) {
            logD { "NS already ${if (enabled) "enabled" else "disabled"}" }
            return true
        }

        return if (isInitialized) {
            updateAudioProcessing(nsEnabled = enabled)
        } else {
            isNsEnabled = enabled
            logD { "NS ${if (enabled) "enabled" else "disabled"} (will apply on init)" }
            true
        }
    }

    /**
     * Enable/disable automatic gain control.
     * Automatically recreates audio source with updated constraints.
     *
     * @param enabled Whether to enable AGC
     * @return true if the change was applied successfully
     */
    fun setAgcEnabled(enabled: Boolean): Boolean {
        if (enabled == isAgcEnabled) {
            logD { "AGC already ${if (enabled) "enabled" else "disabled"}" }
            return true
        }

        return if (isInitialized) {
            updateAudioProcessing(agcEnabled = enabled)
        } else {
            isAgcEnabled = enabled
            logD { "AGC ${if (enabled) "enabled" else "disabled"} (will apply on init)" }
            true
        }
    }

    /**
     * Enable/disable high-pass filter.
     * Automatically recreates audio source with updated constraints.
     *
     * @param enabled Whether to enable high-pass filter
     * @return true if the change was applied successfully
     */
    fun setHighPassFilterEnabled(enabled: Boolean): Boolean {
        if (enabled == isHighPassFilterEnabled) {
            logD { "High-pass filter already ${if (enabled) "enabled" else "disabled"}" }
            return true
        }

        return if (isInitialized) {
            updateAudioProcessing(highPassEnabled = enabled)
        } else {
            isHighPassFilterEnabled = enabled
            logD { "High-pass filter ${if (enabled) "enabled" else "disabled"} (will apply on init)" }
            true
        }
    }

    /**
     * Set microphone mute state.
     */
    fun setMicrophoneMute(muted: Boolean) {
        (audioDeviceModule as? JavaAudioDeviceModule)?.setMicrophoneMute(muted)
        logD { "Microphone ${if (muted) "muted" else "unmuted"}" }
    }

    /**
     * Set speaker mute state.
     */
    fun setSpeakerMute(muted: Boolean) {
        (audioDeviceModule as? JavaAudioDeviceModule)?.setSpeakerMute(muted)
        logD { "Speaker ${if (muted) "muted" else "unmuted"}" }
    }

    /**
     * Get current audio processing statistics.
     */
    fun getStats(): WebRTCAudioStats {
        return WebRTCAudioStats(
            isInitialized = isInitialized,
            aecEnabled = isAecEnabled,
            nsEnabled = isNsEnabled,
            agcEnabled = isAgcEnabled,
            highPassFilterEnabled = isHighPassFilterEnabled,
            sampleRate = sampleRate,
            bitrate = bitrate,
        )
    }

    // ==================== Opus Codec Encode/Decode Methods ====================

    /**
     * Encode PCM audio samples to Opus format.
     *
     * @param pcmData PCM samples (16-bit signed, mono, 48kHz)
     * @return Opus-encoded data with header, or null on failure
     */
    @Suppress("TooGenericExceptionCaught")
    fun encode(pcmData: ShortArray): ByteArray? {
        if (!isInitialized || !isCodecInitialized || pcmData.isEmpty()) {
            return null
        }

        return synchronized(encodeLock) {
            try {
                // Calculate max encoded size (Opus worst case: frame_size * 2 + header)
                val maxEncodedSize = OPUS_HEADER_SIZE + (pcmData.size * 2)
                val output = ByteArray(maxEncodedSize)

                val encodedSize = encodeInto(pcmData, output)
                if (encodedSize > 0) {
                    // Return trimmed array with actual size
                    output.copyOf(encodedSize)
                } else {
                    null
                }
            } catch (e: Exception) {
                logE({ "Opus encoding failed" }, e)
                null
            }
        }
    }

    /**
     * Encode PCM audio samples to Opus format into a pre-allocated buffer.
     * Zero-copy variant for hot path to eliminate per-frame allocations.
     *
     * @param pcmData PCM samples (16-bit signed, mono, 48kHz)
     * @param output Pre-allocated output buffer (must be at least [OPUS_MAX_PACKET_SIZE] bytes)
     * @return Number of bytes written, or -1 on failure
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    fun encodeInto(pcmData: ShortArray, output: ByteArray): Int {
        if (!isInitialized || !isCodecInitialized || pcmData.isEmpty()) {
            return -1
        }

        return synchronized(encodeLock) {
            try {
                // Ensure output buffer is large enough
                if (output.size < OPUS_HEADER_SIZE) {
                    logE { "Output buffer too small: ${output.size} < $OPUS_HEADER_SIZE" }
                    return@synchronized -1
                }

                val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)

                // Try native Opus encoding first
                if (opusEncoderHandle != 0L) {
                    val encodedLength = nativeEncode(
                        opusEncoderHandle,
                        pcmData,
                        pcmData.size,
                        output,
                        OPUS_HEADER_SIZE,
                        output.size - OPUS_HEADER_SIZE,
                    )

                    if (encodedLength > 0) {
                        // Write Opus header
                        buffer.putShort(pcmData.size.toShort()) // Original sample count
                        buffer.putShort(encodedLength.toShort()) // Encoded data length
                        buffer.put(OPUS_CODEC_ID) // Codec identifier
                        buffer.put(if (fecEnabled) 1 else 0) // FEC flag
                        buffer.putShort(0) // Reserved

                        return@synchronized OPUS_HEADER_SIZE + encodedLength
                    }
                }

                // Software fallback: Simple mu-law-like compression
                // This provides ~2x compression as a fallback when native Opus unavailable
                val compressedSize = softwareEncode(pcmData, output, OPUS_HEADER_SIZE)
                if (compressedSize > 0) {
                    buffer.putShort(pcmData.size.toShort())
                    buffer.putShort(compressedSize.toShort())
                    buffer.put(FALLBACK_CODEC_ID)
                    buffer.put(0)
                    buffer.putShort(0)

                    OPUS_HEADER_SIZE + compressedSize
                } else {
                    -1
                }
            } catch (e: UnsatisfiedLinkError) {
                logW { "Native Opus encode not available, using software fallback" }
                softwareEncodeFallback(pcmData, output)
            } catch (e: Exception) {
                logE({ "Opus encoding failed" }, e)
                -1
            }
        }
    }

    /**
     * Decode Opus data back to PCM samples.
     *
     * @param opusData Opus-encoded data with header
     * @return Decoded PCM samples (16-bit signed), or empty array on failure
     */
    @Suppress("TooGenericExceptionCaught")
    fun decode(opusData: ByteArray): ShortArray {
        if (!isInitialized || !isCodecInitialized || opusData.size < OPUS_HEADER_SIZE) {
            return ShortArray(0)
        }

        return synchronized(decodeLock) {
            try {
                // Parse header to determine output size
                val buffer = ByteBuffer.wrap(opusData).order(ByteOrder.LITTLE_ENDIAN)
                val sampleCount = buffer.short.toInt() and 0xFFFF

                if (sampleCount <= 0 || sampleCount > MAX_FRAME_SIZE) {
                    logE { "Invalid sample count in Opus header: $sampleCount" }
                    return@synchronized ShortArray(0)
                }

                val output = ShortArray(sampleCount)
                val decodedCount = decodeInto(opusData, output)

                if (decodedCount > 0) {
                    // Store for PLC
                    lastDecodedSamples = output.copyOf()
                    output
                } else {
                    ShortArray(0)
                }
            } catch (e: Exception) {
                logE({ "Opus decoding failed" }, e)
                ShortArray(0)
            }
        }
    }

    /**
     * Decode Opus data into a pre-allocated buffer.
     * Zero-copy variant for hot path to eliminate per-frame allocations.
     *
     * @param opusData Opus-encoded data with header
     * @param output Pre-allocated output buffer (must be at least sampleCount samples)
     * @return Number of samples written, or -1 on failure
     */
    @Suppress("TooGenericExceptionCaught", "MagicNumber")
    fun decodeInto(opusData: ByteArray, output: ShortArray): Int {
        if (!isInitialized || !isCodecInitialized || opusData.size < OPUS_HEADER_SIZE) {
            return -1
        }

        return synchronized(decodeLock) {
            try {
                val buffer = ByteBuffer.wrap(opusData).order(ByteOrder.LITTLE_ENDIAN)

                // Parse Opus header
                val sampleCount = buffer.short.toInt() and 0xFFFF
                val encodedLength = buffer.short.toInt() and 0xFFFF
                val codecId = buffer.get()
                val fecFlag = buffer.get()
                buffer.getShort() // Skip reserved

                if (sampleCount <= 0 || sampleCount > MAX_FRAME_SIZE) {
                    logE { "Invalid sample count: $sampleCount" }
                    return@synchronized -1
                }

                if (output.size < sampleCount) {
                    logE { "Output buffer too small: ${output.size} < $sampleCount" }
                    return@synchronized -1
                }

                if (opusData.size < OPUS_HEADER_SIZE + encodedLength) {
                    logE { "Opus data truncated: ${opusData.size} < ${OPUS_HEADER_SIZE + encodedLength}" }
                    return@synchronized -1
                }

                // Decode based on codec ID
                val decodedSamples = when (codecId) {
                    OPUS_CODEC_ID -> {
                        if (opusDecoderHandle != 0L) {
                            nativeDecode(
                                opusDecoderHandle,
                                opusData,
                                OPUS_HEADER_SIZE,
                                encodedLength,
                                output,
                                sampleCount,
                                fecFlag.toInt(),
                            )
                        } else {
                            // Fallback to software decode
                            softwareDecode(opusData, OPUS_HEADER_SIZE, encodedLength, output, sampleCount)
                        }
                    }
                    FALLBACK_CODEC_ID -> {
                        softwareDecode(opusData, OPUS_HEADER_SIZE, encodedLength, output, sampleCount)
                    }
                    else -> {
                        logE { "Unknown codec ID: $codecId" }
                        -1
                    }
                }

                if (decodedSamples > 0) {
                    // Update PLC buffer
                    if (lastDecodedSamples == null || lastDecodedSamples!!.size < decodedSamples) {
                        lastDecodedSamples = ShortArray(decodedSamples)
                    }
                    System.arraycopy(output, 0, lastDecodedSamples!!, 0, decodedSamples)
                }

                decodedSamples
            } catch (e: UnsatisfiedLinkError) {
                logW { "Native Opus decode not available" }
                -1
            } catch (e: Exception) {
                logE({ "Opus decoding failed" }, e)
                -1
            }
        }
    }

    /**
     * Decode with packet loss concealment (PLC).
     * Generates comfort noise or repeats last frame when packets are lost.
     */
    fun decodePLC(): ShortArray {
        return synchronized(decodeLock) {
            // If we have last decoded samples, fade them out
            val lastSamples = lastDecodedSamples
            if (lastSamples != null && lastSamples.isNotEmpty()) {
                val output = ShortArray(lastSamples.size)
                for (i in lastSamples.indices) {
                    // Gradual fade to zero (95% decay per frame)
                    @Suppress("MagicNumber")
                    val fadedSample = (lastSamples[i] * 0.95).toInt().toShort()
                    output[i] = fadedSample
                    lastSamples[i] = fadedSample
                }
                output
            } else {
                // Generate silence
                ShortArray(FRAME_SIZE)
            }
        }
    }

    /**
     * Get the compression ratio achieved.
     */
    fun getCompressionRatio(pcmSamples: Int, encodedBytes: Int): Float {
        val pcmBytes = pcmSamples * 2 // 16-bit samples
        return if (encodedBytes > 0) pcmBytes.toFloat() / encodedBytes else 0f
    }

    /**
     * Reset encoder state (call when starting a new stream).
     */
    fun resetEncoder() {
        synchronized(encodeLock) {
            if (opusEncoderHandle != 0L) {
                try {
                    nativeResetEncoder(opusEncoderHandle)
                } catch (e: UnsatisfiedLinkError) {
                    // Ignore - native not available
                }
            }
            logD { "Opus encoder reset" }
        }
    }

    /**
     * Reset decoder state.
     */
    fun resetDecoder() {
        synchronized(decodeLock) {
            if (opusDecoderHandle != 0L) {
                try {
                    nativeResetDecoder(opusDecoderHandle)
                } catch (e: UnsatisfiedLinkError) {
                    // Ignore - native not available
                }
            }
            lastDecodedSamples = null
            logD { "Opus decoder reset" }
        }
    }

    /**
     * Enable or disable Forward Error Correction (FEC).
     * FEC helps maintain audio quality during packet loss.
     */
    fun setFecEnabled(enabled: Boolean) {
        fecEnabled = enabled
        if (opusEncoderHandle != 0L) {
            try {
                nativeSetFecEnabled(opusEncoderHandle, if (enabled) 1 else 0)
            } catch (e: UnsatisfiedLinkError) {
                // Ignore - native not available
            }
        }
        logD { "FEC ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Check if the Opus codec is initialized and ready.
     */
    fun isCodecReady(): Boolean = isCodecInitialized

    // ==================== Software Fallback Methods ====================

    /**
     * Software fallback encoding using simple compression.
     * Provides ~2x compression when native Opus is unavailable.
     */
    @Suppress("MagicNumber")
    private fun softwareEncode(pcmData: ShortArray, output: ByteArray, offset: Int): Int {
        // Simple mu-law-like encoding: 16-bit to 8-bit with logarithmic compression
        if (output.size < offset + pcmData.size) {
            return -1
        }

        for (i in pcmData.indices) {
            val sample = pcmData[i].toInt()
            // Logarithmic compression to 8 bits
            val sign = if (sample < 0) 0x80 else 0
            val magnitude = kotlin.math.abs(sample)
            val compressed = when {
                magnitude < 256 -> magnitude shr 4
                magnitude < 512 -> 16 + ((magnitude - 256) shr 5)
                magnitude < 1024 -> 24 + ((magnitude - 512) shr 6)
                magnitude < 2048 -> 32 + ((magnitude - 1024) shr 7)
                magnitude < 4096 -> 40 + ((magnitude - 2048) shr 8)
                magnitude < 8192 -> 48 + ((magnitude - 4096) shr 9)
                magnitude < 16384 -> 56 + ((magnitude - 8192) shr 10)
                else -> 64 + ((magnitude - 16384) shr 11).coerceAtMost(63)
            }
            output[offset + i] = (sign or compressed).toByte()
        }

        return pcmData.size
    }

    /**
     * Software fallback decoding.
     */
    @Suppress("MagicNumber")
    private fun softwareDecode(
        input: ByteArray,
        offset: Int,
        length: Int,
        output: ShortArray,
        sampleCount: Int,
    ): Int {
        if (length > sampleCount || offset + length > input.size) {
            return -1
        }

        for (i in 0 until length.coerceAtMost(sampleCount)) {
            val byte = input[offset + i].toInt() and 0xFF
            val sign = if (byte and 0x80 != 0) -1 else 1
            val value = byte and 0x7F

            // Expand from 7-bit logarithmic to 16-bit linear
            val magnitude = when {
                value < 16 -> value shl 4
                value < 24 -> 256 + ((value - 16) shl 5)
                value < 32 -> 512 + ((value - 24) shl 6)
                value < 40 -> 1024 + ((value - 32) shl 7)
                value < 48 -> 2048 + ((value - 40) shl 8)
                value < 56 -> 4096 + ((value - 48) shl 9)
                value < 64 -> 8192 + ((value - 56) shl 10)
                else -> 16384 + ((value - 64) shl 11)
            }

            output[i] = (sign * magnitude).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        return length.coerceAtMost(sampleCount)
    }

    /**
     * Full software encode fallback with header.
     */
    @Suppress("MagicNumber")
    private fun softwareEncodeFallback(pcmData: ShortArray, output: ByteArray): Int {
        if (output.size < OPUS_HEADER_SIZE + pcmData.size) {
            return -1
        }

        val buffer = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN)
        val compressedSize = softwareEncode(pcmData, output, OPUS_HEADER_SIZE)

        if (compressedSize > 0) {
            buffer.putShort(pcmData.size.toShort())
            buffer.putShort(compressedSize.toShort())
            buffer.put(FALLBACK_CODEC_ID)
            buffer.put(0)
            buffer.putShort(0)

            return OPUS_HEADER_SIZE + compressedSize
        }

        return -1
    }

    // ==================== Native JNI Methods ====================

    /**
     * Create native Opus encoder.
     * @return Encoder handle (pointer), or 0 on failure
     */
    private external fun nativeCreateEncoder(
        sampleRate: Int,
        channels: Int,
        application: Int,
        bitrate: Int,
        enableFec: Int,
    ): Long

    /**
     * Create native Opus decoder.
     * @return Decoder handle (pointer), or 0 on failure
     */
    private external fun nativeCreateDecoder(sampleRate: Int, channels: Int): Long

    /**
     * Encode PCM to Opus.
     * @return Encoded length in bytes, or negative on error
     */
    private external fun nativeEncode(
        encoderHandle: Long,
        pcmInput: ShortArray,
        inputSize: Int,
        output: ByteArray,
        outputOffset: Int,
        maxOutputSize: Int,
    ): Int

    /**
     * Decode Opus to PCM.
     * @return Number of decoded samples, or negative on error
     */
    private external fun nativeDecode(
        decoderHandle: Long,
        opusInput: ByteArray,
        inputOffset: Int,
        inputSize: Int,
        output: ShortArray,
        maxOutputSamples: Int,
        decodeFec: Int,
    ): Int

    /** Reset encoder state. */
    private external fun nativeResetEncoder(encoderHandle: Long)

    /** Reset decoder state. */
    private external fun nativeResetDecoder(decoderHandle: Long)

    /** Enable/disable FEC in encoder. */
    private external fun nativeSetFecEnabled(encoderHandle: Long, enabled: Int)

    /** Destroy encoder and free resources. */
    private external fun nativeDestroyEncoder(encoderHandle: Long)

    /** Destroy decoder and free resources. */
    private external fun nativeDestroyDecoder(decoderHandle: Long)

    /**
     * Release all WebRTC resources.
     */
    @Suppress("TooGenericExceptionCaught")
    fun cleanup() {
        synchronized(initLock) {
            // Clean up Opus codec
            cleanupOpusCodec()

            try {
                audioTrack?.setEnabled(false)
                audioTrack?.dispose()
                audioSource?.dispose()
                audioDeviceModule?.release()
            } catch (e: RuntimeException) {
                logE({ "Error during WebRTC cleanup" }, e)
            }

            audioTrack = null
            audioSource = null
            audioDeviceModule = null
            mediaConstraints = null
            applicationContext = null
            isInitialized = false

            logD { "WebRTC audio processor cleaned up" }
        }
    }

    /**
     * Clean up Opus codec resources.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun cleanupOpusCodec() {
        synchronized(encodeLock) {
            synchronized(decodeLock) {
                try {
                    if (opusEncoderHandle != 0L) {
                        nativeDestroyEncoder(opusEncoderHandle)
                        opusEncoderHandle = 0
                    }
                    if (opusDecoderHandle != 0L) {
                        nativeDestroyDecoder(opusDecoderHandle)
                        opusDecoderHandle = 0
                    }
                } catch (e: UnsatisfiedLinkError) {
                    // Ignore - native not available
                } catch (e: RuntimeException) {
                    logE({ "Error during Opus cleanup" }, e)
                }

                isCodecInitialized = false
                lastDecodedSamples = null
                logD { "Opus codec cleaned up" }
            }
        }
    }

    /**
     * Shutdown the singleton PeerConnectionFactory.
     * Call this only when completely done with WebRTC in the app lifecycle.
     */
    @Suppress("TooGenericExceptionCaught")
    fun shutdownFactory() {
        synchronized(factoryLock) {
            try {
                peerConnectionFactory?.dispose()
            } catch (e: RuntimeException) {
                logE({ "Error disposing PeerConnectionFactory" }, e)
            }
            peerConnectionFactory = null
            PeerConnectionFactory.shutdownInternalTracer()
            logD { "PeerConnectionFactory shutdown" }
        }
    }

    companion object {
        // Audio configuration matching AdpcmCodec
        const val SAMPLE_RATE = 48_000 // 48kHz
        const val CHANNELS = 1 // Mono
        const val FRAME_SIZE = 960 // 20ms at 48kHz
        const val BITRATE = 32_000 // 32kbps - optimal for voice with Opus

        // Opus codec configuration
        /** Opus header size: sample_count (2) + encoded_length (2) + codec_id (1) + fec_flag (1) + reserved (2) */
        private const val OPUS_HEADER_SIZE = 8

        /** Maximum Opus packet size (worst case: 2 bytes per sample + header) */
        const val OPUS_MAX_PACKET_SIZE = OPUS_HEADER_SIZE + (FRAME_SIZE * 2)

        /** Maximum frame size in samples (120ms at 48kHz) */
        private const val MAX_FRAME_SIZE = 5760

        /** Opus application mode for VoIP (optimized for voice) */
        private const val OPUS_APPLICATION_VOIP = 2048

        /** Codec identifier for Opus */
        private const val OPUS_CODEC_ID: Byte = 0x01

        /** Codec identifier for software fallback (mu-law-like compression) */
        private const val FALLBACK_CODEC_ID: Byte = 0x02

        // Audio constraint keys
        private const val ECHO_CANCELLATION = "googEchoCancellation"
        private const val NOISE_SUPPRESSION = "googNoiseSuppression"
        private const val AUTO_GAIN_CONTROL = "googAutoGainControl"
        private const val HIGH_PASS_FILTER = "googHighpassFilter"

        // Singleton pattern for PeerConnectionFactory
        @Volatile
        private var peerConnectionFactory: PeerConnectionFactory? = null
        private val factoryLock = Any()

        init {
            // Try to load native Opus library
            try {
                System.loadLibrary("opus_jni")
            } catch (e: UnsatisfiedLinkError) {
                // Native library not available, will use software fallback
            }
        }
    }
}

/**
 * WebRTC audio processing statistics for monitoring.
 */
data class WebRTCAudioStats(
    val isInitialized: Boolean,
    val aecEnabled: Boolean,
    val nsEnabled: Boolean,
    val agcEnabled: Boolean,
    val highPassFilterEnabled: Boolean,
    val sampleRate: Int,
    val bitrate: Int,
)

/**
 * Audio processing configuration summary.
 * Provides detailed info about current audio processing state.
 */
data class AudioProcessingConfig(
    val aecEnabled: Boolean,
    val nsEnabled: Boolean,
    val agcEnabled: Boolean,
    val highPassFilterEnabled: Boolean,
    val hardwareAecAvailable: Boolean,
    val hardwareNsAvailable: Boolean,
    val usingWebRtcProcessing: Boolean,
)
