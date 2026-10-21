package com.feedback.security.station

import com.feedback.service.station.*
import com.feedback.model.station.*

/**
 * Station UC 认证外部端口；根据旧 token 协议解析终端用户。
 * 目标环境必须提供 Dubbo 实现，认证依赖不可用时失败关闭。
 */
fun interface StationUcAuthenticator {
    /** 认证 Station 请求并返回受控主体；凭据缺失、UC 异常或产品不匹配时失败关闭。 */
    fun authenticate(token: String, productCode: String, clientIp: String): String?
}
