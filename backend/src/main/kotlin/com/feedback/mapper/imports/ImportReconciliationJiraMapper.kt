package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportReconciliationJiraPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportReconciliationJiraMapper { fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ImportReconciliationJiraPo> }
