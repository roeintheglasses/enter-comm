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
            // initializeWebRTC() // Temporarily commented out
            setupAudioCapture()
            setupAudioPlayback()
            Log.d(TAG, "AudioManager initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize AudioManager", e)
            throw e
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
                val processor = audioProcessors.getOrPut(sourceId) {
                    AudioProcessor(sourceId)
                }
                
                val decodedAudio = audioDecoder.decode(audioData)
                processor.processAndPlay(decodedAudio)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error playing audio from $sourceId", e)
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
        private var audioTrack: AudioTrack? = null
        
        init {
            setupAudioTrack()
        }
        
        private fun setupAudioTrack() {
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
        
        fun processAndPlay(audioData: ShortArray) {
            audioTrack?.write(audioData, 0, audioData.size)
        }
        
        fun cleanup() {
            audioTrack?.stop()
            audioTrack?.release()
        }
    }
    
    // Simplified audio encoder/decoder classes
    private class AudioEncoder {
        fun encode(audioData: ShortArray): ByteArray {
            // In production, use WebRTC's Opus encoder
            // For now, just convert to bytes
            val byteBuffer = ByteBuffer.allocate(audioData.size * 2)
            for (sample in audioData) {
                byteBuffer.putShort(sample)
            }
            return byteBuffer.array()
        }
    }
    
    private class AudioDecoder {
        fun decode(audioData: ByteArray): ShortArray {
            // In production, use WebRTC's Opus decoder
            // For now, just convert from bytes
            val byteBuffer = ByteBuffer.wrap(audioData)
            val shortArray = ShortArray(audioData.size / 2)
            for (i in shortArray.indices) {
                shortArray[i] = byteBuffer.short
            }
            return shortArray
        }
    }
}