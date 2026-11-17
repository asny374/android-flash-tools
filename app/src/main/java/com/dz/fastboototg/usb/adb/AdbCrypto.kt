package com.dz.fastboototg.usb.adb

import com.dz.fastboototg.core.log.AppLogger
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Persistent RSA-2048 keys using the Android ADB public-key format. */
object AdbCrypto {

    private const val KEY_FILE = "adbkey.pk8"
    private const val PUB_FILE = "adbkey.pub"
    private const val KEY_SIZE = 2048

    fun loadOrCreate(filesDir: File): KeyPair {
        val privFile = File(filesDir, KEY_FILE)
        val pubFile = File(filesDir, PUB_FILE)

        if (privFile.exists() && pubFile.exists()) {
            runCatching { return load(privFile, pubFile) }
                .onFailure { AppLogger.w("ADB 密钥文件损坏，重新生成：${it.message}") }
        }

        val generator = java.security.KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE)
        val pair = generator.generateKeyPair()

        privFile.writeText(Base64.getEncoder().encodeToString(pair.private.encoded))
        pubFile.writeText(Base64.getEncoder().encodeToString(pair.public.encoded))
        AppLogger.i("已生成新的 ADB 密钥（RSA-2048）")
        return pair
    }

    private fun load(privFile: File, pubFile: File): KeyPair {
        val factory = KeyFactory.getInstance("RSA")
        val priv = factory.generatePrivate(
            PKCS8EncodedKeySpec(Base64.getDecoder().decode(privFile.readText()))
        )
        val pub = factory.generatePublic(
            X509EncodedKeySpec(Base64.getDecoder().decode(pubFile.readText()))
        )
        return KeyPair(pub, priv)
    }

    /** The AUTH token is already a SHA-1 digest; do not hash it a second time. */
    fun sign(keyPair: KeyPair, token: ByteArray): ByteArray {
        require(token.size == 20) { "ADB AUTH token must be 20 bytes" }
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )
        val signer = Signature.getInstance("NONEwithRSA")
        signer.initSign(keyPair.private)
        signer.update(digestInfo + token)
        return signer.sign()
    }

    /** base64(Android RSAPublicKey struct) followed by a comment and NUL. */
    fun publicKeyBlock(keyPair: KeyPair, userHost: String = "workbuddy@otg"): ByteArray {
        val pub = keyPair.public as java.security.interfaces.RSAPublicKey
        require(pub.modulus.bitLength() == KEY_SIZE)
        val wordBase = java.math.BigInteger.ONE.shiftLeft(32)
        val n0inv = wordBase.subtract(pub.modulus.modInverse(wordBase)).toInt()
        val rr = java.math.BigInteger.ONE.shiftLeft(KEY_SIZE * 2).mod(pub.modulus)
        fun littleEndian(value: java.math.BigInteger): ByteArray {
            val bytes = value.toByteArray().reversedArray()
            return bytes.copyOf(KEY_SIZE / 8)
        }
        val encoded = java.nio.ByteBuffer.allocate(524)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putInt(KEY_SIZE / 32)
            .putInt(n0inv)
            .put(littleEndian(pub.modulus))
            .put(littleEndian(rr))
            .putInt(pub.publicExponent.toInt())
            .array()
        return (Base64.getEncoder().encodeToString(encoded) + " " + userHost + "\u0000")
            .toByteArray(Charsets.US_ASCII)
    }
}
