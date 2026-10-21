package com.feedback.repository.impl.auth

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthFeishuOrgFactMapper
import com.feedback.mapper.auth.AuthFeishuSyncRunMapper
import com.feedback.mapper.auth.AuthPolicyDataPermissionMapper
import com.feedback.model.auth.DataScopeType
import com.feedback.model.auth.FeishuSyncStatus
import com.feedback.model.auth.FeishuOrganizationUserStatus
import com.feedback.model.po.auth.AuthFeishuSyncRunPo
import com.feedback.repository.auth.FeishuPolicyRepository
import com.feedback.repository.auth.FeishuPolicyScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/**
 * MyBatis 飞书授权策略仓储实现。
 *
 * 启动锁依赖当前短事务连接并在完成回调中释放；用户范围删除重建由上层单用户事务覆盖。
 * 所有集合操作有上层身份数量边界，不在本层新增 N+1 或无界扫描。
 */
@Repository
class MyBatisFeishuPolicyRepositoryImpl(
    private val runs: AuthFeishuSyncRunMapper,
    private val scopes: AuthPolicyDataPermissionMapper,
    private val facts: AuthFeishuOrgFactMapper,
) : FeishuPolicyRepository {
    /** 读取最近一次同步运行，不启动同步或访问飞书。 */
    override fun latestRun() = runs.findLatest()

    /** 创建 RUNNING 运行记录并返回数据库生成 ID；影响行数或主键异常时失败关闭。 */
    override fun start(actor: Long): Long {
        val run = AuthFeishuSyncRunPo(runStatus = FeishuSyncStatus.RUNNING.code, requestedBy = actor)
        persisted(runs.insert(run) == 1, "FEISHU_SYNC_RUN_INSERT_FAILED")
        return run.id ?: failed("FEISHU_SYNC_RUN_ID_MISSING")
    }

    /**
     * 在现有短事务连接上获取 MySQL 命名锁，拒绝并发运行并原子过期陈旧任务。
     * 命名锁只等待固定秒数，且注册事务完成回调保证最终释放。
     */
    override fun startExclusive(actor: Long, staleBefore: LocalDateTime): Long {
        persisted(TransactionSynchronizationManager.isActualTransactionActive(), "FEISHU_SYNC_START_TRANSACTION_REQUIRED")
        if (runs.acquireStartLock(START_LOCK_NAME, START_LOCK_TIMEOUT_SECONDS) != 1) {
            syncConflict()
        }
        releaseStartLockAfterTransaction()
        val activeRuns = runs.findRunning()
        if (activeRuns.any { it.startedAt?.isBefore(staleBefore) != true }) {
            syncConflict()
        }
        activeRuns.forEach { active ->
            val activeId = active.id ?: failed("FEISHU_SYNC_ACTIVE_RUN_ID_MISSING")
            persisted(runs.expireRunning(activeId, STALE_ERROR_CODE) == 1, "FEISHU_SYNC_STALE_EXPIRY_CONFLICT")
        }
        return start(actor)
    }

    /** 以单条条件写入终态与计数；未命中说明运行状态冲突并触发事务回滚。 */
    override fun finish(
        id: Long,
        status: FeishuSyncStatus,
        total: Int,
        changed: Int,
        disabled: Int,
        failed: Int,
        errorCode: String?,
    ) {
        persisted(
            runs.finish(id, status.code, total, changed, disabled, failed, errorCode) == 1,
            "FEISHU_SYNC_RUN_FINISH_FAILED",
        )
    }

    /** 读取飞书策略来源的数据范围并严格解析存储 code。 */
    override fun scopes(userId: Long): Set<FeishuPolicyScope> =
        scopes.findFeishuPolicyScopes(userId.toString()).mapTo(linkedSetOf()) {
            FeishuPolicyScope(DataScopeType.requireStorage(it.scopeType), it.scopeValue)
        }

    /** 删除后重建单用户飞书数据范围；由上层单用户授权事务保证原子性。 */
    override fun replaceScopes(userId: Long, values: Set<FeishuPolicyScope>) {
        scopes.deleteFeishuPolicyScopes(userId.toString())
        values.forEach { scope ->
            persisted(
                scopes.insertFeishuPolicyScope(userId.toString(), scope.type.code, scope.value) == 1,
                "FEISHU_POLICY_SCOPE_INSERT_FAILED",
            )
        }
    }

    /** 幂等 UPSERT 最近组织事实；JSON 已由服务层在事务外准备。 */
    override fun saveFacts(userId: Long, groupsJson: String, departmentsJson: String, active: Boolean) {
        persisted(
            facts.upsert(
                userId,
                groupsJson,
                departmentsJson,
                if (active) FeishuOrganizationUserStatus.ACTIVE.code else FeishuOrganizationUserStatus.INACTIVE.code,
            ) > 0,
            "FEISHU_ORG_FACT_UPSERT_FAILED",
        )
    }

    /** 在独立短事务中记录单用户同步失败，避免影响其他用户授权提交。 */
    override fun markFailure(userId: Long, errorCode: String) {
        persisted(facts.markFailure(userId, errorCode) > 0, "FEISHU_ORG_FAILURE_MARK_FAILED")
    }

    /** 将互斥锁或活动运行冲突映射为可识别的同步冲突错误。 */
    private fun syncConflict(): Nothing = throw ServiceException(
        ErrorCode.FEISHU_SYNC_ALREADY_RUNNING,
        "FEISHU_POLICY_SYNC_ALREADY_RUNNING",
    )

    /** 注册事务完成回调释放连接级命名锁；注册失败时立即补偿释放后再抛出。 */
    private fun releaseStartLockAfterTransaction() {
        persisted(TransactionSynchronizationManager.isSynchronizationActive(), "FEISHU_SYNC_START_SYNCHRONIZATION_REQUIRED")
        try {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                /** 无论事务提交或回滚都释放命名锁；释放失败只记录错误，避免覆盖事务结果。 */
                override fun afterCompletion(status: Int) {
                    runCatching { runs.releaseStartLock(START_LOCK_NAME) }
                        .onFailure { log.error("Feishu synchronization start lock release failed", it) }
                        .onSuccess { released ->
                            if (released != 1) {
                                log.error("Feishu synchronization start lock was not owned at completion")
                            }
                        }
                }
            })
        } catch (error: RuntimeException) {
            runCatching { runs.releaseStartLock(START_LOCK_NAME) }
            throw error
        }
    }

    /** 校验仓储写入不变量，失败时抛错以回滚当前短事务。 */
    private fun persisted(condition: Boolean, reasonCode: String) {
        if (!condition) {
            failed(reasonCode)
        }
    }

    /** 将不可恢复的存储异常转换为稳定内部错误。 */
    private fun failed(reasonCode: String): Nothing = throw ServiceException(ErrorCode.INTERNAL_ERROR, reasonCode)

    private companion object {
        val log = LoggerFactory.getLogger(MyBatisFeishuPolicyRepositoryImpl::class.java)
        const val START_LOCK_NAME = "feedback:feishu-policy-sync:start"
        const val START_LOCK_TIMEOUT_SECONDS = 1
        const val STALE_ERROR_CODE = "FEISHU_SYNC_STALE"
    }
}
