package com.feedback.testinfra

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

object SdkTestEnvelope {
    const val APP_KEY = "course"
    const val APP_SECRET = "integration-course-secret-32-characters"
    const val TRANSPORT_KEY_ID = "sdk-transport-v2"
    val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val privateKeyFile: Path = Files.createTempFile("d9-sdk-private-", ".pem").also { path ->
        path.toFile().deleteOnExit()
        val encoded = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        Files.writeString(path, "-----BEGIN PRIVATE KEY-----\n$encoded\n-----END PRIVATE KEY-----\n")
    }

    fun request(mapper: ObjectMapper, clientId: String, description: String,
        packageName: String = "com.company.tutor", appVersion: String = "3.2.1",
        functionModule: String = "AI_TUTOR", issueType: String = "CRASH",
        sourceEventTime: String = "2026-08-04T02:00:00Z", deviceModel: String = "XT11A-MOCK",
        deviceSn: String = "MOCK-SN-000001", networkType: String = "WIFI",
        triggerType: String = "LAUNCHER"): String {
        val dataKey = ByteArray(32).also(SecureRandom()::nextBytes)
        val body = mapper.writeValueAsBytes(mapOf(
            "description" to description,
            "input_methods" to listOf("TEXT"),
            "business_context" to mapOf("device_model" to deviceModel, "device_sn" to deviceSn)
        ))
        val encrypted = aes(body, dataKey)
        val wrapped = wrap(dataKey, keyPair.public)
        dataKey.fill(0); body.fill(0)
        return mapper.writeValueAsString(linkedMapOf(
            "client_feedback_id" to clientId,
            "package_name" to packageName,
            "app_version" to appVersion,
            "function_module" to functionModule,
            "issue_type" to issueType,
            "source_event_time" to sourceEventTime,
            "os_version" to "Android 14",
            "network_type" to networkType,
            "trigger_type" to triggerType,
            "encrypted_payload" to mapOf(
                "key_id" to TRANSPORT_KEY_ID, "algorithm" to "AES-256-GCM+RSA-OAEP-256",
                "encrypted_data_key" to b64(wrapped), "nonce" to b64(encrypted.nonce),
                "ciphertext" to b64(encrypted.ciphertext), "tag" to b64(encrypted.tag)
            ),
            "encrypted_contact_payload" to null,
            "attachment_manifest" to emptyList<Map<String, Any>>()
        ))
    }

    fun signature(body: String, timestamp: String, nonce: String): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        val content = "POST/api/v1/sdk/feedback$timestamp$nonce$hash"
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(APP_SECRET.toByteArray(), "HmacSHA256"))
            doFinal(content.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun aes(plaintext: ByteArray, key: ByteArray): AesValue {
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val output = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            doFinal(plaintext)
        }
        return AesValue(output.copyOfRange(0, output.size - 16), nonce, output.copyOfRange(output.size - 16, output.size))
    }

    private fun wrap(key: ByteArray, publicKey: PublicKey): ByteArray = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding").run {
        init(Cipher.ENCRYPT_MODE, publicKey, OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
        doFinal(key)
    }

    private fun b64(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private data class AesValue(val ciphertext: ByteArray, val nonce: ByteArray, val tag: ByteArray)
}
