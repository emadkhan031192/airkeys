package com.airkeys

import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM, nonce derived from direction + seq (never transmitted).
 * AAD = type || seq (5 bytes). Layout on wire:
 * [uint16 length][type][seq][ciphertext][16-byte tag]
 * Ciphertext length == plaintext length (GCM is a stream mode here).
 */
object Crypto {
    const val NONCE_LEN = 12
    const val TAG_BITS = 128
    private val SALT = "AirKeys-v1".toByteArray(Charsets.UTF_8)

    fun deriveKeyFromCode(code: String): ByteArray {
        require(code.length == 6 && code.all { it.isDigit() }) { "Code must be 6 digits" }
        val spec = PBEKeySpec(code.toCharArray(), SALT, 100_000, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec)
        return key.encoded
    }

    fun nonce(direction: Byte, seq: Int): ByteArray {
        val n = ByteArray(NONCE_LEN)
        n[0] = direction
        ByteBuffer.wrap(n, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(seq)
        return n
    }

    private fun aad(type: Byte, seq: Int): ByteArray {
        val b = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
        b.put(type); b.putInt(seq); return b.array()
    }

    /** Returns the full wire frame, including the 2-byte length prefix. */
    fun encryptFrame(key: ByteArray, direction: Byte, type: Byte, seq: Int, payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(direction, seq)))
        cipher.updateAAD(aad(type, seq))
        val ctWithTag = cipher.doFinal(payload)
        val bodyLen = 1 + 4 + ctWithTag.size
        val out = ByteBuffer.allocate(2 + bodyLen).order(ByteOrder.LITTLE_ENDIAN)
        out.putShort(bodyLen.toShort()); out.put(type); out.putInt(seq); out.put(ctWithTag)
        return out.array()
    }

    /** Decrypts one frame body (type||seq||ciphertext||tag, without length prefix). */
    fun decryptFrame(key: ByteArray, direction: Byte, body: ByteArray): Frame {
        require(body.size >= 1 + 4 + 16) { "Frame too short" }
        val type = body[0]
        val seq = ByteBuffer.wrap(body, 1, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val ct = body.copyOfRange(5, body.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
            GCMParameterSpec(TAG_BITS, nonce(direction, seq)))
        cipher.updateAAD(aad(type, seq))
        val plain = cipher.doFinal(ct) // throws AEADBadTagException on tamper / wrong key
        return Frame(type, seq, plain)
    }
}
