package com.feedback.service.sdk

import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.strategy.sdk.*
import com.feedback.security.sdk.*
import com.feedback.manager.sdk.*
import com.feedback.manager.sdk.*

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import com.feedback.config.RuntimeConfigService
import com.feedback.config.SdkAuthProperties
import io.mockk.every
import io.mockk.mockk

class SignatureVerifierTest {
    private val now = Instant.parse("2026-07-27T00:00:00Z")
    private val secret = "test-secret"
    private val verifier = SignatureVerifier.forTest(secret, Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `accepts valid signature inside replay window`() {
        val timestamp = now.toEpochMilli().toString()
        val content = "POST/api/v1/sdk/feedback${timestamp}nonce-1body-hash"
        assertTrue(verifier.verify(
            "dev-app-key", timestamp, "nonce-1", hmac(content),
            "POST", "/api/v1/sdk/feedback", "body-hash"
        ))
    }

    @Test
    fun `rejects valid signature outside replay window`() {
        val timestamp = now.minusSeconds(301).toEpochMilli().toString()
        val content = "POST/api/v1/sdk/feedback${timestamp}nonce-1body-hash"
        assertFalse(verifier.verify(
            "dev-app-key", timestamp, "nonce-1", hmac(content),
            "POST", "/api/v1/sdk/feedback", "body-hash"
        ))
    }

    @Test fun `runtime replay window takes effect without restart`() {
        val runtime=mockk<RuntimeConfigService>();every{runtime.int("sdk.sign-window-seconds",300)} returns 60
        val properties=SdkAuthProperties().apply{appSecrets=mapOf("dev-app-key" to secret)}
        val dynamic=SignatureVerifier(properties,Clock.fixed(now,ZoneOffset.UTC),runtime)
        val timestamp=now.minusSeconds(61).toEpochMilli().toString();val content="POST/api/v1/sdk/feedback${timestamp}nonce-1body-hash"
        assertFalse(dynamic.verify("dev-app-key",timestamp,"nonce-1",hmac(content),"POST","/api/v1/sdk/feedback","body-hash"))
    }

    private fun hmac(content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(content.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
