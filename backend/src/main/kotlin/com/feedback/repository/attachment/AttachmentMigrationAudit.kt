package com.feedback.repository.attachment

import com.feedback.model.attachment.AttachmentCleanupAuditOutcome
import com.feedback.model.po.attachment.AttachmentObjectCleanupCandidatePo
import com.feedback.model.po.attachment.AttachmentObjectMigrationCandidatePo

/** 附件迁移审计端口；业务编排不感知 JSONL 文件格式与并发写入细节。 */
interface AttachmentMigrationAudit {
    /** 记录清理阶段的封闭结果，不写入原始路径或对象键。 */
    fun recordCleanup(candidate: AttachmentObjectCleanupCandidatePo, outcome: AttachmentCleanupAuditOutcome)

    /** 记录白名单根内本机源已缺失的迁移分类。 */
    fun recordSourceUnavailable(candidate: AttachmentObjectMigrationCandidatePo)

    /** 统计尚未出现闭合结果的删除意图，用于运维告警。 */
    fun countUnclosedDeleteIntents(): Long
}
