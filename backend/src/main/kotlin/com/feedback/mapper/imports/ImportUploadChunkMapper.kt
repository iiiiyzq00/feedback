package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportUploadChunkPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface ImportUploadChunkMapper {
    fun findOne(@Param("sessionId") sessionId: Long, @Param("chunkIndex") chunkIndex: Int): ImportUploadChunkPo?
    fun findBySession(sessionId: Long): List<ImportUploadChunkPo>
    fun insert(@Param("sessionId") sessionId: Long, @Param("chunkIndex") chunkIndex: Int,
               @Param("hash") hash: ByteArray, @Param("size") size: Int,
               @Param("ciphertext") ciphertext: ByteArray, @Param("nonce") nonce: ByteArray,
               @Param("tag") tag: ByteArray, @Param("keyId") keyId: String): Int
    fun deleteBySession(sessionId: Long): Int
}
