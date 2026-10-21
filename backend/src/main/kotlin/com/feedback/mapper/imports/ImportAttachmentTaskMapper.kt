package com.feedback.mapper.imports

import com.feedback.model.imports.ImportAttachmentTask
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ImportAttachmentTaskMapper {
    fun findById(id: Long): ImportAttachmentTask?
    fun findByImportRowId(importRowId: Long): List<ImportAttachmentTask>
    fun existsByRowHash(@Param("importRowId") importRowId: Long, @Param("sourceUrlHash") sourceUrlHash: ByteArray): Int
    fun insert(row: ImportAttachmentTask): Int
    fun update(row: ImportAttachmentTask): Int
}
