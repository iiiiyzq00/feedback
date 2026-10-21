package com.feedback.service.jira

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.repository.ImportBatchRepository
import com.feedback.service.imports.ImportBatchService
import com.feedback.service.imports.ImportBatchView
import com.feedback.service.imports.ImportTemplateService
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * Jira 同步与导入子系统之间的窄接口。
 *
 * 该端口把模板、批次编排和批次仓储三个内部依赖封装在适配器后，避免同步服务同时了解
 * 三套 API，也让字段合同校验只有一个入口。
 */
interface JiraSyncImportPort {
    fun activeContract(): JiraFieldMappingContract
    fun stage(
        rows: List<Map<String, String>>,
        contract: JiraFieldMappingContract,
        trigger: String,
        from: LocalDateTime,
        to: LocalDateTime,
        instanceHash: ByteArray,
        actor: Long?
    ): ImportBatchView
    fun markFormalReviewUnavailable(keys: List<String>): Int
    fun publicBatchId(databaseId: Long): String?
    fun databaseBatchId(publicId: String): Long?
}

@Component
class DefaultJiraSyncImportAdapter(
    private val imports: ImportBatchService,
    private val templates: ImportTemplateService,
    private val batches: ImportBatchRepository
) : JiraSyncImportPort {
    override fun activeContract(): JiraFieldMappingContract {
        val template = templates.active("JIRA")
        val contract = JiraFieldMappingContract(template.id, template.version, templates.view(template).mapping)
        validate(contract)
        return contract
    }

    override fun stage(
        rows: List<Map<String, String>>,
        contract: JiraFieldMappingContract,
        trigger: String,
        from: LocalDateTime,
        to: LocalDateTime,
        instanceHash: ByteArray,
        actor: Long?
    ) = imports.stageJiraApiBatch(
        rows, contract.templateId, contract.version, contract.mapping,
        trigger, from, to, instanceHash, actor
    )

    override fun markFormalReviewUnavailable(keys: List<String>) = imports.markFormalReviewUnavailable(keys)
    override fun publicBatchId(databaseId: Long) = batches.findById(databaseId).orElse(null)?.batchId
    override fun databaseBatchId(publicId: String) = batches.findByBatchId(publicId)?.id

    private fun validate(contract: JiraFieldMappingContract) {
        val required = setOf("source_record_id", "description", "source_update_time")
        if (!contract.mapping.keys.containsAll(required)) invalid("JIRA_MAPPING_REQUIRED_FIELD_MISSING")
        if (!contract.mapping.keys.all { it in templates.allowedTargets("JIRA") }) invalid("JIRA_MAPPING_TARGET_INVALID")
        if (!contract.mapping.keys.all(contract::supportsTarget)) invalid("JIRA_MAPPING_API_TARGET_UNSUPPORTED")
        if (!contract.mapping.values.all { value ->
                value.isNotBlank() && value.none { it == '\r' || it == '\n' }
            }) invalid("JIRA_MAPPING_SOURCE_INVALID")
    }

    private fun invalid(reasonCode: String): Nothing =
        throw ServiceException(ErrorCode.JIRA_SYNC_CONTRACT_INVALID, reasonCode)
}
