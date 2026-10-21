package com.feedback.repository.imports

import com.feedback.mapper.imports.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
class ImportChunkRepository(
    private val sessions: ImportUploadSessionMapper,
    private val chunks: ImportUploadChunkMapper
) {
    fun ensureSession(fileId: String, channel: String, totalChunks: Int, now: LocalDateTime) {
        sessions.insertIfAbsent(fileId, channel, totalChunks, now.plusHours(24))
    }
    fun session(fileId: String) = sessions.findByFileId(fileId)
    fun lockSession(fileId: String) = sessions.lockByFileId(fileId)
    fun readySession() = sessions.findReady()
    fun chunk(sessionId: Long, index: Int) = chunks.findOne(sessionId, index)
    fun chunks(sessionId: Long) = chunks.findBySession(sessionId)
    fun addChunk(sessionId: Long, index: Int, hash: ByteArray, size: Int, ciphertext: ByteArray,
                 nonce: ByteArray, tag: ByteArray, keyId: String, now: LocalDateTime) {
        if(chunks.insert(sessionId, index, hash, size, ciphertext, nonce, tag, keyId) != 1) importRepositoryFailed("IMPORT_CHUNK_INSERT_FAILED")
        if(sessions.addSize(sessionId, size, now) != 1) importRepositoryFailed("IMPORT_SESSION_SIZE_UPDATE_FAILED")
    }
    fun markAssembling(id: Long, filename: String, hash: ByteArray, sheet: String?, dates: String, now: LocalDateTime) {
        if(sessions.markAssembling(id, filename, hash, sheet, dates, now) != 1) importRepositoryFailed("IMPORT_SESSION_ASSEMBLING_FAILED")
    }
    fun complete(id: Long, batchId: String, now: LocalDateTime) {
        chunks.deleteBySession(id)
        if(sessions.markCompleted(id, batchId, now) != 1) importRepositoryFailed("IMPORT_SESSION_COMPLETE_FAILED")
    }
    fun fail(id: Long, code: String, now: LocalDateTime) { if(sessions.markFailed(id, code.take(50), now) != 1) importRepositoryFailed("IMPORT_SESSION_FAIL_UPDATE_FAILED") }
}
