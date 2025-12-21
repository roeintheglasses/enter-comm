package com.entercomm.bikeintercom.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.entercomm.bikeintercom.config.AppConfig
import com.entercomm.bikeintercom.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * Audio configuration for the intercom system.
 */
data class AudioConfig(
    val sampleRate: Int = AdpcmCodec.SAMPLE_RATE, // 48kHz
    val channelCount: Int = AdpcmCodec.CHANNELS, // Mono
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val frameSize: Int = AdpcmCodec.FRAME_SIZE, // 20ms at 48kHz
    val bitrate: Int = AdpcmCodec.BITRATE, // 24 kbps
)

/**
 * Audio processing settings that can be configured at runtime.
 */
data class AudioProcessingSettings(
    val aecEnabled: Boolean = true,
    val nsEnabled: Boolean = true,
    val agcEnabled: Boolean = true,
    val windFilterEnabled: Boolean = true,
    val opusEnabled: Boolean = true,
)

/**
 * AudioManager handles audio capture, processing, encoding, and playback
 * for the mesh intercom network.
 *
 * Features:
 * - Opus codec for 10-20x bandwidth reduction
 * - Echo cancellation (hardware AEC when available)
 * - Noise suppression (hardware NS when available)
 * - Automatic gain control (hardware AGC with software fallback)
 * - Wind noise filtering (optimized for cycling)
 */
class AudioManager(
    private val context: Context,
    /** Callback to send encoded audio data. Parameters: (buffer, offset, length) */
    private val meshCallback: (ByteArray, Int, Int) -> Unit,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // State flows
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _processingSettings = MutableStateFlow(AudioProcessingSettings())
    val processingSettings: StateFlow<AudioProcessingSettings> = _processingSettings.asStateFlow()

    private val _codecStats = MutableStateFlow(CodecStats())
    val codecStats: StateFlow<CodecStats> = _codecStats.asStateFlow()

    // Configuration
    private val audioConfig = AudioConfig()

    // Audio I/O
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    // Codec and processing
    private val adpcmCodec = AdpcmCodec(
        sampleRate = audioConfig.sampleRate,
        channels = audioConfig.channelCount,
        frameSize = audioConfig.frameSize,
        bitrate = audioConfig.bitrate,
    )
    private val effectsProcessor = AudioEffectsProcessor()

    // WebRTC audio processor for superior audio quality with Opus codec
    private var webRtcProcessor: WebRTCAudioProcessor? = null

    // Flag indicating if we're using WebRTC codec (true) or ADPCM fallback (false)
    @Volatile
    private var usingWebRtcCodec = false

    // Codec fallback tracking
    private val _codecStatus = MutableStateFlow(CodecStatus.INITIALIZING)
    val codecStatus: StateFlow<CodecStatus> = _codecStatus.asStateFlow()

    // Track consecutive WebRTC failures for runtime fallback
    @Volatile
    private var consecutiveWebRtcFailures = 0
    private val maxConsecutiveFailures = 5 // Trigger fallback after 5 consecutive failures

    // Per-source audio processors for playback mixing with LRU eviction
    private val audioProcessors = ConcurrentHashMap<String, ProcessorEntry>()
    private val processorsLock = Any()

    /**
     * Wrapper for PlaybackProcessor with last access time for LRU eviction.
     */
    private inner class ProcessorEntry(
        val processor: PlaybackProcessor,
        @Volatile var lastAccessTime: Long = System.currentTimeMillis(),
    ) {
        fun touch() {
            lastAccessTime = System.currentTimeMillis()
        }
    }

    // Processing state
    @Volatile
    private var isProcessing = false

    // Statistics
    private var totalBytesEncoded = 0L
    private var totalBytesRaw = 0L
    private var packetsEncoded = 0L

    // Audio Focus Management
    private val androidAudioManager: android.media.AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager

    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioFocusGranted = false
    private var wasRecordingBeforeFocusLoss = false
    private var playbackVolumeBeforeDuck = 1.0f

    // Audio focus state
    private val _hasAudioFocus = MutableStateFlow(false)
    val hasAudioFocus: StateFlow<Boolean> = _hasAudioFocus.asStateFlow()

    /**
     * Initialize the audio system.
     */
    fun initialize() {
        try {
            logD { "Initializing AudioManager..." }
            _codecStatus.value = CodecStatus.INITIALIZING

            // Try to initialize WebRTC codec first if Opus is enabled
            if (_processingSettings.value.opusEnabled) {
                initializeWebRtcCodec()
            } else {
                // Opus is disabled, use ADPCM directly
                usingWebRtcCodec = false
                _codecStatus.value = CodecStatus.ADPCM_OPUS_DISABLED
                logD { "Opus disabled in settings, using ADPCM codec" }
            }

            // Initialize ADPCM codec as fallback (always available)
            if (!adpcmCodec.initialize()) {
                logE { "Failed to initialize ADPCM codec" }
                // If both codecs failed, mark as failed
                if (!usingWebRtcCodec) {
                    _codecStatus.value = CodecStatus.FAILED
                }
            } else {
                logD { "ADPCM codec initialized: ${audioConfig.sampleRate}Hz, ${audioConfig.bitrate}bps" }
            }

            // Log which codec is active
            if (usingWebRtcCodec) {
                logD { "Using WebRTC/Opus codec for audio processing" }
            } else {
                logD { "Using ADPCM codec for audio processing (fallback)" }
            }

            // Setup audio focus handling
            setupAudioFocus()

            // Setup audio capture
            setupAudioCapture()

            // Setup audio playback
            setupAudioPlayback()

            logD { "AudioManager initialized successfully" }
        } catch (e: Exception) {
            logE({ "Failed to initialize AudioManager" }, e)
            _codecStatus.value = CodecStatus.FAILED
        }
    }

    /**
     * Initialize the WebRTC audio processor with Opus codec.
     *
     * Creates the WebRTCAudioProcessor and attempts initialization.
     * If initialization fails, falls back to ADPCM codec gracefully.
     *
     * Fallback behavior:
     * - If WebRTC initialization succeeds: usingWebRtcCodec = true, status = WEBRTC_ACTIVE
     * - If WebRTC initialization fails: usingWebRtcCodec = false, status = ADPCM_FALLBACK_INIT_FAILURE
     * - If Opus is disabled: usingWebRtcCodec = false, status = ADPCM_OPUS_DISABLED
     */
    private fun initializeWebRtcCodec() {
        try {
            logD { "Attempting WebRTC/Opus codec initialization..." }

            // Create WebRTC processor with matching configuration
            webRtcProcessor = WebRTCAudioProcessor(
                sampleRate = audioConfig.sampleRate,
                bitrate = audioConfig.bitrate,
            )

            // Initialize WebRTC processor (must be called on main thread internally)
            val initialized = webRtcProcessor?.initialize(context) ?: false

            if (initialized && webRtcProcessor?.isCodecReady() == true) {
                usingWebRtcCodec = true
                _codecStatus.value = CodecStatus.WEBRTC_ACTIVE
                consecutiveWebRtcFailures = 0
                logD {
                    "WebRTC/Opus codec initialized successfully: " +
                        "${audioConfig.sampleRate}Hz, ${audioConfig.bitrate}bps"
                }
            } else {
                logW { "WebRTC codec initialization incomplete, falling back to ADPCM" }
                cleanupWebRtcProcessor()
                usingWebRtcCodec = false
                _codecStatus.value = CodecStatus.ADPCM_FALLBACK_INIT_FAILURE
            }
        } catch (e: Exception) {
            logE({ "WebRTC initialization failed, falling back to ADPCM" }, e)
            cleanupWebRtcProcessor()
            usingWebRtcCodec = false
            _codecStatus.value = CodecStatus.ADPCM_FALLBACK_INIT_FAILURE
        }
    }

    /**
     * Clean up WebRTC processor resources.
     */
    private fun cleanupWebRtcProcessor() {
        try {
            webRtcProcessor?.cleanup()
        } catch (e: Exception) {
            logW({ "Error cleaning up WebRTC processor" }, e)
        }
        webRtcProcessor = null
    }

    /**
     * Handle a WebRTC encoding/decoding failure.
     *
     * Tracks consecutive failures and triggers fallback to ADPCM if
     * the failure threshold is exceeded. This provides runtime resilience
     * when WebRTC encounters unexpected issues during operation.
     *
     * @param operation Description of the failed operation for logging
     */
    private fun handleWebRtcFailure(operation: String) {
        consecutiveWebRtcFailures++
        logW { "WebRTC $operation failed (failure $consecutiveWebRtcFailures/$maxConsecutiveFailures)" }

        if (consecutiveWebRtcFailures >= maxConsecutiveFailures) {
            triggerRuntimeFallback()
        }
    }

    /**
     * Record a successful WebRTC operation, resetting the failure counter.
     */
    private fun recordWebRtcSuccess() {
        if (consecutiveWebRtcFailures > 0) {
            consecutiveWebRtcFailures = 0
        }
    }

    /**
     * Trigger runtime fallback from WebRTC to ADPCM.
     *
     * Called when WebRTC encounters too many consecutive failures during operation.
     * This is distinct from initialization failure - it handles cases where WebRTC
     * was working but starts failing (e.g., resource exhaustion, native library issues).
     */
    private fun triggerRuntimeFallback() {
        if (!usingWebRtcCodec) return // Already using fallback

        logE { "Triggering runtime fallback from WebRTC to ADPCM due to consecutive failures" }

        usingWebRtcCodec = false
        _codecStatus.value = CodecStatus.ADPCM_FALLBACK_RUNTIME_FAILURE

        // Clean up WebRTC resources to free memory
        cleanupWebRtcProcessor()

        logD { "Runtime fallback complete, now using ADPCM codec" }
    }

    /**
     * Attempt to recover WebRTC codec after runtime fallback.
     *
     * This can be called to try re-initializing WebRTC after a runtime failure,
     * for example when the user changes settings or after a period of stability.
     *
     * @return true if WebRTC was successfully re-initialized, false otherwise
     */
    fun attemptWebRtcRecovery(): Boolean {
        if (usingWebRtcCodec) {
            logD { "WebRTC already active, no recovery needed" }
            return true
        }

        if (!_processingSettings.value.opusEnabled) {
            logD { "Opus disabled in settings, cannot recover WebRTC" }
            return false
        }

        logD { "Attempting WebRTC recovery..." }
        initializeWebRtcCodec()

        return if (usingWebRtcCodec) {
            logD { "WebRTC recovery successful" }
            true
        } else {
            logW { "WebRTC recovery failed, continuing with ADPCM" }
            false
        }
    }

    /**
     * Encode audio samples using the active codec.
     *
     * Uses WebRTC/Opus if available, falls back to ADPCM.
     * Handles runtime failures gracefully with automatic fallback.
     *
     * @param samples PCM audio samples to encode
     * @param output Pre-allocated output buffer
     * @return Number of bytes encoded, or -1 on failure
     */
    private fun encodeAudioWithFallback(samples: ShortArray, output: ByteArray): Int {
        // Try WebRTC first if active
        if (usingWebRtcCodec) {
            val result = tryWebRtcEncode(samples, output)
            if (result > 0) return result
        }

        // Fallback to ADPCM (or if WebRTC disabled)
        return adpcmCodec.encodeInto(samples, output)
    }

    /**
     * Attempt WebRTC encoding with error handling.
     * @return encoded bytes on success, -1 on failure
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tryWebRtcEncode(samples: ShortArray, output: ByteArray): Int {
        val processor = webRtcProcessor ?: return -1
        return try {
            val encodedSize = processor.encodeInto(samples, output)
            if (encodedSize > 0) {
                recordWebRtcSuccess()
                encodedSize
            } else {
                handleWebRtcFailure("encode")
                -1
            }
        } catch (e: Exception) {
            logE({ "WebRTC encode exception" }, e)
            handleWebRtcFailure("encode")
            -1
        }
    }

    /**
     * Decode audio data using the active codec.
     *
     * Uses WebRTC/Opus if available, falls back to ADPCM.
     * Handles runtime failures gracefully with automatic fallback.
     *
     * @param data Encoded audio data
     * @param output Pre-allocated output buffer for decoded samples
     * @return Number of samples decoded, or -1 on failure
     */
    private fun decodeAudioWithFallback(data: ByteArray, output: ShortArray): Int {
        // Try WebRTC first if active
        if (usingWebRtcCodec) {
            val result = tryWebRtcDecode(data, output)
            if (result > 0) return result
        }

        // Fallback to ADPCM (or if WebRTC disabled)
        return adpcmCodec.decodeInto(data, output)
    }

    /**
     * Attempt WebRTC decoding with error handling.
     * @return decoded samples on success, -1 on failure
     */
    @Suppress("TooGenericExceptionCaught")
    private fun tryWebRtcDecode(data: ByteArray, output: ShortArray): Int {
        val processor = webRtcProcessor ?: return -1
        return try {
            val sampleCount = processor.decodeInto(data, output)
            if (sampleCount > 0) {
                recordWebRtcSuccess()
                sampleCount
            } else {
                handleWebRtcFailure("decode")
                -1
            }
        } catch (e: Exception) {
            logE({ "WebRTC decode exception" }, e)
            handleWebRtcFailure("decode")
            -1
        }
    }

    /**
     * Setup audio focus request for voice communication.
     */
    @SuppressLint("NewApi")
    private fun setupAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            audioFocusRequest = AudioFocusRequest.Builder(
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    handleAudioFocusChange(focusChange)
                }
                .build()

            logD { "Audio focus request configured for API 26+" }
        } else {
            logD { "Audio focus will use legacy API for API < 26" }
        }
    }

    /**
     * Handle audio focus changes from the system.
     */
    private fun handleAudioFocusChange(focusChange: Int) {
        when (focusChange) {
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                logD { "Audio focus gained" }
                audioFocusGranted = true
                _hasAudioFocus.value = true

                // Restore volume after ducking
                restorePlaybackVolume()

                // Resume recording if we were recording before focus loss
                if (wasRecordingBeforeFocusLoss) {
                    wasRecordingBeforeFocusLoss = false
                    scope.launch {
                        logD { "Resuming recording after focus gain" }
                        startRecordingInternal()
                    }
                }
            }

            android.media.AudioManager.AUDIOFOCUS_LOSS -> {
                logD { "Audio focus lost permanently" }
                audioFocusGranted = false
                _hasAudioFocus.value = false

                // Stop recording and playback completely
                if (_isRecording.value) {
                    wasRecordingBeforeFocusLoss = false // Don't auto-resume on permanent loss
                    stopRecording()
                }
                pauseAllPlayback()
            }

            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                logD { "Audio focus lost transiently (e.g., phone call)" }
                audioFocusGranted = false
                _hasAudioFocus.value = false

                // Pause but remember state to resume later
                if (_isRecording.value) {
                    wasRecordingBeforeFocusLoss = true
                    stopRecordingInternal()
                }
                pauseAllPlayback()
            }

            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                logD { "Audio focus lost - can duck (reduce volume)" }
                // Don't stop, just reduce volume
                duckPlaybackVolume()
            }
        }
    }

    /**
     * Request audio focus before starting audio operations.
     * @return true if focus was granted
     */
    @Suppress("DEPRECATION")
    fun requestAudioFocus(): Boolean {
        val audioMgr = androidAudioManager ?: return false

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioMgr.requestAudioFocus(request)
            } ?: android.media.AudioManager.AUDIOFOCUS_REQUEST_FAILED
        } else {
            audioMgr.requestAudioFocus(
                { focusChange -> handleAudioFocusChange(focusChange) },
                android.media.AudioManager.STREAM_VOICE_CALL,
                android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            )
        }

        audioFocusGranted = result == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        _hasAudioFocus.value = audioFocusGranted

        logD { "Audio focus request result: ${if (audioFocusGranted) "granted" else "denied"}" }
        return audioFocusGranted
    }

    /**
     * Abandon audio focus when done with audio operations.
     */
    @Suppress("DEPRECATION")
    fun abandonAudioFocus() {
        val audioMgr = androidAudioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { request ->
                audioMgr.abandonAudioFocusRequest(request)
            }
        } else {
            audioMgr.abandonAudioFocus { focusChange ->
                handleAudioFocusChange(focusChange)
            }
        }

        audioFocusGranted = false
        _hasAudioFocus.value = false
        wasRecordingBeforeFocusLoss = false
        logD { "Audio focus abandoned" }
    }

    /**
     * Reduce playback volume for ducking.
     */
    private fun duckPlaybackVolume() {
        playbackVolumeBeforeDuck = 1.0f
        audioProcessors.values.forEach { entry ->
            entry.processor.setVolume(0.3f)
        }
        logD { "Playback volume ducked to 30%" }
    }

    /**
     * Restore playback volume after ducking.
     */
    private fun restorePlaybackVolume() {
        audioProcessors.values.forEach { entry ->
            entry.processor.setVolume(playbackVolumeBeforeDuck)
        }
        logD { "Playback volume restored" }
    }

    /**
     * Pause all active playback processors.
     */
    private fun pauseAllPlayback() {
        audioProcessors.values.forEach { entry ->
            entry.processor.pause()
        }
        logD { "All playback paused" }
    }

    /**
     * Resume all paused playback processors.
     */
    private fun resumeAllPlayback() {
        audioProcessors.values.forEach { entry ->
            entry.processor.resume()
        }
        logD { "All playback resumed" }
    }

    /**
     * Start recording and transmitting audio.
     * Requests audio focus before starting.
     */
    fun startRecording() {
        if (_isRecording.value) return

        if (!hasAudioPermission()) {
            logE { "Cannot start recording: RECORD_AUDIO permission not granted" }
            return
        }

        // Request audio focus before starting
        if (!requestAudioFocus()) {
            logW { "Audio focus not granted, starting recording anyway" }
            // Continue anyway - focus might be granted later or user may not care
        }

        startRecordingInternal()
    }

    /**
     * Internal method to start recording without requesting focus.
     * Used for resuming after focus regain.
     */
    private fun startRecordingInternal() {
        if (_isRecording.value) return

        val record = audioRecord
        if (record == null) {
            logE { "Cannot start recording: AudioRecord not initialized" }
            return
        }

        scope.launch {
            try {
                record.startRecording()
                _isRecording.value = true
                isProcessing = true

                // Initialize audio effects with the audio session
                effectsProcessor.initialize(record.audioSessionId, audioConfig.sampleRate)
                applyProcessingSettings(_processingSettings.value)

                // Start audio processing loop
                launch { processAudioInput() }

                logD { "Audio recording started with Opus encoding" }
            } catch (e: Exception) {
                logE({ "Failed to start recording" }, e)
                _isRecording.value = false
            }
        }
    }

    /**
     * Stop recording.
     * Abandons audio focus.
     */
    fun stopRecording() {
        stopRecordingInternal()
        abandonAudioFocus()
    }

    /**
     * Internal method to stop recording without abandoning focus.
     * Used for pausing during transient focus loss.
     */
    private fun stopRecordingInternal() {
        _isRecording.value = false
        isProcessing = false

        try {
            audioRecord?.stop()
            effectsProcessor.cleanup()
            logD { "Audio recording stopped" }

            // Log compression stats
            if (totalBytesRaw > 0) {
                val ratio = totalBytesRaw.toFloat() / totalBytesEncoded.coerceAtLeast(1)
                logD { "Compression stats: ${totalBytesRaw}B raw -> ${totalBytesEncoded}B encoded (${ratio.format(1)}x)" }
            }
        } catch (e: Exception) {
            logE({ "Error stopping recording" }, e)
        }
    }

    /**
     * Set mute state.
     */
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        logD { "Audio ${if (muted) "muted" else "unmuted"}" }
    }

    /**
     * Update audio processing settings.
     */
    fun updateProcessingSettings(settings: AudioProcessingSettings) {
        _processingSettings.value = settings
        applyProcessingSettings(settings)
        logD { "Processing settings updated: $settings" }
    }

    /**
     * Play received audio data from a mesh node.
     */
    fun playAudioData(audioData: ByteArray, sourceId: String) {
        scope.launch {
            try {
                if (audioData.isEmpty()) {
                    logW { "Received empty audio data from $sourceId" }
                    return@launch
                }

                // Validate size
                if (audioData.size > 16384) {
                    logE { "Audio data too large from $sourceId: ${audioData.size} bytes" }
                    return@launch
                }

                logD { "Received ${audioData.size} bytes from $sourceId" }

                // Get or create processor for this source with LRU eviction
                val entry = getOrCreateProcessor(sourceId)
                entry.touch()

                // Decode using active codec (WebRTC/Opus or ADPCM fallback)
                val decodeBuffer = AudioBufferPool.acquireDecodeBuffer()
                val sampleCount = if (_processingSettings.value.opusEnabled) {
                    decodeAudioWithFallback(audioData, decodeBuffer)
                } else {
                    decodePcmInto(audioData, decodeBuffer)
                }

                if (sampleCount > 0) {
                    // Note: play() will copy samples into jitter buffer,
                    // so pooled buffer is safe to reuse after this call
                    entry.processor.play(decodeBuffer, sampleCount)
                    logD { "Played $sampleCount samples from $sourceId" }
                }
            } catch (e: OutOfMemoryError) {
                logE({ "OOM processing audio from $sourceId" }, e)
                removeProcessor(sourceId)
            } catch (e: Exception) {
                logE({ "Error playing audio from $sourceId" }, e)
            }
        }
    }

    /**
     * Get or create a processor for the given source, evicting LRU if at capacity.
     */
    private fun getOrCreateProcessor(sourceId: String): ProcessorEntry {
        // Fast path: processor already exists
        audioProcessors[sourceId]?.let { return it }

        // Slow path: need to create a new processor
        synchronized(processorsLock) {
            // Double-check after acquiring lock
            audioProcessors[sourceId]?.let { return it }

            // Evict LRU processor if at capacity
            if (audioProcessors.size >= AppConfig.Audio.MAX_AUDIO_PROCESSORS) {
                evictLruProcessor()
            }

            // Create new processor
            logD { "Creating PlaybackProcessor for $sourceId (pool: ${audioProcessors.size + 1}/${AppConfig.Audio.MAX_AUDIO_PROCESSORS})" }
            val processor = PlaybackProcessor(sourceId, audioConfig)
            val entry = ProcessorEntry(processor)
            audioProcessors[sourceId] = entry
            return entry
        }
    }

    /**
     * Evict the least recently used processor to make room for a new one.
     */
    private fun evictLruProcessor() {
        val lruEntry = audioProcessors.entries
            .minByOrNull { it.value.lastAccessTime }
            ?: return

        logD { "Evicting LRU processor for ${lruEntry.key} (age: ${System.currentTimeMillis() - lruEntry.value.lastAccessTime}ms)" }
        audioProcessors.remove(lruEntry.key)?.processor?.cleanup()
    }

    /**
     * Remove and cleanup a processor.
     */
    private fun removeProcessor(sourceId: String) {
        audioProcessors.remove(sourceId)?.processor?.cleanup()
    }

    /**
     * Handle packet loss - generate comfort noise for a source.
     *
     * Uses WebRTC's PLC when available for better concealment,
     * falls back to ADPCM PLC otherwise.
     */
    fun handlePacketLoss(sourceId: String) {
        scope.launch {
            try {
                val entry = audioProcessors[sourceId] ?: return@launch
                entry.touch()

                // Use WebRTC PLC if active, otherwise ADPCM PLC
                val plcSamples = if (usingWebRtcCodec) {
                    webRtcProcessor?.decodePLC() ?: adpcmCodec.decodePLC()
                } else {
                    adpcmCodec.decodePLC()
                }

                if (plcSamples.isNotEmpty()) {
                    entry.processor.play(plcSamples)
                }
            } catch (e: Exception) {
                logE({ "Error handling packet loss for $sourceId" }, e)
            }
        }
    }

    /**
     * Get current codec statistics.
     */
    fun getCodecStats(): CodecStats {
        val compressionRatio = if (totalBytesEncoded > 0) {
            totalBytesRaw.toFloat() / totalBytesEncoded
        } else {
            0f
        }

        return CodecStats(
            packetsEncoded = packetsEncoded,
            bytesRaw = totalBytesRaw,
            bytesEncoded = totalBytesEncoded,
            compressionRatio = compressionRatio,
            effectsStats = effectsProcessor.getStats(),
        )
    }

    /**
     * Cleanup all resources.
     */
    fun cleanup() {
        stopRecording()
        isProcessing = false

        // Cleanup playback processors
        audioProcessors.values.forEach { it.processor.cleanup() }
        audioProcessors.clear()

        // Release audio resources
        audioRecord?.release()
        audioTrack?.release()

        // Cleanup codecs
        cleanupWebRtcProcessor()
        adpcmCodec.cleanup()

        scope.cancel()
        logD { "AudioManager cleaned up" }
    }

    /**
     * Check if WebRTC codec is currently active.
     * @return true if WebRTC/Opus codec is in use, false if using ADPCM fallback
     */
    fun isUsingWebRtcCodec(): Boolean = usingWebRtcCodec

    /**
     * Get the WebRTC audio processor (for advanced configuration or debugging).
     * @return WebRTCAudioProcessor instance if initialized, null otherwise
     */
    fun getWebRtcProcessor(): WebRTCAudioProcessor? = webRtcProcessor

    // Private methods

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun setupAudioCapture() {
        if (!hasAudioPermission()) {
            logE { "RECORD_AUDIO permission not granted" }
            return
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            audioConfig.audioFormat,
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            logE { "Invalid buffer size: $bufferSize" }
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, // Optimized for voice
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            audioConfig.audioFormat,
            bufferSize * 2,
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            logE { "AudioRecord initialization failed" }
            audioRecord?.release()
            audioRecord = null
        } else {
            logD { "AudioRecord initialized: ${audioConfig.sampleRate}Hz, buffer=$bufferSize" }
        }
    }

    private fun setupAudioPlayback() {
        val bufferSize = AudioTrack.getMinBufferSize(
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioConfig.audioFormat,
        )

        audioTrack = AudioTrack(
            android.media.AudioManager.STREAM_VOICE_CALL,
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioConfig.audioFormat,
            bufferSize * 2,
            AudioTrack.MODE_STREAM,
        )

        audioTrack?.play()
        logD { "AudioTrack initialized: ${audioConfig.sampleRate}Hz" }
    }

    private suspend fun processAudioInput() {
        val bufferSize = audioConfig.frameSize
        val buffer = ShortArray(bufferSize)

        while (isProcessing && _isRecording.value) {
            try {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                if (samplesRead > 0 && !_isMuted.value) {
                    // Calculate audio level for UI
                    val level = calculateAudioLevel(buffer, samplesRead)
                    _audioLevel.value = level

                    // Apply audio processing (AEC, NS, AGC, wind filter)
                    // Note: effectsProcessor may return a pooled buffer, which is fine
                    // since we consume it synchronously before the next frame
                    val processedSamples = effectsProcessor.process(buffer)

                    // Encode using active codec (WebRTC/Opus or ADPCM fallback)
                    val encodeBuffer = AudioBufferPool.getEncodeBuffer()
                    val encodedSize = if (_processingSettings.value.opusEnabled) {
                        encodeAudioWithFallback(processedSamples, encodeBuffer)
                    } else {
                        encodePcmInto(processedSamples, encodeBuffer)
                    }

                    if (encodedSize > 0) {
                        // Update stats
                        val rawBytes = samplesRead * 2L
                        totalBytesRaw += rawBytes
                        totalBytesEncoded += encodedSize
                        packetsEncoded++

                        // Update codec stats flow
                        _codecStats.value = getCodecStats()

                        // Send to mesh network (zero-copy - caller must consume synchronously)
                        meshCallback(encodeBuffer, 0, encodedSize)
                    }
                } else if (samplesRead > 0 && _isMuted.value) {
                    // Update level to show we're muted but receiving audio
                    _audioLevel.value = 0f
                }

                // Sleep for frame duration
                delay((audioConfig.frameSize * 1000L / audioConfig.sampleRate))
            } catch (e: Exception) {
                logE({ "Error processing audio input" }, e)
                delay(100)
            }
        }
    }

    private fun applyProcessingSettings(settings: AudioProcessingSettings) {
        effectsProcessor.setAecEnabled(settings.aecEnabled)
        effectsProcessor.setNsEnabled(settings.nsEnabled)
        effectsProcessor.setAgcEnabled(settings.agcEnabled)
        effectsProcessor.setWindFilterEnabled(settings.windFilterEnabled)
    }

    private fun calculateAudioLevel(buffer: ShortArray, length: Int): Float {
        var sum = 0L
        for (i in 0 until length) {
            sum += (buffer[i] * buffer[i]).toLong()
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / length)
        return (rms / Short.MAX_VALUE).toFloat()
    }

    // Fallback PCM encoding/decoding (for when ADPCM is disabled)
    private fun encodePcmInto(samples: ShortArray, output: ByteArray): Int {
        val requiredSize = samples.size * 2
        if (output.size < requiredSize) return -1

        val buffer = java.nio.ByteBuffer.wrap(output).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return requiredSize
    }

    private fun decodePcmInto(data: ByteArray, output: ShortArray): Int {
        if (data.isEmpty() || data.size % 2 != 0) return -1
        val sampleCount = data.size / 2
        if (output.size < sampleCount) return -1

        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            output[i] = buffer.short
        }
        return sampleCount
    }

    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

    /**
     * Per-source audio playback processor with jitter buffering.
     *
     * Uses a JitterBuffer to smooth out network timing variations,
     * preventing audio glitches from variable packet arrival times.
     */
    private inner class PlaybackProcessor(
        private val sourceId: String,
        private val config: AudioConfig,
    ) {
        private var audioTrack: AudioTrack? = null
        private val lock = Any()
        private var isInitialized = false

        // Jitter buffer for smoothing network timing variations
        private val jitterBuffer = JitterBuffer(
            bufferSizeMs = 80, // 80ms buffer suitable for bike intercom
            sampleRate = config.sampleRate,
            frameSizeMs = (config.frameSize * 1000) / config.sampleRate,
        )

        // Sequence counter for incoming frames (per-source)
        private var frameSequence = 0L

        // Playback job for extracting frames from buffer
        private var playbackJob: Job? = null

        @Volatile
        private var isPlaying = false

        init {
            setup()
        }

        private fun setup() {
            synchronized(lock) {
                try {
                    val bufferSize = AudioTrack.getMinBufferSize(
                        config.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        config.audioFormat,
                    )

                    if (bufferSize == AudioTrack.ERROR_BAD_VALUE || bufferSize == AudioTrack.ERROR) {
                        logE { "Invalid buffer size for $sourceId" }
                        return
                    }

                    audioTrack = AudioTrack(
                        android.media.AudioManager.STREAM_VOICE_CALL,
                        config.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        config.audioFormat,
                        bufferSize * 4,
                        AudioTrack.MODE_STREAM,
                    ).apply {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            play()
                            isInitialized = true
                            logD { "PlaybackProcessor initialized for $sourceId with jitter buffer" }
                        } else {
                            release()
                            logE { "Failed to initialize AudioTrack for $sourceId" }
                        }
                    }

                    // Start playback loop
                    startPlaybackLoop()
                } catch (e: Exception) {
                    logE({ "Error setting up PlaybackProcessor for $sourceId" }, e)
                }
            }
        }

        /**
         * Start the playback loop that extracts frames from the jitter buffer.
         */
        private fun startPlaybackLoop() {
            if (playbackJob?.isActive == true) return

            isPlaying = true
            playbackJob = scope.launch {
                val frameDurationMs = (config.frameSize * 1000L) / config.sampleRate
                logD { "PlaybackProcessor: Starting playback loop for $sourceId (frame=${frameDurationMs}ms)" }

                while (isPlaying && isActive) {
                    try {
                        // Extract frame from jitter buffer
                        val frame = jitterBuffer.getFrame()

                        if (frame != null) {
                            // Write to AudioTrack
                            writeToAudioTrack(frame)
                        }

                        // Wait for next frame period
                        delay(frameDurationMs)
                    } catch (e: Exception) {
                        if (isPlaying) {
                            logE({ "Playback loop error for $sourceId" }, e)
                            delay(100)
                        }
                    }
                }

                logD { "PlaybackProcessor: Playback loop ended for $sourceId" }
            }
        }

        /**
         * Write samples directly to AudioTrack.
         */
        private fun writeToAudioTrack(samples: ShortArray) {
            synchronized(lock) {
                val track = audioTrack
                if (!isInitialized || track == null) return

                try {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    val written = track.write(samples, 0, samples.size)
                    if (written < 0) {
                        logW { "AudioTrack write error: $written for $sourceId" }
                    }
                } catch (e: Exception) {
                    logE({ "Error writing audio for $sourceId" }, e)
                    isInitialized = false
                }
            }
        }

        /**
         * Add audio samples to the jitter buffer for playback.
         *
         * @param samples Sample buffer (may be pooled, will be copied)
         * @param sampleCount Number of valid samples in the buffer
         */
        fun play(samples: ShortArray, sampleCount: Int = samples.size) {
            if (sampleCount <= 0) return

            synchronized(lock) {
                if (!isInitialized) {
                    setup()
                    return
                }
            }

            // Add frame to jitter buffer with sequence number
            // Copy samples since the input may be a pooled buffer
            val frameSamples = if (sampleCount == samples.size) samples else samples.copyOf(sampleCount)
            val seq = frameSequence++
            val timestamp = System.currentTimeMillis()
            jitterBuffer.addFrame(frameSamples, seq, timestamp)
        }

        /**
         * Get jitter buffer statistics.
         */
        fun getJitterStats(): JitterBuffer.JitterBufferStats {
            return jitterBuffer.getStats()
        }

        /**
         * Set playback volume (0.0 to 1.0).
         */
        @SuppressLint("NewApi")
        fun setVolume(volume: Float) {
            synchronized(lock) {
                val track = audioTrack ?: return
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        track.setVolume(volume.coerceIn(0f, 1f))
                    } else {
                        @Suppress("DEPRECATION")
                        track.setStereoVolume(volume, volume)
                    }
                } catch (e: Exception) {
                    logW({ "Error setting volume for $sourceId" }, e)
                }
            }
        }

        /**
         * Pause playback (stops extracting from jitter buffer).
         */
        fun pause() {
            synchronized(lock) {
                val track = audioTrack
                if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    try {
                        track.pause()
                        logD { "PlaybackProcessor paused for $sourceId" }
                    } catch (e: Exception) {
                        logW({ "Error pausing PlaybackProcessor for $sourceId" }, e)
                    }
                }
            }
        }

        /**
         * Resume playback after pause.
         */
        fun resume() {
            synchronized(lock) {
                val track = audioTrack
                if (track != null && isInitialized && track.playState == AudioTrack.PLAYSTATE_PAUSED) {
                    try {
                        track.play()
                        logD { "PlaybackProcessor resumed for $sourceId" }
                    } catch (e: Exception) {
                        logW({ "Error resuming PlaybackProcessor for $sourceId" }, e)
                    }
                }
            }
        }

        fun cleanup() {
            isPlaying = false
            playbackJob?.cancel()
            playbackJob = null

            synchronized(lock) {
                try {
                    audioTrack?.stop()
                    audioTrack?.release()
                } catch (e: Exception) {
                    logW({ "Error cleaning up PlaybackProcessor for $sourceId" }, e)
                }
                audioTrack = null
                isInitialized = false
            }

            jitterBuffer.reset()
            logD { "PlaybackProcessor cleaned up for $sourceId" }
        }
    }
}

/**
 * Codec statistics for monitoring.
 */
data class CodecStats(
    val packetsEncoded: Long = 0,
    val bytesRaw: Long = 0,
    val bytesEncoded: Long = 0,
    val compressionRatio: Float = 0f,
    val effectsStats: AudioEffectsStats? = null,
)

/**
 * Represents the current codec status for fallback monitoring.
 */
enum class CodecStatus {
    /** Codec is being initialized */
    INITIALIZING,

    /** WebRTC/Opus codec is active and working */
    WEBRTC_ACTIVE,

    /** ADPCM fallback is active due to WebRTC initialization failure */
    ADPCM_FALLBACK_INIT_FAILURE,

    /** ADPCM fallback is active due to runtime WebRTC failures */
    ADPCM_FALLBACK_RUNTIME_FAILURE,

    /** ADPCM is active because Opus is disabled in settings */
    ADPCM_OPUS_DISABLED,

    /** Codec initialization failed completely */
    FAILED,
}
