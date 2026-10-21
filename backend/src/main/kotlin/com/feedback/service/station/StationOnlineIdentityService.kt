package com.feedback.service.station

import com.feedback.model.station.PreparedStationIdentity

/** Station 在线身份安全 Service；只暴露规范化前输入，不传播密文 PO。 */
interface StationOnlineIdentityService {
    /** 在事务外校验、规范化并加密手机号。 */
    fun preparePhone(rawPhone: String, authorizationBasis: String): PreparedStationIdentity

    /** 在短事务内按 first-write-wins 保存预加密身份。 */
    fun storePrepared(sourceId: Long, identity: PreparedStationIdentity): Boolean

    /** 按明确授权依据保存手机号，唯一键冲突保持首次写入获胜。 */
    fun storePhone(sourceId: Long, rawPhone: String, authorizationBasis: String): Boolean

    /** 保存来源用户标识，唯一键冲突保持首次写入获胜。 */
    fun storeUserId(sourceId: Long, rawUserId: String): Boolean
}
