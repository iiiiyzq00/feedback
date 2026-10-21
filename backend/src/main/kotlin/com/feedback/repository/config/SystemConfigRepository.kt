package com.feedback.repository.config

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.mapper.config.SystemConfigMapper
import com.feedback.model.po.config.DynamicConfigCatalogPo
import com.feedback.model.po.config.MaskingEvidencePo
import com.feedback.model.po.config.SystemConfigInsertPo
import com.feedback.model.po.config.SystemConfigPo
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class DynamicConfigCatalogRecord(
    val configKey: String,
    val valueType: String,
    val minValue: String?,
    val maxValue: String?,
    val hotReload: Boolean,
    val secretValue: Boolean,
    val ownerModule: String,
    val description: String
)

data class SystemConfigRecord(
    val id: Long,
    val configKey: String,
    val configVersion: String,
    val configValue: String,
    val configDesc: String? = null,
    val active: Boolean = false,
    val changeSetId: String? = null,
    val changeReason: String? = null,
    val sourceConfigId: Long? = null,
    val createdBy: Long? = null,
    val createdAt: LocalDateTime = LocalDateTime.MIN,
    val updatedAt: LocalDateTime = LocalDateTime.MIN
)

data class SystemConfigReplacement(
    val configKey: String,
    val configVersion: String,
    val configValue: String,
    val configDesc: String,
    val changeSetId: String,
    val changeReason: String,
    val sourceConfigId: Long?,
    val createdBy: Long
)

data class MaskingEvidenceRecord(
    val configKey: String,
    val configVersion: String,
    val configValue: String,
    val configSubtype: String?,
    val recordStatus: String?,
    val recordVersion: Long?,
    val effectiveTo: LocalDateTime?
)

/**
 * 系统配置的单表持久化边界。
 *
 * 所有集合都在 Mapper 前做去重和上限校验；跨记录目录组装和脱敏证据链判断只在应用层完成。
 */
@Repository("myBatisSystemConfigRepository")
class SystemConfigRepository(private val mapper: SystemConfigMapper, private val json: ObjectMapper) {
    fun activeCatalogs(): List<DynamicConfigCatalogRecord> {
        val rows = mapper.findActiveCatalogs(MAX_CATALOGS + 1)
        if(rows.size > MAX_CATALOGS) invalid("CONFIG_CATALOG_LIMIT_EXCEEDED")
        return rows.map(::catalog)
    }

    fun catalog(key: String): DynamicConfigCatalogRecord? {
        validateKey(key)
        return single(mapper.findCatalog(catalogKey(key), 2), "CONFIG_CATALOG_DUPLICATED")?.let(::catalog)
    }

    fun lockCatalogs(keys: Set<String>): Map<String, DynamicConfigCatalogRecord> {
        val bounded = boundedKeys(keys, MAX_CHANGES)
        if (bounded.isEmpty()) return emptyMap()
        val rows = mapper.lockCatalogs(bounded.map(::catalogKey))
        val records = rows.map(::catalog)
        return unique(records, DynamicConfigCatalogRecord::configKey, "CONFIG_CATALOG_DUPLICATED", bounded)
    }

    fun activeConfigurations(keys: Set<String>): Map<String, SystemConfigRecord> {
        val bounded = boundedKeys(keys, MAX_READ_BATCH)
        if (bounded.isEmpty()) return emptyMap()
        val records = mapper.findActiveConfigurations(bounded).map(::record)
        return unique(records, SystemConfigRecord::configKey, "CONFIG_ACTIVE_VERSION_DUPLICATED", bounded)
    }

    fun history(key: String, page: Int, size: Int): List<SystemConfigRecord> {
        validateKey(key)
        if(page !in 1..100000 || size !in 1..100) invalid("CONFIG_PAGE_INVALID")
        val offset = (page.toLong() - 1L) * size.toLong()
        return mapper.findHistory(key, size, offset).map(::record)
    }

    fun historyCount(key: String): Long {
        validateKey(key)
        return mapper.countHistory(key)
    }

    fun historyCounts(keys: Set<String>): Map<String, Long> {
        val bounded = boundedKeys(keys, MAX_CHANGES)
        if (bounded.isEmpty()) return emptyMap()
        val rows = mapper.countHistoryByKeys(bounded)
        val result = rows.associate { it.configKey to it.historyCount }
        if(result.size != rows.size || result.keys.any { it !in bounded }) failed("CONFIG_HISTORY_COUNT_INVALID")
        return result
    }

    fun findVersion(key: String, version: String): SystemConfigRecord? {
        validateKey(key)
        if(version.isBlank() || version.length > 30) invalid("CONFIG_VERSION_INVALID")
        return single(mapper.findByKeyAndVersion(key, version, 2), "CONFIG_VERSION_DUPLICATED")?.let(::record)
    }

    fun replaceActive(records: List<SystemConfigReplacement>) {
        if(records.isEmpty() || records.size > MAX_CHANGES) invalid("CONFIG_CHANGE_SET_INVALID")
        val keys = records.map(SystemConfigReplacement::configKey)
        if(keys.distinct().size != keys.size) invalid("CONFIG_CHANGE_KEY_DUPLICATED")
        if(mapper.deactivate(keys.sorted()) != records.size) failed("CONFIG_ACTIVE_VERSION_CHANGED")
        val inserts = records.sortedBy(SystemConfigReplacement::configKey).map {
            SystemConfigInsertPo(
                it.configKey, it.configVersion, it.configValue, it.configDesc, it.changeSetId,
                it.changeReason, it.sourceConfigId, it.createdBy
            )
        }
        if(mapper.insertAll(inserts) != inserts.size) failed("CONFIG_INSERT_COUNT_INVALID")
    }

    fun activeRuntime(key: String): SystemConfigRecord? {
        validateKey(key)
        return single(mapper.findActiveRuntime(key, 2), "RUNTIME_CONFIG_ACTIVE_DUPLICATED")?.let(::record)
    }

    fun activeMaskingRuleVersion(): MaskingEvidenceRecord? =
        single(mapper.findActiveMaskingRuleVersion(2), "MASKING_RULE_VERSION_DUPLICATED")?.let(::evidence)

    fun maskingRelease(version: String): MaskingEvidenceRecord? =
        single(mapper.findMaskingRelease(version, 2), "MASKING_RELEASE_DUPLICATED")?.let(::evidence)

    fun maskingEvidence(key: String, type: String): MaskingEvidenceRecord? =
        single(mapper.findMaskingEvidence(key, type, 2), "MASKING_EVIDENCE_DUPLICATED")?.let(::evidence)

    private fun catalog(row: DynamicConfigCatalogPo): DynamicConfigCatalogRecord {
        val value = json.readTree(row.configValue)
        val key = value["configKey"]?.asText()?.takeIf(String::isNotBlank)
            ?: row.configKey.removePrefix(CATALOG_PREFIX)
        if(row.configKey != catalogKey(key)) failed("CONFIG_CATALOG_KEY_MISMATCH")
        return DynamicConfigCatalogRecord(
            configKey = key,
            valueType = value["valueType"]?.asText()?.uppercase() ?: failed("CONFIG_CATALOG_TYPE_MISSING"),
            minValue = value["minValue"]?.takeUnless { it.isNull }?.asText(),
            maxValue = value["maxValue"]?.takeUnless { it.isNull }?.asText(),
            hotReload = boolean(value["hotReload"], default = false),
            secretValue = boolean(value["secretValue"], default = true),
            ownerModule = value["ownerModule"]?.asText()?.takeIf(String::isNotBlank) ?: "SYSTEM",
            description = value["description"]?.asText()?.takeIf(String::isNotBlank) ?: key
        )
    }

    private fun record(row: SystemConfigPo) = SystemConfigRecord(
        row.id, row.configKey, row.configVersion, row.configValue, row.configDesc, row.isActive,
        row.changeSetId, row.changeReason, row.sourceConfigId, row.createdBy, row.createdAt, row.updatedAt
    )

    private fun evidence(row: MaskingEvidencePo) = MaskingEvidenceRecord(
        row.configKey, row.configVersion, row.configValue, row.configSubtype,
        row.recordStatus, row.recordVersion, row.effectiveTo
    )

    private fun boolean(value: com.fasterxml.jackson.databind.JsonNode?, default: Boolean): Boolean = when {
        value == null || value.isNull -> default
        value.isBoolean -> value.booleanValue()
        value.isNumber -> value.intValue() != 0
        value.isTextual -> value.asText().equals("true", true) || value.asText() == "1"
        else -> default
    }

    private fun <T> single(rows: List<T>, code: String): T? {
        if(rows.size > 1) failed(code)
        return rows.singleOrNull()
    }

    private fun <T> unique(
        records: List<T>,
        key: (T) -> String,
        duplicateCode: String,
        requested: Collection<String>
    ): Map<String, T> {
        val result = records.associateBy(key)
        if(result.size != records.size) failed(duplicateCode)
        if(result.keys.any { it !in requested }) failed("CONFIG_QUERY_RETURNED_UNEXPECTED_KEY")
        return result
    }

    private fun boundedKeys(keys: Collection<String>, limit: Int): List<String> {
        val values = keys.toList()
        if(values.size > limit || values.distinct().size != values.size) invalid("CONFIG_KEY_BATCH_INVALID")
        values.forEach(::validateKey)
        return values.sorted()
    }

    private fun validateKey(key: String) {
        if(key.isBlank() || key.length > 100 || !KEY.matches(key)) invalid("CONFIG_KEY_INVALID")
    }

    private fun invalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun failed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)

    private fun catalogKey(key: String) = "$CATALOG_PREFIX$key"

    companion object {
        const val MAX_CATALOGS = 500
        const val MAX_READ_BATCH = 200
        const val MAX_CHANGES = 20
        private const val CATALOG_PREFIX = "DYNAMIC_CONFIG_CATALOG:"
        private val KEY = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,99}$")
    }
}
