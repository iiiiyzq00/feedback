package com.feedback.repository

import com.feedback.model.imports.ImportAttachmentTask
import com.feedback.mapper.imports.ImportAttachmentTaskMapper
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
class ImportAttachmentTaskRepository(private val mapper: ImportAttachmentTaskMapper) {
    fun existsByImportRowIdAndSourceUrlHash(rowId: Long, hash: ByteArray) = mapper.existsByRowHash(rowId, hash) > 0
    fun findByImportRowId(rowId: Long) = mapper.findByImportRowId(rowId)
    fun findById(id: Long): Optional<ImportAttachmentTask> = Optional.ofNullable(mapper.findById(id))
    fun save(row: ImportAttachmentTask): ImportAttachmentTask { if (row.id == 0L) check(mapper.insert(row) == 1) else check(mapper.update(row) == 1); return row }
}
