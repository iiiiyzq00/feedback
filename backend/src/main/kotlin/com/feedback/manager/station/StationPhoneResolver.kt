package com.feedback.manager.station

import com.feedback.repository.remote.station.StationUserDirectory
import com.feedback.security.station.LegacyStationPhoneDecryptor
import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import org.springframework.stereotype.Service

/** Station 联系方式解析器；按来源版本选择适配器输入或授权 UC 查询。 */
@Service
class StationPhoneResolver(
    private val decryptor: LegacyStationPhoneDecryptor,
    private val users: StationUserDirectory
) {
    /** 按来源模式解析 Station 联系方式；适配器输入和 UC 查询使用不同授权依据，失败时不降级猜测号码。 */
    fun resolve(version: String, suppliedCiphertext: String?, authenticatedUserId: String?, productCode: String): ResolvedStationPhone? {
        val supplied = suppliedCiphertext.orEmpty()
        if (version == "V2" && supplied.isNotBlank()) {
            return ResolvedStationPhone(decryptor.decrypt(supplied), "SOURCE_V2_REQUEST")
        }
        if (authenticatedUserId.isNullOrBlank()) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_UC_USER_ID_MISSING")
        }
        return users.findPhone(authenticatedUserId, productCode)?.takeIf(String::isNotBlank)
            ?.let { ResolvedStationPhone(it, "UC_USER_LOOKUP") }
    }
}
