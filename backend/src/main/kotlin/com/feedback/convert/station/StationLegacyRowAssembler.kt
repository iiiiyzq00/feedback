package com.feedback.convert.station

import com.feedback.model.station.StationLegacyExportRow
import com.feedback.security.station.LegacyStationPhoneDecryptor
import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.model.po.station.legacy.StationLegacyIdentityPo
import com.feedback.service.masking.ContactStorageCrypto
import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.service.masking.MaskingPipeline
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/** Station 历史行安全组装器；事务外完成脱敏、手机号判定和身份加密，不访问持久化层。 */
@Component
class StationLegacyRowAssembler(
    private val masking: MaskingPipeline,
    private val phoneDecryptor: LegacyStationPhoneDecryptor,
    private val contactCrypto: ContactStorageCrypto,
    private val fingerprints: IdentityFingerprintService
) {
    /** 在事务外完成输入规范化、加密或文件准备，返回可由短事务安全提交的不可变结果。 */
    fun prepare(row: StationLegacyExportRow): StationLegacyPreparedRow {
        val title = masking.mask(row.title, "station_legacy_title_${row.recordId}")
        val content = masking.mask(row.content, "station_legacy_content_${row.recordId}")
        val description = row.sourceDescription?.let {
            masking.mask(it, "station_legacy_description_${row.recordId}")
        }
        if (!listOfNotNull(title.maskingStatus, content.maskingStatus, description?.maskingStatus).all { it == "PASS" }) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_MASKING_NOT_PASS")
        }
        return StationLegacyPreparedRow(row, title.maskedText, content.maskedText, description?.maskedText, phone(row.phone))
    }

    /** 规范化并校验历史手机号；仅合法号码进入加密身份流程，非法值返回受控决策。 */
    fun phone(value: String?): StationLegacyPhoneDecision {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank()) {
            return StationLegacyPhoneDecision.Missing
        }
        val decrypted = if (raw.matches(Regex("^1[3-9][0-9]{9}$"))) raw else try {
            if (!phoneDecryptor.available()) {
                throw ServiceException(ErrorCode.INTERNAL_ERROR, "STATION_LEGACY_PHONE_KEY_NOT_CONFIGURED")
            }
            phoneDecryptor.decrypt(raw)
        } catch (error: ServiceException) {
            throw error
        } catch (error: ClientException) {
            return StationLegacyPhoneDecision.Invalid(safePhoneError(error))
        }
        val normalized = runCatching { fingerprints.normalizeCanonical(decrypted, "PHONE") }.getOrElse {
            return StationLegacyPhoneDecision.Invalid("STATION_CONTACT_SOURCE_INVALID")
        }
        return if (normalized.matches(Regex("^1[3-9][0-9]{9}$"))) StationLegacyPhoneDecision.Valid(normalized)
        else StationLegacyPhoneDecision.Invalid("STATION_CONTACT_SOURCE_INVALID")
    }

    /** 组装 USER_ID 来源身份，保持类型 code 和来源记录关联稳定。 */
    fun userIdentity(sourceId: Long, rawUserId: String): StationLegacyIdentityPo = identity(sourceId, "USER_ID", rawUserId.trim())
    /** 对合法手机号执行指纹与加密后组装 PHONE 身份，不传播明文到持久化边界。 */
    fun phoneIdentity(sourceId: Long, phone: String): StationLegacyIdentityPo = identity(sourceId, "PHONE", phone)

    /** 组装通用 Station 身份记录；校验类型、授权依据和密文/指纹组合不变量。 */
    private fun identity(sourceId: Long, type: String, value: String): StationLegacyIdentityPo {
        val normalized = fingerprints.normalizeCanonical(value, type)
        val encrypted = contactCrypto.encrypt(normalized)
        return StationLegacyIdentityPo(
            sourceId = sourceId,
            identityType = type,
            fingerprint = fingerprints.fingerprintCanonical(normalized, type),
            fingerprintKeyId = fingerprints.keyId,
            contactCiphertext = encrypted.ciphertext,
            contactNonce = encrypted.nonce,
            contactTag = encrypted.tag,
            contactKeyId = contactCrypto.keyId,
            contactAlgorithm = "AES-256-GCM",
            consentedAt = LocalDateTime.now()
        )
    }

    /** 把手机号处理异常映射为稳定失败类别，不返回密钥、密文或原始号码。 */
    private fun safePhoneError(error: ClientException): String = error.reasonCode?.takeIf {
        it in setOf("STATION_PHONE_CIPHERTEXT_TOO_LONG", "STATION_PHONE_BASE64_INVALID", "STATION_PHONE_DECRYPT_FAILED")
    } ?: "STATION_CONTACT_SOURCE_INVALID"
}
