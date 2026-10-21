package com.feedback.security.sdk

import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.stereotype.Service

/** 验证 SDK 签名，并执行防重放和流量保护。 */
interface SdkRequestAuthenticator {
    /** 认证 Station 请求并返回受控主体；凭据缺失、UC 异常或产品不匹配时失败关闭。 */
    fun authenticate(
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodySha256: String,
        clientIp: String
    )
}

/**
 * SDK 请求安全门面。
 *
 * 这里可能访问 Redis，因此必须在数据库事务开启前调用，避免网络等待长期占用数据库连接。
 */
@Service
class DefaultSdkRequestAuthenticator(
    private val signatureVerifier: SignatureVerifier,
    private val nonceValidator: NonceValidator,
    private val rateLimiter: RateLimiter
) : SdkRequestAuthenticator {

    /** 认证 Station 请求并返回受控主体；凭据缺失、UC 异常或产品不匹配时失败关闭。 */
    override fun authenticate(
        appKey: String,
        signature: String,
        timestamp: String,
        nonce: String,
        method: String,
        path: String,
        bodySha256: String,
        clientIp: String
    ) {
        if (!signatureVerifier.verify(appKey, timestamp, nonce, signature, method, path, bodySha256)) {
            throw ClientException(ErrorCode.SDK_SIGNATURE_INVALID, "SDK_SIGNATURE_INVALID")
        }
        if (!nonceValidator.validateAndMark(nonce)) {
            throw ClientException(ErrorCode.SDK_NONCE_REPLAY, "SDK_NONCE_REPLAY")
        }
        if (!rateLimiter.allow(appKey, clientIp)) {
            throw ClientException(ErrorCode.SDK_RATE_LIMITED, "SDK_RATE_LIMITED")
        }
    }
}
