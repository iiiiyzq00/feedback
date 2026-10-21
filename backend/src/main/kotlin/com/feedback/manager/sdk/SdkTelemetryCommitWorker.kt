package com.feedback.manager.sdk

import com.feedback.service.sdk.*

import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import com.feedback.repository.sdk.SdkTelemetryRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/** SDK 遥测短事务提交器；JSON 组装和字段裁剪已在事务外完成。 */
@Component
class SdkTelemetryCommitWorker(
    private val telemetry: SdkTelemetryRepository
) {
    /** 用一次有界批量 SQL 原子保存遥测事件。 */
    @Transactional
    fun commit(batch: List<SdkTransportTelemetryPo>) {
        telemetry.insertTransportEvents(batch)
    }
}
