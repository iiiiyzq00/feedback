package com.feedback.security.sdk

import com.feedback.service.sdk.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.service.masking.EnvelopeCrypto
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/** 解密已经完成格式拆分的 FBA2 附件负载。 */
interface SdkAttachmentDecryptor {
    /** 解码或解密受控输入：附件记录；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun decrypt(ciphertext: ByteArray, tag: ByteArray, nonce: ByteArray, wrappedKey: ByteArray): ByteArray
}

/**
 * FBA2 附件解密适配器。
 *
 * 缺少本地解密能力属于服务异常；客户端提供的封装密钥或密文无法解密属于客户端异常。
 * 临时密钥材料在所有分支中都会清零。
 */
@Component
class Fba2SdkAttachmentDecryptor(
    private val transportKeysProvider: ObjectProvider<TransportKeyService>,
    private val envelopeCryptoProvider: ObjectProvider<EnvelopeCrypto>
) : SdkAttachmentDecryptor {

    /** 解码或解密受控输入：附件记录；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    override fun decrypt(
        ciphertext: ByteArray,
        tag: ByteArray,
        nonce: ByteArray,
        wrappedKey: ByteArray
    ): ByteArray {
        val transportKeys = transportKeysProvider.ifAvailable
            ?: throw ServiceException(
                ErrorCode.SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE,
                "SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE"
            )
        val envelopeCrypto = envelopeCryptoProvider.ifAvailable
            ?: throw ServiceException(
                ErrorCode.SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE,
                "SDK_ATTACHMENT_DECRYPTION_UNAVAILABLE"
            )

        val key = try {
            transportKeys.unwrapDataKey(wrappedKey, TRANSPORT_KEY_ID)
        } catch (ex: Exception) {
            throw ClientException(ErrorCode.SDK_ATTACHMENT_KEY_INVALID, "SDK_ATTACHMENT_KEY_INVALID", cause = ex)
        }

        return try {
            envelopeCrypto.decrypt(ciphertext, tag, nonce, key)
        } catch (ex: Exception) {
            throw ClientException(ErrorCode.SDK_ENVELOPE_INVALID, "SDK_ATTACHMENT_DECRYPTION_FAILED", cause = ex)
        } finally {
            key.fill(0)
            wrappedKey.fill(0)
        }
    }

    private companion object {
        const val TRANSPORT_KEY_ID = "sdk-transport-v2"
    }
}
