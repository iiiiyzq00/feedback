package com.feedback.repository.imports

import com.feedback.model.imports.ImportTemplate
import com.feedback.mapper.imports.ImportTemplateConfigMapper
import com.feedback.model.po.imports.ImportTemplateConfigPo
import org.springframework.stereotype.Repository

@Repository
class ImportTemplateConfigRepository(private val mapper: ImportTemplateConfigMapper) {
    fun active(): List<ImportTemplate> = mapper.findActive().map(::toEntity)
    fun active(channel: String): ImportTemplate? = mapper.findLatestActive(channel)?.let(::toEntity)
    fun byLegacyId(id: Long): ImportTemplate? = mapper.findByLegacyId(id)?.let(::toEntity)

    private fun toEntity(row: ImportTemplateConfigPo) = ImportTemplate(
        id = row.legacyId, templateName = row.templateName, sourceChannel = row.sourceChannel,
        version = row.version, schemaVersion = row.schemaVersion, isActive = row.isActive,
        headerColumns = row.headerColumns, fieldMappings = row.fieldMappings,
        createdBy = row.createdBy, createdAt = row.createdAt, updatedAt = row.updatedAt
    )
}
