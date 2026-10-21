package com.feedback.service.query.listprojection

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
/**
 * CREATE、REFRESH、VERIFY 的受控运维输入。
 *
 * action 必须与调用入口一致；generationKey 去空格后最长 80，只允许字母数字和 `-_.:`；actorId 必须指向
 * 活动用户，CREATE 将其写入 createdBy，REFRESH/VERIFY 只复核活动状态；batchSize 固定为 1..200。该对象不是
 * HTTP 请求，也不自行建立授权主体。
 */
data class FeedbackListProjectionBuildCommand(
    val action: String,
    val generationKey: String,
    val actorId: Long,
    val batchSize: Int
)

/**
 * 单次创建或刷新动作的结果。
 *
 * processedCount 只统计本次实际请求处理的 source；cursorSourceId 是已持久化构建进度，fenceSourceId 是本次
 * BUILDING 必须追平的闭区间上界。返回 BUILDING 只表示槽位仍可继续构建，不表示已验证或可公开读取。
 */
data class FeedbackListProjectionBuildResult(
    val generationId: Long,
    val generationStatus: String,
    val processedCount: Int,
    val cursorSourceId: Long,
    val fenceSourceId: Long
)

/** ACTIVATE 的受控运维输入；actorId 仅作活动用户校验和激活归因，不代表从 HTTP 会话继承权限。 */
data class FeedbackListProjectionActivationCommand(
    val action: String,
    val generationKey: String,
    val actorId: Long
)

/**
 * 激活完成后的槽位事实。previousActiveGenerationId 为空表示此前无 ACTIVE；mutationEpoch 是切换事务锁定、
 * 并写入 generation cutover 事实的 control epoch，供后续 dirty 变化与回滚资格校验。
 */
data class FeedbackListProjectionActivationResult(
    val generationId: Long,
    val generationStatus: String,
    val previousActiveGenerationId: Long?,
    val mutationEpoch: Long
)

/** ROLLBACK 的受控运维输入；generationKey 必须定位唯一 ROLLBACK_READY，而不是任意历史 generation。 */
data class FeedbackListProjectionRollbackCommand(
    val action: String,
    val generationKey: String,
    val actorId: Long
)

/**
 * 回滚完成后的槽位事实。generationId 是重新激活的旧 generation，failedGenerationId 是被固定失败事实关闭的
 * 原 ACTIVE；mutationEpoch 是双方 checksum 与 cutover epoch 重新核验时锁定的 control epoch。
 */
data class FeedbackListProjectionRollbackResult(
    val generationId: Long,
    val generationStatus: String,
    val failedGenerationId: Long,
    val mutationEpoch: Long
)

/**
 * 同一事务快照内的 generation 完整性事实。
 *
 * baselineSourceCount 必须等于 projectedSourceCount + rejectedSourceCount；memberSourceCount 与四组 checksum
 * 分别覆盖列表、成员、问题分析成员和不可逆身份投影。mutationEpoch 是验证快照，不代表事务提交后的未来写入；
 * Coordinator 必须持锁保存并在激活或回滚前重新核验。
 */
data class FeedbackListProjectionVerificationResult(
    val generationId: Long,
    val mutationEpoch: Long,
    val baselineSourceCount: Long,
    val projectedSourceCount: Long,
    val rejectedSourceCount: Long,
    val memberSourceCount: Long,
    val contentChecksum: String,
    val memberChecksum: String,
    val analysisChecksum: String = "0".repeat(64),
    val analysisIdentityChecksum: String = "0".repeat(64),
    val analysisIdentityCount: Long = 0
)

/** Runner 和 Coordinator 共用的纯输入验证；不访问数据库，也不推断 action 对应的业务权限。 */
internal object FeedbackListProjectionBackfillValidation {
    fun generationKey(value: String): String = value.trim().also {
        if (it.isEmpty() || it.length > 80 || it.any { char -> !char.isLetterOrDigit() && char !in "-_.:" }) {
            invalid("FEEDBACK_LIST_PROJECTION_GENERATION_KEY_INVALID")
        }
    }

    fun action(actual: String, expected: String) {
        if (actual.trim().uppercase() != expected) invalid("FEEDBACK_LIST_PROJECTION_ACTION_INVALID")
    }

    fun batchSize(value: Int): Int = value.also {
        if (it !in 1..200) invalid("FEEDBACK_LIST_PROJECTION_BATCH_LIMIT_INVALID")
    }

    private fun invalid(reasonCode: String): Nothing = throw ClientException(ErrorCode.BAD_REQUEST, reasonCode)
}
