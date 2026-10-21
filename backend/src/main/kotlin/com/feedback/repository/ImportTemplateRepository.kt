package com.feedback.repository

import com.feedback.model.imports.ImportTemplate
import com.feedback.mapper.imports.ImportTemplateMapper
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class ImportTemplateRepository(private val mapper: ImportTemplateMapper) {
    fun findByIsActiveTrueOrderBySourceChannelAscTemplateNameAsc() = mapper.findActive()
    fun findFirstBySourceChannelAndIsActiveTrueOrderByCreatedAtDesc(channel: String) = mapper.findLatestActive(channel)
    fun findById(id: Long): Optional<ImportTemplate> = Optional.ofNullable(mapper.findById(id))
    fun save(row: ImportTemplate): ImportTemplate { if (row.id == 0L) check(mapper.insert(row) == 1) else check(mapper.update(row) == 1); return row }
    fun saveAndFlush(row: ImportTemplate) = save(row)
}
