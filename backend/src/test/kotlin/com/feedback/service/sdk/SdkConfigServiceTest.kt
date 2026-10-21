package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import com.feedback.service.impl.sdk.SdkConfigServiceImpl
import com.feedback.common.exception.ClientException
import com.feedback.model.config.SystemConfig
import com.feedback.repository.SystemConfigRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files

class SdkConfigServiceTest {
    private val repository = mockk<SystemConfigRepository>()
    private val mapper = jacksonObjectMapper()
    private val transportKeys = mockk<TransportKeyService> {
        every { activePublicKeyPath } returns ""
        every { activeKeyId } returns "sdk-transport-v2"
    }
    private val service = SdkConfigServiceImpl(repository, mapper, transportKeys)

    @Test
    fun `returns active package configuration`() {
        val value = mapper.createObjectNode().apply {
            put("log_buffer_minutes", 10)
            put("log_max_size_kb", 1024)
            put("max_video_duration", 20)
            put("max_video_bitrate_kbps", 800)
            set<com.fasterxml.jackson.databind.node.ArrayNode>(
                "quick_issue_types", mapper.createArrayNode().add("功能异常")
            )
            put("float_window_enabled", false)
        }
        every {
            repository.findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc("sdk.config.com.example.app")
        } returns SystemConfig(configKey = "sdk.config.com.example.app", configVersion = "v2", configValue = value)

        val result = service.getConfig("com.example.app")
        assertEquals("v2", result["version"].asText())
        assertEquals(10, result["log_buffer_minutes"].asInt())
        assertEquals("功能异常", result["quick_issue_types"][0].asText())
    }

    @Test
    fun `rejects invalid package name`() {
        assertThrows(ClientException::class.java) { service.getConfig("../../etc/passwd") }
    }

    @Test
    fun `exposes only active transport public key and identifier`() {
        val publicKey = Files.createTempFile("sdk-transport-public", ".pem")
        Files.writeString(publicKey, "-----BEGIN PUBLIC KEY-----\nactive-public-key\n-----END PUBLIC KEY-----\n")
        val keys = mockk<TransportKeyService> {
            every { activePublicKeyPath } returns publicKey.toString()
            every { activeKeyId } returns "sdk-transport-v2"
        }
        every {
            repository.findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc("sdk.config.com.example.app")
        } returns null
        every {
            repository.findFirstByConfigKeyAndIsActiveTrueOrderByUpdatedAtDesc("sdk.config.default")
        } returns null

        val result = SdkConfigServiceImpl(repository, mapper, keys).getConfig("com.example.app")

        assertEquals("sdk-transport-v2", result["transport_key_id"].asText())
        assertEquals(Files.readString(publicKey), result["transport_public_key_pem"].asText())
        assertEquals(null, result["transport_private_key"])
        Files.deleteIfExists(publicKey)
    }
}
