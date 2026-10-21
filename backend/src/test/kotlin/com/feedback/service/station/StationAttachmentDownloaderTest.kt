package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.exception.ClientException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

class StationAttachmentDownloaderTest {
    @Test
    fun `accepts only https default or 443 URLs without credentials`() {
        val downloader = StationAttachmentDownloader(null, "build/test-station-attachments", 2)
        assertTrue(downloader.validateUrl("https://cdn.example/a.jpg").host == "cdn.example")
        assertThrows(ClientException::class.java) { downloader.validateUrl("http://cdn.example/a.jpg") }
        assertThrows(ClientException::class.java) { downloader.validateUrl("https://user:pass@cdn.example/a.jpg") }
        assertThrows(ClientException::class.java) { downloader.validateUrl("https://cdn.example:8443/a.jpg") }
    }

    @Test
    fun `rejects local private link local carrier nat and multicast addresses`() {
        listOf("127.0.0.1", "10.0.0.1", "169.254.169.254", "172.16.0.1", "192.168.1.1", "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1")
            .forEach { assertFalse(StationAttachmentDownloader.PublicAddressDns.isPublic(InetAddress.getByName(it)), it) }
        assertTrue(StationAttachmentDownloader.PublicAddressDns.isPublic(InetAddress.getByName("8.8.8.8")))
        assertTrue(StationAttachmentDownloader.PublicAddressDns.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
    }
}
