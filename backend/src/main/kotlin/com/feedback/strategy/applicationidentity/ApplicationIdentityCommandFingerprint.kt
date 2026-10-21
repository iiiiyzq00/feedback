package com.feedback.strategy.applicationidentity

import com.feedback.model.applicationidentity.ApplicationIdentityAuditAction
import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** requestId 幂等命令指纹；字段顺序与分隔符一经发布不可改变。 */
object ApplicationIdentityCommandFingerprint {
    /** 对规范化命令字段生成稳定 SHA-256 大写十六进制指纹。 */
    fun of(vararg values: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f") { it?.toString().orEmpty() }.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02X".format(it) }
}

/** reviewed binding 指纹固定包含规范化展示名、计数、决策、目标版本与原因。 */
fun reviewedBindingCommandFingerprint(command: ApplicationReviewedBindingCommand): String =
    ApplicationIdentityCommandFingerprint.of(
        ApplicationIdentityAuditAction.REVIEWED_BINDING.code,
        command.candidateDisplayName,
        command.expectedSourceCount,
        command.decision.code,
        command.targetApplicationId,
        command.expectedTargetVersion,
        command.reason
    )
