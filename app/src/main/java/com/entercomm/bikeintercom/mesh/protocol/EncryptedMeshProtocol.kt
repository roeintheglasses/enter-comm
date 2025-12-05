package com.entercomm.bikeintercom.mesh.protocol

import com.entercomm.bikeintercom.mesh.MeshMessage
import com.entercomm.bikeintercom.util.logD
import com.entercomm.bikeintercom.util.logE
import com.entercomm.bikeintercom.util.logW
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted mesh protocol wrapper that adds AES-GCM encryption to any underlying protocol.
 *
 * Security features:
 * - AES-256-GCM authenticated encryption
 * - Random 12-byte IV per message (prepended to ciphertext)
 * - 128-bit authentication tag
 * - Key derived from group code using PBKDF2 with SHA-256
 *
 * Message format:
 * [4 bytes: magic header "ECRY"] [12 bytes: IV] [N bytes: ciphertext + auth tag]
 *
 * Usage:
 * ```kotlin
 * val encrypted = EncryptedMeshProtocol.fromGroupCode("ABCD1234")
 * // or
 * val encrypted = EncryptedMeshProtocol(secretKey, baseProtocol)
 * ```
 *
 * Note: All nodes in the mesh must use the same group code/key to communicate.
 */
class EncryptedMeshProtocol(
    private val secretKey: SecretKey,
    private val baseProtocol: MeshProtocol = PipeDelimitedMeshProtocol(),
) : MeshProtocol {

    companion object {
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val KEY_ALGORITHM = "AES"
        private const val KEY_DERIVATION_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val KEY_SIZE_BITS = 256
        private const val IV_SIZE_BYTES = 12
        private const val TAG_SIZE_BITS = 128
        private const val PBKDF2_ITERATIONS = 10000
        private val MAGIC_HEADER = byteArrayOf('E'.code.toByte(), 'C'.code.toByte(), 'R'.code.toByte(), 'Y'.code.toByte())
        private const val HEADER_SIZE = 4

        // Fixed salt for key derivation (in production, consider using a dynamic salt)
        private val KEY_DERIVATION_SALT = "EnterComm-Mesh-v1".toByteArray()

        private val secureRandom = SecureRandom()

        /**
         * Create an EncryptedMeshProtocol from a group code.
         * The group code is used to derive the encryption key using PBKDF2.
         *
         * @param groupCode The group code to derive the key from (4-8 alphanumeric chars)
         * @param baseProtocol The underlying protocol to use for serialization
         * @return EncryptedMeshProtocol instance
         */
        fun fromGroupCode(groupCode: String, baseProtocol: MeshProtocol = PipeDelimitedMeshProtocol()): EncryptedMeshProtocol {
            val key = deriveKeyFromGroupCode(groupCode)
            return EncryptedMeshProtocol(key, baseProtocol)
        }

        /**
         * Derive an AES-256 key from a group code using PBKDF2.
         */
        private fun deriveKeyFromGroupCode(groupCode: String): SecretKey {
            val spec = PBEKeySpec(
                groupCode.toCharArray(),
                KEY_DERIVATION_SALT,
                PBKDF2_ITERATIONS,
                KEY_SIZE_BITS,
            )
            val factory = SecretKeyFactory.getInstance(KEY_DERIVATION_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            return SecretKeySpec(keyBytes, KEY_ALGORITHM)
        }

        /**
         * Check if the given data appears to be encrypted (has magic header).
         */
        fun isEncrypted(data: ByteArray, length: Int): Boolean {
            if (length < HEADER_SIZE + IV_SIZE_BYTES + TAG_SIZE_BITS / 8) {
                return false
            }
            return data.slice(0 until HEADER_SIZE) == MAGIC_HEADER.toList()
        }
    }

    override val protocolVersion: String = "encrypted-v1+${baseProtocol.protocolVersion}"

    override val protocolDescription: String =
        "AES-256-GCM encrypted wrapper over ${baseProtocol.protocolDescription}"

    override fun serialize(message: MeshMessage): ByteArray {
        try {
            // First serialize with base protocol
            val plaintext = baseProtocol.serialize(message)

            // Generate random IV
            val iv = ByteArray(IV_SIZE_BYTES)
            secureRandom.nextBytes(iv)

            // Encrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
            val ciphertext = cipher.doFinal(plaintext)

            // Combine: magic header + IV + ciphertext (includes auth tag)
            val result = ByteArray(HEADER_SIZE + IV_SIZE_BYTES + ciphertext.size)
            System.arraycopy(MAGIC_HEADER, 0, result, 0, HEADER_SIZE)
            System.arraycopy(iv, 0, result, HEADER_SIZE, IV_SIZE_BYTES)
            System.arraycopy(ciphertext, 0, result, HEADER_SIZE + IV_SIZE_BYTES, ciphertext.size)

            logD { "Encrypted message: ${plaintext.size} -> ${result.size} bytes" }
            return result
        } catch (e: Exception) {
            logE({ "Encryption failed" }, e)
            throw e
        }
    }

    override fun deserialize(data: ByteArray, length: Int): MeshMessage? {
        try {
            // Validate minimum size
            val minSize = HEADER_SIZE + IV_SIZE_BYTES + TAG_SIZE_BITS / 8
            if (length < minSize) {
                logW { "Encrypted message too short: $length < $minSize" }
                return null
            }

            // Check magic header
            if (!isEncrypted(data, length)) {
                logW { "Missing encryption header - message not encrypted or corrupted" }
                return null
            }

            // Extract IV
            val iv = data.sliceArray(HEADER_SIZE until HEADER_SIZE + IV_SIZE_BYTES)

            // Extract ciphertext (includes auth tag)
            val ciphertext = data.sliceArray(HEADER_SIZE + IV_SIZE_BYTES until length)

            // Decrypt
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)
            val plaintext = cipher.doFinal(ciphertext)

            logD { "Decrypted message: $length -> ${plaintext.size} bytes" }

            // Deserialize with base protocol
            return baseProtocol.deserialize(plaintext, plaintext.size)
        } catch (e: javax.crypto.AEADBadTagException) {
            logW { "Decryption failed: authentication tag mismatch (wrong key or corrupted data)" }
            return null
        } catch (e: Exception) {
            logE({ "Decryption failed" }, e)
            return null
        }
    }
}
