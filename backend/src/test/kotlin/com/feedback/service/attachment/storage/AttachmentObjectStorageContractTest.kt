package com.feedback.service.attachment.storage

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class AttachmentObjectStorageContractTest {
    @Test
    fun `object key rejects traversal absolute and ambiguous path forms`() {
        listOf("", "/absolute", "../escape", "a/../escape", "a/./b", "a//b", "a\\b", "a/\u0000b")
            .forEach { value ->
                val error = assertThrows(ClientException::class.java, { AttachmentObjectKey(value) }, value)
                assertEquals(ErrorCode.BAD_REQUEST, error.errorCode)
            }
    }

    @Test
    fun `temporary adapter maps all operations and destroys its root on close`() {
        val storage = LocalObjectStorageTestAdapter("unit-test-bucket")
        val root = storage.temporaryRoot
        val key = AttachmentObjectKey("source/42/screenshot.png")
        val content = "controlled-content".toByteArray()

        storage.use {
            val stored = it.put(
                PutAttachmentObjectCommand(
                    key = key,
                    content = ByteArrayInputStream(content),
                    contentLength = content.size.toLong(),
                    contentType = "image/png"
                )
            )
            assertEquals(AttachmentStorageBackend.S3_COMPATIBLE, stored.backend)
            assertEquals(content.size.toLong(), it.head(key)?.contentLength)
            it.read(key).use { read ->
                assertEquals("image/png", read.metadata.contentType)
                assertTrue(content.contentEquals(read.content.readBytes()))
            }
            it.delete(DeleteAttachmentObjectCommand(key))
            it.delete(DeleteAttachmentObjectCommand(key))
            assertEquals(null, it.head(key))
        }

        assertFalse(Files.exists(root), "unit-test adapter must remove all temporary files")
    }
}
