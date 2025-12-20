package com.entercomm.bikeintercom.mesh.protocol

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Encodes node ID strings to compact 8-byte values for the binary protocol.
 *
 * Uses SHA-256 hashing truncated to 8 bytes (64 bits) for compact representation.
 * Maintains a bidirectional cache for encoding/decoding without repeated hashing.
 *
 * Special reserved values:
 * - BROADCAST_ID (0xFFFFFFFFFFFFFFFF): Represents broadcast destination
 *
 * Thread-safe: All operations use ConcurrentHashMap for concurrent access.
 */
object NodeIdEncoder {

    /**
     * Special ID representing broadcast destination.
     * Binary: all 1s (0xFFFFFFFFFFFFFFFF)
     */
    const val BROADCAST_ID: Long = -1L

    /**
     * The string representation of broadcast destination.
     */
    private const val BROADCAST_STRING = "broadcast"

    /**
     * Maximum cache size to prevent unbounded memory growth.
     */
    private const val MAX_CACHE_SIZE = 1000

    // Forward cache: nodeId string -> encoded long
    private val encodeCache = ConcurrentHashMap<String, Long>()

    // Reverse cache: encoded long -> nodeId string
    private val decodeCache = ConcurrentHashMap<Long, String>()

    // Thread-local MessageDigest to avoid synchronization overhead
    private val digestThreadLocal = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-256")
    }

    /**
     * Encode a node ID string to an 8-byte long value.
     *
     * Special handling:
     * - "broadcast" returns BROADCAST_ID
     * - All other strings are hashed with SHA-256, taking first 8 bytes
     *
     * Results are cached for fast repeated lookups.
     *
     * @param nodeId The node ID string to encode
     * @return 8-byte encoded value as Long
     */
    fun encode(nodeId: String): Long {
        // Handle special broadcast value
        if (nodeId == BROADCAST_STRING) {
            return BROADCAST_ID
        }

        // Check cache first
        encodeCache[nodeId]?.let { return it }

        // Compute SHA-256 hash
        val digest = digestThreadLocal.get()
        digest.reset()
        val hash = digest.digest(nodeId.toByteArray(Charsets.UTF_8))

        // Take first 8 bytes as big-endian long
        val encoded = ByteBuffer.wrap(hash, 0, 8).long

        // Handle collision with reserved values
        val finalEncoded = if (encoded == BROADCAST_ID) {
            // Extremely unlikely, but handle by flipping lowest bit
            encoded xor 1L
        } else {
            encoded
        }

        // Cache the result (with size limit check)
        if (encodeCache.size < MAX_CACHE_SIZE) {
            encodeCache[nodeId] = finalEncoded
            decodeCache[finalEncoded] = nodeId
        }

        return finalEncoded
    }

    /**
     * Decode an 8-byte encoded value back to the original node ID string.
     *
     * Special handling:
     * - BROADCAST_ID returns "broadcast"
     * - Other values are looked up in the cache
     *
     * @param encoded The encoded 8-byte value
     * @return Original node ID string, or null if not in cache
     */
    fun decode(encoded: Long): String? {
        // Handle special broadcast value
        if (encoded == BROADCAST_ID) {
            return BROADCAST_STRING
        }

        return decodeCache[encoded]
    }

    /**
     * Register a node ID in the cache.
     *
     * Call this when receiving DISCOVERY messages to populate the reverse cache.
     * This ensures decode() can recover the original node ID string.
     *
     * @param nodeId The node ID string to register
     * @return The encoded value for the node ID
     */
    fun register(nodeId: String): Long {
        return encode(nodeId) // encode() already populates both caches
    }

    /**
     * Check if a node ID is already registered in the cache.
     *
     * @param nodeId The node ID to check
     * @return true if the node ID is in the encode cache
     */
    fun isRegistered(nodeId: String): Boolean {
        return encodeCache.containsKey(nodeId)
    }

    /**
     * Clear all cached encodings.
     * Primarily useful for testing.
     */
    fun clearCache() {
        encodeCache.clear()
        decodeCache.clear()
    }

    /**
     * Get the current cache size.
     * Primarily useful for testing and debugging.
     */
    fun cacheSize(): Int = encodeCache.size
}
