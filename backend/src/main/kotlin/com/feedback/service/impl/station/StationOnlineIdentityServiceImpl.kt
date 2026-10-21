package com.feedback.service.impl.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.station.online.StationOnlineIdentityPo
import com.feedback.repository.station.online.StationOnlineIdentityRepository
import com.feedback.service.masking.ContactStorageCrypto
import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.service.station.StationOnlineIdentityService
import com.feedback.model.station.PreparedStationIdentity
import com.feedback.model.station.StationIdentityType
import org.springframework.stereotype.Service
import java.time.LocalDateTime

/**
 * 为 Station Online 准备并保存受控身份事实。
 *
 * 身份规范化、指纹和加密继续复用公共安全组件；唯一键冲突保持 first-write-wins，
 * 不在业务代码中读取或比较联系方式明文。
 */
@Service
class StationOnlineIdentityServiceImpl(
    private val repository: StationOnlineIdentityRepository,
    private val contactCrypto: ContactStorageCrypto,
    private val fingerprints: IdentityFingerprintService
) : StationOnlineIdentityService {
    /** 校验授权来源与手机号格式后，按 first-write-wins 保存密文身份。 */
    override fun storePhone(sourceId: Long, rawPhone: String, authorizationBasis: String): Boolean {
        return storePrepared(sourceId, preparePhone(rawPhone, authorizationBasis))
    }

    /** 在事务外完成手机号校验、规范化、指纹和加密。 */
    override fun preparePhone(rawPhone: String, authorizationBasis: String): PreparedStationIdentity {
        if (authorizationBasis !in setOf("SOURCE_V2_REQUEST", "UC_USER_LOOKUP", "ADAPTER_RESOLVED")) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_PHONE_AUTHORIZATION_INVALID")
        }
        val normalized = fingerprints.normalize(rawPhone, "PHONE")
        if (!normalized.matches(Regex("^1[3-9][0-9]{9}$"))) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_PHONE_FORMAT_INVALID")
        }
        return prepare(StationIdentityType.PHONE, normalized)
    }

    /** 规范化来源用户 ID 后保存受控密文身份。 */
    override fun storeUserId(sourceId: Long, rawUserId: String): Boolean {
        val normalized = rawUserId.trim()
        if (normalized.isBlank() || normalized.length > 200) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_USER_ID_FORMAT_INVALID")
        }
        return storePrepared(sourceId, prepare(StationIdentityType.USER_ID, normalized))
    }

    /** 在短事务内只映射并保存密文身份，不执行密码学或明文比较。 */
    override fun storePrepared(sourceId: Long, identity: PreparedStationIdentity): Boolean {
        return repository.insertFirstWins(StationOnlineIdentityPo(
            sourceId = sourceId,
            identityType = identity.identityType.code,
            fingerprint = identity.fingerprint,
            fingerprintKeyId = identity.fingerprintKeyId,
            contactCiphertext = identity.contactCiphertext,
            contactNonce = identity.contactNonce,
            contactTag = identity.contactTag,
            contactKeyId = identity.contactKeyId,
            contactAlgorithm = "AES-256-GCM",
            contactAuthorizationBasis = "CHANNEL_GOVERNANCE",
            consentedAt = identity.consentedAt,
            expiresAt = identity.expiresAt
        ))
    }

    /** 构造只含密文和稳定元数据的身份写模型。 */
    private fun prepare(type: StationIdentityType, normalized: String): PreparedStationIdentity {
        val encrypted = contactCrypto.encrypt(normalized)
        return PreparedStationIdentity(
            identityType = type,
            fingerprint = fingerprints.fingerprint(normalized, type.code),
            fingerprintKeyId = fingerprints.keyId,
            contactCiphertext = encrypted.ciphertext,
            contactNonce = encrypted.nonce,
            contactTag = encrypted.tag,
            contactKeyId = contactCrypto.keyId,
            consentedAt = LocalDateTime.now(),
            expiresAt = LocalDateTime.of(2099, 12, 31, 23, 59, 59)
        )
    }
}
