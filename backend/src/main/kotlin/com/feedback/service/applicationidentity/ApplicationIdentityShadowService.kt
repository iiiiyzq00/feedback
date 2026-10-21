package com.feedback.service.applicationidentity

import com.feedback.model.applicationidentity.*

/** 管理员触发的应用身份影子批次 Service；不会修改活动归簇或页面投影。 */
interface ApplicationIdentityShadowService {
    /** 处理一个有界来源批次；唯一键竞争最多安全重试一次。 */
    fun process(
        afterSourceId: Long,
        limit: Int,
        resolutionRuleVersion: String
    ): ApplicationIdentityShadowBatchResult

    companion object {
        const val RESOLUTION_RULE_VERSION = "application-identity-v1"
    }
}
