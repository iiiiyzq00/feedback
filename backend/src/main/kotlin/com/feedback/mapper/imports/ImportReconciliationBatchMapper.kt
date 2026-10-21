package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportReconciliationBatchPo
import org.apache.ibatis.annotations.Mapper

@Mapper interface ImportReconciliationBatchMapper { fun findByBatchId(batchId: String): ImportReconciliationBatchPo? }
