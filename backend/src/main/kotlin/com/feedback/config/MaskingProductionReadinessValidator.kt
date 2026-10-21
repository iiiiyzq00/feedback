package com.feedback.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.repository.config.SystemConfigRepository
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 生产启动前按固定四步单表证据链确认脱敏规则可用。
 *
 * 任一步缺失、重复、状态非法、过期或数据库异常都会阻止启动，不允许用默认值绕过安全门禁。
 */
@Component
@Profile("prod")
@Order(20)
class MaskingProductionReadinessValidator(
    private val repository: SystemConfigRepository,
    private val mapper: ObjectMapper = jacksonObjectMapper()
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val rule = repository.activeMaskingRuleVersion() ?: failed()
        val releaseVersion = mapper.readTree(rule.configValue).asText().takeIf(String::isNotBlank)
            ?: failed()

        val release = repository.maskingRelease(releaseVersion) ?: failed()
        if(release.recordStatus != "PUBLISHED" || release.recordVersion == null) failed()

        val run = repository.maskingEvidence("MASKING_RUN:${release.recordVersion}", "MASKING_RUN") ?: failed()
        if(run.recordStatus != "APPROVED" || run.configSubtype != "PASSED" || run.recordVersion == null) failed()
        val runValue = mapper.readTree(run.configValue)
        if(!SHA256.matches(runValue["executionLogSha256"]?.asText().orEmpty())) failed()
        if(!SHA256.matches(runValue["failureSummarySha256"]?.asText().orEmpty())) failed()

        val dataset = repository.maskingEvidence("MASKING_DATASET:${run.recordVersion}", "MASKING_DATASET") ?: failed()
        val datasetValue = mapper.readTree(dataset.configValue)
        if(dataset.configSubtype != "APPROVED_HISTORICAL" || dataset.recordStatus != "APPROVED") failed()
        if((dataset.recordVersion ?: 0) < 100 || (datasetValue["boundaryCount"]?.asInt(0) ?: 0) < 20) failed()
        if(dataset.effectiveTo?.isAfter(LocalDateTime.now()) != true) failed()
    }

    private fun failed():Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,ERROR)

    companion object {
        private const val ERROR = "PRODUCTION_MASKING_APPROVED_HISTORICAL_EVIDENCE_REQUIRED"
        private val SHA256 = Regex("^[a-f0-9]{64}$")
    }
}
