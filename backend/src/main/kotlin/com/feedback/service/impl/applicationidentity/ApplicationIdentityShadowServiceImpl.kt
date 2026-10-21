package com.feedback.service.impl.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.CurrentWebPrincipalProvider
import com.feedback.model.applicationidentity.ApplicationIdentityShadowBatchResult
import com.feedback.service.applicationidentity.ApplicationIdentityShadowService
import com.feedback.manager.applicationidentity.ApplicationIdentityShadowWorker
import org.slf4j.MDC
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.util.UUID

/** 影子 Service 实现；鉴权、规则版本与有限重试在事务外完成。 */
@Service
class ApplicationIdentityShadowServiceImpl(
    private val worker: ApplicationIdentityShadowWorker,
    private val principalProvider: CurrentWebPrincipalProvider
) : ApplicationIdentityShadowService {
    /** 唯一键冲突表示并发批次竞争，完整回滚后最多重试一次安全编排。 */
    override fun process(
        afterSourceId: Long,
        limit: Int,
        resolutionRuleVersion: String
    ): ApplicationIdentityShadowBatchResult {
        val actor = principalProvider.require().userId
        if (resolutionRuleVersion != ApplicationIdentityShadowService.RESOLUTION_RULE_VERSION) {
            throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_RESOLUTION_RULE_VERSION_INVALID")
        }
        val requestId = MDC.get("requestId") ?: UUID.randomUUID().toString()
        return try {
            worker.process(afterSourceId, limit, resolutionRuleVersion, actor, requestId)
        } catch (_: DuplicateKeyException) {
            worker.process(afterSourceId, limit, resolutionRuleVersion, actor, requestId)
        }
    }
}
