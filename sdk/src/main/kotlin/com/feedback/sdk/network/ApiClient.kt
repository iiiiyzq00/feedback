package com.feedback.sdk.network

import com.feedback.sdk.config.SDKConfig
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * API 客户端 — OkHttp + Retrofit 单例
 */
object ApiClient {

    private var api: FeedbackApi? = null

    fun init(config: SDKConfig) {
        // HMAC 签名用 appSecret（独立于 appKey）
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(HmacInterceptor(config.appKey, config.appSecret))
            .build()

        api = Retrofit.Builder()
            .baseUrl(config.serverUrl.removeSuffix("/") + "/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FeedbackApi::class.java)
    }

    fun get(): FeedbackApi {
        checkNotNull(api) { "ApiClient not initialized. Call FeedbackSDK.init() first." }
        return api!!
    }
}
