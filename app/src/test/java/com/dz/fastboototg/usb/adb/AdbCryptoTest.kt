package com.dz.fastboototg.usb.adb

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import org.junit.Assert.*
import org.junit.Test

class AdbCryptoTest {
    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    @Test
    fun `public key matches Android 524 byte little endian structure`() {
        val pair = keyPair()
        val pub = pair.public as RSAPublicKey
        val block = AdbCrypto.publicKeyBlock(pair)
        assertEquals(0.toByte(), block.last())
        val raw = Base64.getDecoder().decode(String(block, Charsets.US_ASCII).substringBefore(' '))
        assertEquals(524, raw.size)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(64, buffer.int)
        val inverse = buffer.int.toLong() and 0xffffffffL
        val modulus = ByteArray(256).also { buffer.get(it) }
        val rr = ByteArray(256).also { buffer.get(it) }
        assertEquals(pub.modulus, BigInteger(1, modulus.reversedArray()))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(pub.modulus), BigInteger(1, rr.reversedArray()))
        assertEquals(pub.publicExponent.toInt(), buffer.int)
        val wordBase = BigInteger.ONE.shiftLeft(32)
        assertEquals(wordBase.subtract(BigInteger.ONE), pub.modulus.multiply(BigInteger.valueOf(inverse)).mod(wordBase))
    }

    @Test
    fun `AUTH signs the supplied digest without hashing again`() {
        val pair = keyPair()
        val token = ByteArray(20) { it.toByte() }
        val signature = AdbCrypto.sign(pair, token)
        assertEquals(256, signature.size)
        val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        rsa.init(Cipher.DECRYPT_MODE, pair.public)
        val expectedPrefix = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        assertArrayEquals(expectedPrefix + token, rsa.doFinal(signature))
    }
}
