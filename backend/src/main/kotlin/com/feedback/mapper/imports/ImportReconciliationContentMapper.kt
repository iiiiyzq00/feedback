package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportReconciliationContentPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportReconciliationContentMapper { fun findPassingBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ImportReconciliationContentPo> }
