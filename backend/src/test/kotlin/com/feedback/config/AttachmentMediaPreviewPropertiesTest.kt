package com.feedback.config

import com.feedback.common.exception.ServiceException
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AttachmentMediaPreviewPropertiesTest {
    @Test
    fun `bounded defaults are valid`() {
        assertDoesNotThrow { AttachmentMediaPreviewProperties().validate() }
    }

    @Test
    fun `enabled ffmpeg requires a configured executable path`() {
        val properties = AttachmentMediaPreviewProperties().apply {
            ffmpegEnabled = true
            ffmpegPath = ""
        }
        assertThrows(ServiceException::class.java) { properties.validate() }
    }

    @Test
    fun `unbounded concurrency and video timeout fail startup validation`() {
        assertThrows(ServiceException::class.java) {
            AttachmentMediaPreviewProperties().apply { concurrency = 5 }.validate()
        }
        assertThrows(ServiceException::class.java) {
            AttachmentMediaPreviewProperties().apply { videoTimeoutMs = 31_000 }.validate()
        }
    }
}
