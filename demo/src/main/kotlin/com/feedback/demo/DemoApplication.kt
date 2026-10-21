package com.feedback.demo

import android.app.Application
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.config.SDKConfig

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FeedbackSDK.init(
            context = this,
            config = SDKConfig(
                appId = "com.feedback.demo",
                appKey = "course",
                appSecret = BuildConfig.COURSE_SDK_SECRET,
                serverUrl = BuildConfig.SDK_SERVER_URL,
                serverPublicKeyPem = TransportPublicKey.pemOrNull(),
                allowInsecurePrivateNetworkForTesting = BuildConfig.DEBUG
            )
        )
        FeedbackSDK.setUser("demo-user-001")
    }
}
