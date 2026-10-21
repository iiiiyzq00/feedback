package com.feedback.service.sdk

import com.fasterxml.jackson.databind.JsonNode

/** SDK 客户端配置查询契约；HTTP 层不依赖具体实现。 */
interface SdkConfigService {
    /** 返回指定包名的已校验配置和当前传输公钥。 */
    fun getConfig(packageName: String): JsonNode
}
