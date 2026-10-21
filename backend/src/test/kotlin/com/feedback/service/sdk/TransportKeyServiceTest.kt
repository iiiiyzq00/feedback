package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.common.exception.ClientException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource

class TransportKeyServiceTest {
    @Test
    fun `selects matching private key during rotation and rejects unknown key id`() {
        val v1 = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val v2 = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val v1Path = writePrivateKey(v1.private.encoded)
        val v2Path = writePrivateKey(v2.private.encoded)
        val service = TransportKeyService(
            "", "sdk-transport-v1=$v1Path,sdk-transport-v2=$v2Path",
            "sdk-transport-v2", ""
        )
        val dataKey = ByteArray(32) { it.toByte() }

        assertArrayEquals(dataKey, service.unwrapDataKey(wrap(dataKey, v1.public), "sdk-transport-v1"))
        assertArrayEquals(dataKey, service.unwrapDataKey(wrap(dataKey, v2.public), "sdk-transport-v2"))
        assertThrows(ClientException::class.java) {
            service.unwrapDataKey(wrap(dataKey, v2.public), "sdk-transport-retired")
        }
        Files.deleteIfExists(v1Path)
        Files.deleteIfExists(v2Path)
    }

    private fun writePrivateKey(encoded: ByteArray) = Files.createTempFile("sdk-private", ".pem").also {
        Files.writeString(it, "-----BEGIN PRIVATE KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(encoded)}\n-----END PRIVATE KEY-----\n")
    }

    private fun wrap(dataKey: ByteArray, publicKey: java.security.PublicKey): ByteArray =
        Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
            init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal(dataKey)
        }
}
