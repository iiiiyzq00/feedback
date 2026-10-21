package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportTemplateConfigPo
import org.apache.ibatis.annotations.Mapper

@Mapper
interface ImportTemplateConfigMapper {
    fun findActive(): List<ImportTemplateConfigPo>
    fun findLatestActive(sourceChannel: String): ImportTemplateConfigPo?
    fun findByLegacyId(id: Long): ImportTemplateConfigPo?
}
