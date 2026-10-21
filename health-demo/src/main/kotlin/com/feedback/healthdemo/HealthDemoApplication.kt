package com.feedback.healthdemo

import android.app.Application
import android.util.Base64
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.config.SDKConfig

class HealthDemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FeedbackSDK.init(
            this,
            SDKConfig(
                appId = BuildConfig.SDK_APP_ID,
                appKey = BuildConfig.SDK_APP_KEY,
                appSecret = BuildConfig.SDK_APP_SECRET,
                serverUrl = BuildConfig.SDK_SERVER_URL,
                serverPublicKeyPem = publicKeyPem(),
                allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
            )
        )
        FeedbackSDK.setUser("SIMULATED_HEALTH_USER_001")
        FeedbackSDK.setContact(phone = "13800138001", email = "health.demo@example.com")
    }

    private fun publicKeyPem(): String? = BuildConfig.SDK_SERVER_PUBLIC_KEY_BASE64
        .takeIf(String::isNotBlank)
        ?.let { Base64.decode(it, Base64.DEFAULT).toString(Charsets.UTF_8) }
        ?.takeIf { it.contains("BEGIN PUBLIC KEY") && it.contains("END PUBLIC KEY") }
}
