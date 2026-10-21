package com.feedback.repository.impl.rca

import com.feedback.agent.rca.intake.RcaIntakeCandidate
import com.feedback.agent.rca.intake.RcaIntakeCandidatePort
import com.feedback.mapper.rca.RcaIntakeCandidateMapper
import org.springframework.stereotype.Repository

/**
 * 接入候选读取的 MyBatis 实现。
 *
 * 位置在 `repository/impl/rca/` 而不是 `agent/rca/`：本类直接依赖
 * `com.feedback.mapper.rca.RcaIntakeCandidateMapper`，属于数据访问实现。
 * 把它留在 `agent/rca/` 里会让这个运行时依赖 MyBatis，而
 * `agent/rca/` 的离线回放（三个 Gradle `JavaExec` 任务）刻意不连库。
 *
 * 本类**不自行开事务**：它只做一次只读查询，接入的事务边界在
 * `EpisodeIntakeService.accept` 上，那里才是「问题对象、成员、工作项、任务必须同时生效」的地方。
 */
@Repository
class MyBatisRcaIntakeCandidateRepositoryImpl(
    private val mapper: RcaIntakeCandidateMapper
) : RcaIntakeCandidatePort {

    /** 按游标取一批候选；这里不做过滤，`masking_status` 与 `data_environment` 已在 SQL 里。 */
    override fun findAfter(
        afterSourceId: Long,
        dataEnvironment: String,
        limit: Int
    ): List<RcaIntakeCandidate> =
        mapper.findIntakeCandidatesAfter(afterSourceId, dataEnvironment, limit).map { row ->
            RcaIntakeCandidate(
                sourceId = row.sourceId,
                sourceChannel = row.sourceChannel,
                sourceRecordId = row.sourceRecordId,
                eventTime = row.sourceEventTime,
                problemType = row.problemType,
                title = row.contentTitle,
                description = row.contentDescription,
                appPublicIdHex = row.appPublicIdHex,
                tenantKey = row.tenantKey
            )
        }
}
