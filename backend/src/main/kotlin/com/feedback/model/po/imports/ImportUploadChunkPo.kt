package com.feedback.model.po.imports

data class ImportUploadChunkPo(
    val chunkIndex: Int, val chunkSha256: ByteArray, val chunkCiphertext: ByteArray,
    val chunkNonce: ByteArray, val chunkTag: ByteArray
)
