package com.entercomm.bikeintercom.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
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
    val sampleRate: Int = OpusCodec.SAMPLE_RATE,  // 48kHz - native Opus rate
    val channelCount: Int = OpusCodec.CHANNELS,    // Mono
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val frameSize: Int = OpusCodec.FRAME_SIZE,     // 20ms at 48kHz
    val bitrate: Int = OpusCodec.BITRATE           // 24 kbps
)

/**
 * Audio processing settings that can be configured at runtime.
 */
data class AudioProcessingSettings(
    val aecEnabled: Boolean = true,
    val nsEnabled: Boolean = true,
    val agcEnabled: Boolean = true,
    val windFilterEnabled: Boolean = true,
    val opusEnabled: Boolean = true
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
    private val meshCallback: (ByteArray) -> Unit
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
    private val opusCodec = OpusCodec(
        sampleRate = audioConfig.sampleRate,
        channels = audioConfig.channelCount,
        frameSize = audioConfig.frameSize,
        bitrate = audioConfig.bitrate
    )
    private val effectsProcessor = AudioEffectsProcessor()

    // Per-source audio processors for playback mixing
    private val audioProcessors = ConcurrentHashMap<String, PlaybackProcessor>()

    // Processing state
    @Volatile
    private var isProcessing = false

    // Statistics
    private var totalBytesEncoded = 0L
    private var totalBytesRaw = 0L
    private var packetsEncoded = 0L

    /**
     * Initialize the audio system.
     */
    fun initialize() {
        try {
            logD { "Initializing AudioManager with Opus codec..." }

            // Initialize Opus codec
            if (!opusCodec.initialize()) {
                logE { "Failed to initialize Opus codec, falling back to PCM" }
            } else {
                logD { "Opus codec initialized: ${audioConfig.sampleRate}Hz, ${audioConfig.bitrate}bps" }
            }

            // Setup audio capture
            setupAudioCapture()

            // Setup audio playback
            setupAudioPlayback()

            logD { "AudioManager initialized successfully" }
        } catch (e: Exception) {
            logE({ "Failed to initialize AudioManager" }, e)
        }
    }

    /**
     * Start recording and transmitting audio.
     */
    fun startRecording() {
        if (_isRecording.value) return

        if (!hasAudioPermission()) {
            logE { "Cannot start recording: RECORD_AUDIO permission not granted" }
            return
        }

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
     */
    fun stopRecording() {
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

                // Get or create processor for this source
                val processor = audioProcessors.getOrPut(sourceId) {
                    logD { "Creating PlaybackProcessor for $sourceId" }
                    PlaybackProcessor(sourceId, audioConfig)
                }

                // Decode audio
                val decodedSamples = if (_processingSettings.value.opusEnabled) {
                    opusCodec.decode(audioData)
                } else {
                    decodePcm(audioData)
                }

                if (decodedSamples.isNotEmpty()) {
                    processor.play(decodedSamples)
                    logD { "Played ${decodedSamples.size} samples from $sourceId" }
                }

            } catch (e: OutOfMemoryError) {
                logE({ "OOM processing audio from $sourceId" }, e)
                audioProcessors.remove(sourceId)?.cleanup()
            } catch (e: Exception) {
                logE({ "Error playing audio from $sourceId" }, e)
            }
        }
    }

    /**
     * Handle packet loss - generate comfort noise for a source.
     */
    fun handlePacketLoss(sourceId: String) {
        scope.launch {
            try {
                val processor = audioProcessors[sourceId] ?: return@launch
                val plcSamples = opusCodec.decodePLC()
                if (plcSamples.isNotEmpty()) {
                    processor.play(plcSamples)
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
        } else 0f

        return CodecStats(
            packetsEncoded = packetsEncoded,
            bytesRaw = totalBytesRaw,
            bytesEncoded = totalBytesEncoded,
            compressionRatio = compressionRatio,
            effectsStats = effectsProcessor.getStats()
        )
    }

    /**
     * Cleanup all resources.
     */
    fun cleanup() {
        stopRecording()
        isProcessing = false

        // Cleanup playback processors
        audioProcessors.values.forEach { it.cleanup() }
        audioProcessors.clear()

        // Release audio resources
        audioRecord?.release()
        audioTrack?.release()

        // Cleanup codec
        opusCodec.cleanup()

        scope.cancel()
        logD { "AudioManager cleaned up" }
    }

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
            audioConfig.audioFormat
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            logE { "Invalid buffer size: $bufferSize" }
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,  // Optimized for voice
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            audioConfig.audioFormat,
            bufferSize * 2
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
            audioConfig.audioFormat
        )

        audioTrack = AudioTrack(
            android.media.AudioManager.STREAM_VOICE_CALL,
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            audioConfig.audioFormat,
            bufferSize * 2,
            AudioTrack.MODE_STREAM
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
                    val processedSamples = effectsProcessor.process(
                        buffer.copyOf(samplesRead)
                    )

                    // Encode with Opus
                    val encodedData = if (_processingSettings.value.opusEnabled) {
                        opusCodec.encode(processedSamples)
                    } else {
                        encodePcm(processedSamples)
                    }

                    if (encodedData != null && encodedData.isNotEmpty()) {
                        // Update stats
                        val rawBytes = samplesRead * 2L
                        totalBytesRaw += rawBytes
                        totalBytesEncoded += encodedData.size
                        packetsEncoded++

                        // Update codec stats flow
                        _codecStats.value = getCodecStats()

                        // Send to mesh network
                        meshCallback(encodedData)
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

    // Fallback PCM encoding/decoding (for when Opus is disabled)
    private fun encodePcm(samples: ShortArray): ByteArray {
        val buffer = java.nio.ByteBuffer.allocate(samples.size * 2)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private fun decodePcm(data: ByteArray): ShortArray {
        if (data.isEmpty() || data.size % 2 != 0) return ShortArray(0)
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(data.size / 2)
        for (i in samples.indices) {
            samples[i] = buffer.short
        }
        return samples
    }

    private fun Float.format(decimals: Int) = "%.${decimals}f".format(this)

    /**
     * Per-source audio playback processor.
     */
    private inner class PlaybackProcessor(
        private val sourceId: String,
        private val config: AudioConfig
    ) {
        private var audioTrack: AudioTrack? = null
        private val lock = Any()
        private var isInitialized = false

        init {
            setup()
        }

        private fun setup() {
            synchronized(lock) {
                try {
                    val bufferSize = AudioTrack.getMinBufferSize(
                        config.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        config.audioFormat
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
                        AudioTrack.MODE_STREAM
                    ).apply {
                        if (state == AudioTrack.STATE_INITIALIZED) {
                            play()
                            isInitialized = true
                            logD { "PlaybackProcessor initialized for $sourceId" }
                        } else {
                            release()
                            logE { "Failed to initialize AudioTrack for $sourceId" }
                        }
                    }
                } catch (e: Exception) {
                    logE({ "Error setting up PlaybackProcessor for $sourceId" }, e)
                }
            }
        }

        fun play(samples: ShortArray) {
            if (samples.isEmpty()) return

            synchronized(lock) {
                val track = audioTrack
                if (!isInitialized || track == null) {
                    setup()
                    return
                }

                try {
                    if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        track.play()
                    }

                    val written = track.write(samples, 0, samples.size)
                    if (written < 0) {
                        logW { "AudioTrack write error: $written for $sourceId" }
                    }
                } catch (e: Exception) {
                    logE({ "Error playing audio for $sourceId" }, e)
                    isInitialized = false
                }
            }
        }

        fun cleanup() {
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
    val effectsStats: AudioEffectsStats? = null
)
