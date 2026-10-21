package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportReconciliationAttachmentPo
import com.feedback.model.po.imports.ImportReconciliationRowPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportReconciliationRowMapper {
    fun findRows(batchId: Long): List<ImportReconciliationRowPo>
    fun findAttachmentStats(@Param("rowIds") rowIds: List<Long>): List<ImportReconciliationAttachmentPo>
}
