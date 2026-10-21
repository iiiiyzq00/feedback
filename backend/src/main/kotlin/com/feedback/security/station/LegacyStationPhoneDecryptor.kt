package com.feedback.security.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Station 历史手机号解密器；密钥不可用或密文非法时失败关闭。 */
@Component
class LegacyStationPhoneDecryptor(
    @Value("\${app.station-feedback.legacy-phone-key:}") configuredKey: String
) {
    private val key = configuredKey.takeIf(String::isNotBlank)?.padEnd(16, '0')?.take(16)
        ?.toByteArray(Charsets.UTF_8)?.also {
            if (it.size != 16) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_PHONE_KEY_INVALID")
            }
        }

    /** 判断旧 Station 手机号解密器是否具备有效密钥；不可用时禁止尝试明文猜测。 */
    fun available() = key != null

    /** 解码或解密受控输入：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun decrypt(base64Ciphertext: String): String {
        val activeKey = key ?: throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_PHONE_KEY_NOT_CONFIGURED")
        if (base64Ciphertext.length > 1024) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_PHONE_CIPHERTEXT_TOO_LONG")
        }
        val encrypted = runCatching { Base64.getDecoder().decode(base64Ciphertext) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "STATION_PHONE_BASE64_INVALID", cause = it) }
        return try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(activeKey, "AES"))
            cipher.doFinal(encrypted).toString(Charsets.UTF_8)
        } catch (error: Exception) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_PHONE_DECRYPT_FAILED", cause = error)
        } finally {
            encrypted.fill(0)
        }
    }
}
