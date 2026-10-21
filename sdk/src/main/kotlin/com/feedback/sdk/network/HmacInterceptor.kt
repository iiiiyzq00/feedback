package com.feedback.sdk.network

import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * OkHttp Interceptor — 自动注入 HMAC-SHA256 签名头
 *
 * X-App-Key: <appKey>
 * X-Timestamp: <unix_epoch_ms>
 * X-Nonce: <uuid_v4>
 * X-Signature: HMAC-SHA256(app_secret, METHOD+PATH+timestamp+nonce+sha256(body))
 */
class HmacInterceptor(
    private val appKey: String,
    private val appSecret: String
) : Interceptor {

    companion object {
        const val CANONICAL_BODY_SHA256_HEADER = "X-Canonical-Body-SHA256"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val timestamp = System.currentTimeMillis().toString()
        val nonce = UUID.randomUUID().toString()

        // 计算 body SHA-256
        val bodyBytes = original.body?.let { body ->
            val buffer = okio.Buffer()
            body.writeTo(buffer)
            buffer.readByteArray()
        } ?: ByteArray(0)

        val bodySha256 = original.header(CANONICAL_BODY_SHA256_HEADER)
            ?: MessageDigest.getInstance("SHA-256")
                .digest(bodyBytes)
                .joinToString("") { "%02x".format(it) }

        // 签名内容: METHOD + PATH + timestamp + nonce + sha256(body)
        val path = original.url.encodedPath
        val signContent = "${original.method}$path$timestamp$nonce$bodySha256"

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = mac.doFinal(signContent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val newRequest = original.newBuilder()
            .apply {
                // 重新设置 body（因为被读取过了）
                if (bodyBytes.isNotEmpty()) {
                    val mediaType = original.body?.contentType()
                    method(original.method, bodyBytes.toRequestBody(mediaType))
                }
            }
            .header("X-App-Key", appKey)
            .header("X-Timestamp", timestamp)
            .header("X-Nonce", nonce)
            .header("X-Signature", signature)
            .build()

        return chain.proceed(newRequest)
    }
}
