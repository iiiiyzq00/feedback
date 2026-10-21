package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportQualityAttachmentCountsPo
import com.feedback.model.po.imports.ImportQualityRowCountsPo
import org.apache.ibatis.annotations.Mapper

@Mapper interface ImportQualityRowMapper {
    fun counts(batchId: Long): ImportQualityRowCountsPo
    fun attachmentCounts(batchId: Long): ImportQualityAttachmentCountsPo
}
