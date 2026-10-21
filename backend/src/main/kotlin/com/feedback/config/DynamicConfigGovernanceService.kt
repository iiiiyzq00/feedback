package com.feedback.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import com.feedback.repository.config.DynamicConfigCatalogRecord
import com.feedback.repository.config.SystemConfigRecord
import com.feedback.repository.config.SystemConfigReplacement
import com.feedback.repository.config.SystemConfigRepository
import com.feedback.security.CurrentWebPrincipalProvider
import org.slf4j.MDC
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID

/**
 * 动态配置用例编排：读取时在应用层组装目录与活动值，写入时以稳定锁序完成原子版本替换。
 *
 * 写接口默认禁用；正式审计只保存配置键、版本、原因哈希和长度，不保存配置值或原因明文。
 */
@Service
class DynamicConfigGovernanceService(
    private val repository: SystemConfigRepository,
    private val mapper: ObjectMapper,
    private val currentPrincipal: CurrentWebPrincipalProvider,
    private val businessAudits: BusinessAuditService,
    @Value("\${app.dynamic-config.mutation-enabled:false}") private val mutationEnabled: Boolean = false
) {
    fun current(): List<DynamicConfigItemResponse> {
        val catalogs = repository.activeCatalogs().filterNot(DynamicConfigCatalogRecord::secretValue)
        val active = catalogs.map(DynamicConfigCatalogRecord::configKey)
            .chunked(SystemConfigRepository.MAX_READ_BATCH)
            .flatMap { repository.activeConfigurations(it.toSet()).entries }
            .associate { it.toPair() }
        return catalogs.mapNotNull { catalog -> active[catalog.configKey]?.let { response(catalog, it) } }
    }

    fun history(key: String, page: Int, size: Int): DynamicConfigHistoryPageResponse {
        val catalog = repository.catalog(key) ?: notFound("CONFIG_KEY_NOT_DYNAMIC")
        if(catalog.secretValue) configInvalid("CONFIG_SECRET_FORBIDDEN")
        val records = repository.history(key, page, size).map {
            DynamicConfigHistoryItemResponse(
                it.id, it.configKey, it.configVersion, parseValue(it.configValue), it.active,
                it.changeSetId, it.changeReason, it.sourceConfigId, it.createdBy, it.createdAt
            )
        }
        return DynamicConfigHistoryPageResponse(records, repository.historyCount(key), page, size)
    }

    @Transactional
    fun change(command: DynamicConfigChangeCommand): DynamicConfigChangeResponse {
        ensureMutationEnabled()
        val outcome = replace(command.changes, command.reason, emptyMap(), "CONFIG_CHANGE_SET")
        return DynamicConfigChangeResponse(
            outcome.changeSetId,
            outcome.changes,
            outcome.restartRequiredKeys.isEmpty(),
            outcome.restartRequiredKeys
        )
    }

    @Transactional
    fun rollback(command: DynamicConfigRollbackCommand): DynamicConfigRollbackResponse {
        ensureMutationEnabled()
        val key = command.key.trim()
        val targetVersion = command.targetVersion.trim()
        val target = repository.findVersion(key, targetVersion)
            ?: notFound("CONFIG_ROLLBACK_TARGET_NOT_FOUND")
        val outcome = replace(
            listOf(DynamicConfigChangeItemCommand(key, parseValue(target.configValue), command.expectedVersion.trim())),
            command.reason,
            mapOf(key to target.id),
            "CONFIG_ROLLBACK"
        )
        val changed = outcome.changes.single()
        return DynamicConfigRollbackResponse(
            outcome.changeSetId,
            key,
            targetVersion,
            changed.version,
            outcome.restartRequiredKeys.isEmpty(),
            outcome.restartRequiredKeys
        )
    }

    private fun replace(
        changes: List<DynamicConfigChangeItemCommand>,
        rawReason: String,
        sources: Map<String, Long>,
        operation: String
    ): ReplacementOutcome {
        val reason = rawReason.trim()
        if(reason.length !in 2..500) configInvalid("CONFIG_CHANGE_REASON_REQUIRED")
        if(changes.isEmpty() || changes.size > SystemConfigRepository.MAX_CHANGES) configInvalid("CONFIG_CHANGE_SET_INVALID")
        val normalizedChanges = changes.map {
            it.copy(key = it.key.trim(), expectedVersion = it.expectedVersion.trim())
        }
        val keys = normalizedChanges.map(DynamicConfigChangeItemCommand::key)
        if(keys.distinct().size != keys.size) configInvalid("CONFIG_CHANGE_KEY_DUPLICATED")
        if(normalizedChanges.any { it.expectedVersion.isBlank() || it.expectedVersion.length > 30 }) configInvalid("CONFIG_EXPECTED_VERSION_REQUIRED")

        val requested = keys.toSet()
        val catalogs = repository.lockCatalogs(requested)
        if (catalogs.size != requested.size) notFound("CONFIG_KEY_NOT_DYNAMIC")
        if(catalogs.values.any(DynamicConfigCatalogRecord::secretValue)) configInvalid("CONFIG_SECRET_FORBIDDEN")

        val active = repository.activeConfigurations(requested)
        if (active.size != requested.size) conflict("CONFIG_ACTIVE_VERSION_MISSING")
        normalizedChanges.forEach { change ->
            val current = active.getValue(change.key)
            if (current.configVersion != change.expectedVersion) conflict("CONFIG_VERSION_CONFLICT")
            validateValue(change.key, change.value, catalogs.getValue(change.key))
        }

        val counts = repository.historyCounts(requested)
        val actor = currentPrincipal.require().userId
        val changeSetId = UUID.randomUUID().toString()
        val replacements = normalizedChanges.sortedBy(DynamicConfigChangeItemCommand::key).map { change ->
            val catalog = catalogs.getValue(change.key)
            SystemConfigReplacement(
                configKey = change.key,
                configVersion = "v${(counts[change.key] ?: 0L) + 1L}",
                configValue = mapper.writeValueAsString(change.value),
                configDesc = catalog.description,
                changeSetId = changeSetId,
                changeReason = reason,
                sourceConfigId = sources[change.key],
                createdBy = actor
            )
        }
        repository.replaceActive(replacements)

        val changed = replacements.map { DynamicConfigChangedItemResponse(it.configKey, it.configVersion) }
        val restartRequired = replacements.map(SystemConfigReplacement::configKey)
            .filterNot { catalogs.getValue(it).hotReload }
        appendAudit(operation, actor, changeSetId, reason, changed, sources)
        return ReplacementOutcome(changeSetId, changed, restartRequired)
    }

    private fun appendAudit(
        operation: String,
        actor: Long,
        changeSetId: String,
        reason: String,
        changed: List<DynamicConfigChangedItemResponse>,
        sources: Map<String, Long>
    ) {
        businessAudits.append(
            BusinessAuditEvent(
                actor = actor,
                operation = operation,
                auditClass = "SYSTEM",
                outcome = "SUCCESS",
                targetType = "SYSTEM_CONFIG",
                targetId = changeSetId,
                requestId = MDC.get("requestId") ?: changeSetId,
                clientIp = null,
                detail = mapOf(
                    "changeSetId" to changeSetId,
                    "reasonSha256" to sha256(reason),
                    "reasonLength" to reason.length,
                    "changes" to changed.map { mapOf("key" to it.key, "version" to it.version) },
                    "sourceRecordIds" to sources.values.sorted()
                )
            )
        )
    }

    private fun response(catalog: DynamicConfigCatalogRecord, record: SystemConfigRecord) = DynamicConfigItemResponse(
        configKey = record.configKey,
        version = record.configVersion,
        value = parseValue(record.configValue),
        description = catalog.description,
        changeReason = record.changeReason,
        updatedAt = record.updatedAt,
        valueType = catalog.valueType,
        minValue = catalog.minValue?.toBigDecimal(),
        maxValue = catalog.maxValue?.toBigDecimal(),
        hotReload = catalog.hotReload,
        ownerModule = catalog.ownerModule
    )

    private fun validateValue(key: String, value: JsonNode, catalog: DynamicConfigCatalogRecord) {
        when (catalog.valueType) {
            "INTEGER" -> if(!value.isIntegralNumber) configInvalid("CONFIG_TYPE_INVALID")
            "DECIMAL" -> if(!value.isNumber) configInvalid("CONFIG_TYPE_INVALID")
            "BOOLEAN" -> if(!value.isBoolean) configInvalid("CONFIG_TYPE_INVALID")
            "STRING" -> if(!value.isTextual || value.asText().length > 2000) configInvalid("CONFIG_TYPE_INVALID")
            "JSON" -> if(!value.isContainerNode) configInvalid("CONFIG_TYPE_INVALID")
            else -> configInvalid("CONFIG_TYPE_INVALID")
        }
        if (value.isNumber) {
            val number = value.decimalValue()
            catalog.minValue?.toBigDecimal()?.let { if(number < it) configInvalid("CONFIG_RANGE_INVALID") }
            catalog.maxValue?.toBigDecimal()?.let { if(number > it) configInvalid("CONFIG_RANGE_INVALID") }
        }
        if(key != catalog.configKey) configInvalid("CONFIG_CATALOG_KEY_MISMATCH")
    }

    private fun parseValue(raw: String): JsonNode = mapper.readTree(raw)

    private fun ensureMutationEnabled() {
        if (!mutationEnabled) configFailed("DYNAMIC_CONFIG_MUTATION_DISABLED")
    }

    private fun notFound(code: String): Nothing = configNotFound(code)
    private fun conflict(code: String): Nothing = configInvalid(code)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private data class ReplacementOutcome(
        val changeSetId: String,
        val changes: List<DynamicConfigChangedItemResponse>,
        val restartRequiredKeys: List<String>
    )
}
