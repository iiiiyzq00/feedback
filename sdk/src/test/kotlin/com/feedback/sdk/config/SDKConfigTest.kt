package com.feedback.sdk.config

import org.junit.Assert.assertThrows
import org.junit.Test

class SDKConfigTest {
    @Test
    fun `rejects public app key as signing secret`() {
        assertThrows(IllegalArgumentException::class.java) {
            SDKConfig("app.id", "same-secret-value", "https://feedback.example.com", "same-secret-value")
        }
    }

    @Test
    fun `rejects cleartext non emulator endpoint`() {
        assertThrows(IllegalArgumentException::class.java) {
            SDKConfig("app.id", "course", "http://feedback.example.com", "a-secure-local-secret")
        }
    }

    @Test
    fun `allows private network only with explicit test switch`() {
        SDKConfig(
            "app.id", "course", "http://192.168.3.140:18080", "a-secure-local-secret",
            allowInsecurePrivateNetworkForTesting = true
        )
        assertThrows(IllegalArgumentException::class.java) {
            SDKConfig("app.id", "course", "http://192.168.3.140:18080", "a-secure-local-secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SDKConfig(
                "app.id", "course", "http://feedback.example.com", "a-secure-local-secret",
                allowInsecurePrivateNetworkForTesting = true
            )
        }
    }
}
