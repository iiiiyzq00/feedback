package com.feedback.mapper.imports

import com.feedback.model.imports.ImportTemplate
import org.apache.ibatis.annotations.Mapper

@Mapper
interface ImportTemplateMapper {
    fun findActive(): List<ImportTemplate>
    fun findLatestActive(sourceChannel: String): ImportTemplate?
    fun findById(id: Long): ImportTemplate?
    fun insert(row: ImportTemplate): Int
    fun update(row: ImportTemplate): Int
}
