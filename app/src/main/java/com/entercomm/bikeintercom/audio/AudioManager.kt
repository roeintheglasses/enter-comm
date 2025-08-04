package com.entercomm.bikeintercom.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
// import org.webrtc.* // Commented out for now to avoid compatibility issues
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

data class AudioConfig(
    val sampleRate: Int = 48000,
    val channelCount: Int = 1,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    val bufferSizeMs: Int = 20 // 20ms buffer
)

class AudioManager(
    private val context: Context,
    private val meshCallback: (ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "AudioManager"
        private const val OPUS_PAYLOAD_TYPE = 111
    }
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()
    
    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()
    
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    
    private val audioConfig = AudioConfig()
    
    // WebRTC components - temporarily commented out for initial build
    // private var peerConnectionFactory: PeerConnectionFactory? = null
    // private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var audioRecord: AudioRecord? = null
    
    // Audio processing
    private var isProcessing = false
    private val audioProcessors = ConcurrentHashMap<String, AudioProcessor>()
    
    // Opus encoder/decoder (simplified - in production use WebRTC's built-in opus)
    private val audioEncoder = AudioEncoder()
    private val audioDecoder = AudioDecoder()
    
    fun initialize() {
        try {
            Log.d(TAG, "Starting AudioManager initialization...")
            // initializeWebRTC() // Temporarily commented out
            setupAudioCapture()
            setupAudioPlayback()
            Log.d(TAG, "AudioManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioManager", e)
            // Don't re-throw the exception to prevent app crash
            // The audio functionality will be limited but app should still work
        }
    }
    
    fun startRecording() {
        if (_isRecording.value) return
        
        scope.launch {
            try {
                audioRecord?.startRecording()
                _isRecording.value = true
                isProcessing = true
                
                launch { processAudioInput() }
                
                Log.d(TAG, "Audio recording started")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start recording", e)
                _isRecording.value = false
            }
        }
    }
    
    fun stopRecording() {
        _isRecording.value = false
        isProcessing = false
        
        try {
            audioRecord?.stop()
            Log.d(TAG, "Audio recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }
    
    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        Log.d(TAG, "Audio ${if (muted) "muted" else "unmuted"}")
    }
    
    fun playAudioData(audioData: ByteArray, sourceId: String) {
        scope.launch {
            try {
                Log.d(TAG, "Received audio data from $sourceId: ${audioData.size} bytes")
                
                if (audioData.isEmpty()) {
                    Log.w(TAG, "Received empty audio data from $sourceId")
                    return@launch
                }
                
                // Validate audio data size - prevent processing extremely large payloads
                if (audioData.size > 16384) { // 16KB limit
                    Log.e(TAG, "Audio data too large from $sourceId: ${audioData.size} bytes")
                    return@launch
                }
                
                val processor = audioProcessors.getOrPut(sourceId) {
                    Log.d(TAG, "Creating new AudioProcessor for $sourceId")
                    try {
                        AudioProcessor(sourceId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to create AudioProcessor for $sourceId", e)
                        return@launch
                    }
                }
                
                val decodedAudio = try {
                    audioDecoder.decode(audioData)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decode audio from $sourceId", e)
                    return@launch
                }
                
                Log.d(TAG, "Decoded audio: ${decodedAudio.size} samples")
                
                if (decodedAudio.isNotEmpty()) {
                    // Additional safety check on decoded data
                    if (decodedAudio.size <= 8192) { // Reasonable limit for decoded samples
                        processor.processAndPlay(decodedAudio)
                    } else {
                        Log.e(TAG, "Decoded audio too large from $sourceId: ${decodedAudio.size} samples")
                    }
                } else {
                    Log.w(TAG, "Decoded audio is empty for $sourceId")
                }
                
            } catch (e: OutOfMemoryError) {
                Log.e(TAG, "Out of memory processing audio from $sourceId", e)
                // Remove the problematic processor
                audioProcessors.remove(sourceId)?.cleanup()
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio from $sourceId", e)
                e.printStackTrace()
                // Try to recover by removing and recreating the processor
                audioProcessors.remove(sourceId)?.cleanup()
            }
        }
    }
    
    fun cleanup() {
        stopRecording()
        isProcessing = false
        
        audioProcessors.values.forEach { it.cleanup() }
        audioProcessors.clear()
        
        audioRecord?.release()
        audioTrack?.release()
        // audioSource?.dispose() // Temporarily commented out
        // peerConnectionFactory?.dispose() // Temporarily commented out
        
        scope.cancel()
        Log.d(TAG, "AudioManager cleaned up")
    }
    
    /*
    private fun initializeWebRTC() {
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        
        PeerConnectionFactory.initialize(initializationOptions)
        
        val options = PeerConnectionFactory.Options()
        val encoderFactory = DefaultVideoEncoderFactory(null, false, false)
        val decoderFactory = DefaultVideoDecoderFactory(null)
        
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(createAudioDeviceModule())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        
        // Create audio source for WebRTC audio processing
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        
        audioSource = peerConnectionFactory?.createAudioSource(audioConstraints)
    }
    */
    
    /*
    private fun createAudioDeviceModule(): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(context)
            .setUseStereoInput(false)
            .setUseStereoOutput(false)
            .setAudioRecordErrorCallback { errorMessage ->
                Log.e(TAG, "Audio record error: $errorMessage")
            }
            .setAudioTrackErrorCallback { errorMessage ->
                Log.e(TAG, "Audio track error: $errorMessage")
            }
            .createAudioDeviceModule()
    }
    */
    
    private fun setupAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            audioConfig.audioFormat
        )
        
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            audioConfig.audioFormat,
            bufferSize * 2
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw RuntimeException("AudioRecord initialization failed")
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
    }
    
    private suspend fun processAudioInput() {
        val bufferSize = audioConfig.sampleRate * audioConfig.bufferSizeMs / 1000
        val buffer = ShortArray(bufferSize)
        
        while (isProcessing && _isRecording.value) {
            try {
                val samplesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                
                if (samplesRead > 0 && !_isMuted.value) {
                    // Calculate audio level for UI feedback
                    val level = calculateAudioLevel(buffer, samplesRead)
                    _audioLevel.value = level
                    
                    // Apply audio processing (noise reduction, echo cancellation, etc.)
                    val processedAudio = applyAudioProcessing(buffer, samplesRead)
                    
                    // Encode audio data
                    val encodedAudio = audioEncoder.encode(processedAudio)
                    
                    // Send encoded audio through mesh network
                    meshCallback(encodedAudio)
                }
                
                delay(audioConfig.bufferSizeMs.toLong())
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing audio input", e)
                delay(100)
            }
        }
    }
    
    private fun applyAudioProcessing(buffer: ShortArray, length: Int): ShortArray {
        // Apply noise reduction, gain control, etc.
        // This is where WebRTC's audio processing would be applied
        
        // Simple gain control for now
        val processed = ShortArray(length)
        val gain = if (_audioLevel.value < 0.1f) 2.0f else 1.0f
        
        for (i in 0 until length) {
            val amplified = (buffer[i] * gain).toInt()
            processed[i] = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        
        return processed
    }
    
    private fun calculateAudioLevel(buffer: ShortArray, length: Int): Float {
        var sum = 0L
        for (i in 0 until length) {
            sum += (buffer[i] * buffer[i]).toLong()
        }
        val rms = kotlin.math.sqrt(sum.toDouble() / length)
        return (rms / Short.MAX_VALUE).toFloat()
    }
    
    // Inner class for per-source audio processing
    private inner class AudioProcessor(private val sourceId: String) {
        @Volatile
        private var audioTrack: AudioTrack? = null
        private val trackLock = Any()
        @Volatile
        private var isInitialized = false
        
        init {
            setupAudioTrack()
        }
        
        private fun setupAudioTrack() {
            synchronized(trackLock) {
                try {
                    // Clean up existing track
                    audioTrack?.let { track ->
                        try {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                                track.stop()
                            }
                            track.release()
                        } catch (e: Exception) {
                            Log.w(TAG, "Error cleaning up previous AudioTrack for $sourceId", e)
                        }
                    }
                    
                    val bufferSize = AudioTrack.getMinBufferSize(
                        audioConfig.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        audioConfig.audioFormat
                    )
                    
                    if (bufferSize == AudioTrack.ERROR_BAD_VALUE || bufferSize == AudioTrack.ERROR) {
                        Log.e(TAG, "Invalid buffer size for AudioTrack: $bufferSize")
                        isInitialized = false
                        return
                    }
                    
                    audioTrack = AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        audioConfig.sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        audioConfig.audioFormat,
                        bufferSize * 4, // Larger buffer to prevent underruns
                        AudioTrack.MODE_STREAM
                    )
                    
                    val track = audioTrack
                    if (track?.state == AudioTrack.STATE_INITIALIZED) {
                        track.play()
                        isInitialized = true
                        Log.d(TAG, "AudioTrack initialized and started for $sourceId")
                    } else {
                        Log.e(TAG, "AudioTrack initialization failed for $sourceId, state: ${track?.state}")
                        isInitialized = false
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception setting up AudioTrack for $sourceId", e)
                    isInitialized = false
                }
            }
        }
        
        fun processAndPlay(audioData: ShortArray) {
            if (audioData.isEmpty()) {
                Log.w(TAG, "Empty audio data for $sourceId")
                return
            }
            
            synchronized(trackLock) {
                try {
                    val track = audioTrack
                    if (!isInitialized || track == null) {
                        Log.w(TAG, "AudioTrack not initialized for $sourceId, attempting to reinitialize")
                        setupAudioTrack()
                        return
                    }
                    
                    // Double-check track state after potential reinitialize
                    val currentTrack = audioTrack
                    if (currentTrack == null) {
                        Log.e(TAG, "AudioTrack is still null for $sourceId after reinitialize attempt")
                        return
                    }
                    
                    if (currentTrack.state != AudioTrack.STATE_INITIALIZED) {
                        Log.e(TAG, "AudioTrack not in initialized state for $sourceId, state: ${currentTrack.state}")
                        setupAudioTrack()
                        return
                    }
                    
                    if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING) {
                        Log.d(TAG, "Starting AudioTrack playback for $sourceId")
                        currentTrack.play()
                    }
                    
                    // Validate audio data size
                    if (audioData.size > 0 && audioData.size <= 8192) { // Reasonable size limit
                        val bytesWritten = currentTrack.write(audioData, 0, audioData.size)
                        if (bytesWritten > 0) {
                            Log.d(TAG, "Audio playback: wrote $bytesWritten samples for $sourceId")
                        } else {
                            Log.w(TAG, "AudioTrack write returned $bytesWritten for $sourceId")
                        }
                    } else {
                        Log.e(TAG, "Invalid audio data size: ${audioData.size} for $sourceId")
                    }
                    
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "IllegalStateException playing audio for $sourceId", e)
                    isInitialized = false
                    // Try to recover on next call
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error playing audio for $sourceId", e)
                    e.printStackTrace()
                    isInitialized = false
                }
            }
        }
        
        fun cleanup() {
            synchronized(trackLock) {
                isInitialized = false
                audioTrack?.let { track ->
                    try {
                        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                            track.stop()
                        }
                        track.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Error during AudioTrack cleanup for $sourceId", e)
                    }
                }
                audioTrack = null
            }
        }
    }
    
    // Simplified audio encoder/decoder classes
    private class AudioEncoder {
        fun encode(audioData: ShortArray): ByteArray {
            // In production, use WebRTC's Opus encoder
            // For now, just convert to bytes with proper endianness
            val byteBuffer = ByteBuffer.allocate(audioData.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            for (sample in audioData) {
                byteBuffer.putShort(sample)
            }
            return byteBuffer.array()
        }
    }
    
    private class AudioDecoder {
        fun decode(audioData: ByteArray): ShortArray {
            try {
                // In production, use WebRTC's Opus decoder
                // For now, just convert from bytes with proper endianness
                if (audioData.isEmpty()) {
                    return ShortArray(0)
                }
                
                // Ensure even number of bytes for proper short conversion
                val validSize = if (audioData.size % 2 == 0) audioData.size else audioData.size - 1
                if (validSize <= 0) {
                    return ShortArray(0)
                }
                
                val byteBuffer = ByteBuffer.wrap(audioData, 0, validSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val shortArray = ShortArray(validSize / 2)
                
                for (i in shortArray.indices) {
                    if (byteBuffer.hasRemaining()) {
                        shortArray[i] = byteBuffer.short
                    } else {
                        // This shouldn't happen with our size calculation, but be safe
                        shortArray[i] = 0
                        break
                    }
                }
                return shortArray
            } catch (e: Exception) {
                Log.e(TAG, "Error decoding audio data", e)
                return ShortArray(0)
            }
        }
    }
}