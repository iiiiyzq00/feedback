package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportReconciliationSourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportReconciliationSourceMapper { fun findByIds(@Param("ids") ids: List<Long>): List<ImportReconciliationSourcePo> }
