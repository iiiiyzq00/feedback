package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportUploadSessionPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface ImportUploadSessionMapper {
    fun insertIfAbsent(@Param("fileId") fileId: String, @Param("channel") channel: String,
                       @Param("totalChunks") totalChunks: Int, @Param("expiresAt") expiresAt: LocalDateTime): Int
    fun findByFileId(fileId: String): ImportUploadSessionPo?
    fun lockByFileId(fileId: String): ImportUploadSessionPo?
    fun findReady(): ImportUploadSessionPo?
    fun addSize(@Param("id") id: Long, @Param("size") size: Int, @Param("updatedAt") updatedAt: LocalDateTime): Int
    fun markAssembling(@Param("id") id: Long, @Param("filename") filename: String,
                       @Param("hash") hash: ByteArray, @Param("requestedSheet") requestedSheet: String?,
                       @Param("dates") dates: String, @Param("updatedAt") updatedAt: LocalDateTime): Int
    fun markCompleted(@Param("id") id: Long, @Param("batchId") batchId: String, @Param("updatedAt") updatedAt: LocalDateTime): Int
    fun markFailed(@Param("id") id: Long, @Param("code") code: String, @Param("updatedAt") updatedAt: LocalDateTime): Int
}
