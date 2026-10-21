package com.feedback.service.attachment

import com.feedback.model.attachment.*

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.feedback.security.ContentVisibility
import com.feedback.security.WebPrincipal
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

class InlineLogViewTest {
    private val technical=WebPrincipal(1,"tech","","Tech",setOf("PHASE_ONE_TECHNICAL"),setOf(ContentVisibility.RAW_PERMISSION.code),authProvider="SSO")
    private val product=WebPrincipal(2,"product","","Product",setOf("PHASE_ONE_PRODUCT"),emptySet(),authProvider="SSO")
    @Test fun `gzip log becomes inline plain text`() {
        val bytes=ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("原始日志\nline-2".toByteArray()) } }.toByteArray()
        val view=SecureAttachmentContent(source(bytes),"application/gzip","a.log.gz",bytes.size.toLong()).asInlineView(technical)
        assertEquals("text/plain;charset=UTF-8",view.mimeType)
        assertEquals("原始日志\nline-2",view.openStream().reader().readText())
        assertEquals(null,view.contentLength)
    }

    @Test fun `bounded utf8 gzip does not become gb18030 mojibake at a split character`() {
        val bytes=ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("你好世界".toByteArray()) } }.toByteArray()
        val text=SecureAttachmentContent(source(bytes),"application/gzip","a.log.gz",bytes.size.toLong()).asInlineView(technical,previewLimitBytes=5).openStream().reader().readText()
        assertTrue(text.startsWith("你"))
        assertFalse(text.contains("浣"))
        assertTrue(text.contains("日志预览已截断"))
    }

    @Test fun `zip log entries become one inline text stream`() {
        val bytes=ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("first.log"));zip.write("first".toByteArray());zip.closeEntry()
            zip.putNextEntry(ZipEntry("second.txt"));zip.write("second".toByteArray());zip.closeEntry()
        }}.toByteArray()
        val text=SecureAttachmentContent(source(bytes),"application/zip","logs.zip",bytes.size.toLong()).asInlineView(technical).openStream().reader().readText()
        assertTrue(text.contains("===== first.log ====="));assertTrue(text.contains("first"))
        assertTrue(text.contains("===== second.txt ====="));assertTrue(text.contains("second"))
    }

    @Test fun `tgz archive exposes text entries instead of tar headers`() {
        val bytes=tgz(mapOf("device/app.log" to "启动完成\n中文日志".toByteArray(),"device/blob.bin" to byteArrayOf(0,1,2,3)))
        val text=SecureAttachmentContent(source(bytes),"application/gzip","logs.tgz",bytes.size.toLong()).asInlineView(technical).openStream().reader().readText()
        assertTrue(text.contains("===== device/app.log ====="))
        assertTrue(text.contains("启动完成\n中文日志"))
        assertFalse(text.contains("ustar"))
        assertFalse(text.contains("blob.bin"))
    }

    @Test fun `nested tgz log is safely expanded`() {
        val inner=tgz(mapOf("logcat/main.txt" to "nested-log-line".toByteArray()))
        val outer=tgz(mapOf("device/seewo_apk.tgz" to inner))
        val text=SecureAttachmentContent(source(outer),"application/gzip","bundle.tgz",outer.size.toLong()).asInlineView(technical).openStream().reader().readText()
        assertTrue(text.contains("===== logcat/main.txt ====="))
        assertTrue(text.contains("nested-log-line"))
        assertFalse(text.contains("ustar"))
    }

    @Test fun `product log stream hides embedded originals before browser delivery`() {
        val content=SecureAttachmentContent(source("phone=18712349695 email=yang@example.com".toByteArray()),"text/plain","a.log")
        assertEquals("phone=187****9695 email=y***@example.com\n",content.asInlineView(product).openStream().reader().readText())
    }

    @Test fun `large log view is bounded and explains truncation`() {
        val content=SecureAttachmentContent(source("0123456789abcdefghijklmnopqrstuvwxyz".toByteArray()),"text/plain","large.log")
        val text=content.asInlineView(technical,previewLimitBytes=10).openStream().reader().readText()
        assertTrue(text.startsWith("0123456789"))
        assertTrue(text.contains("日志预览已截断"))
        assertTrue(text.contains("仅展示前 10 字节"))
        assertTrue(text.length < 200)
    }

    @Test fun `small log view is not marked as truncated`() {
        val content=SecureAttachmentContent(source("short".toByteArray()),"text/plain","small.log")
        val text=content.asInlineView(technical,previewLimitBytes=10).openStream().reader().readText()
        assertEquals("short",text)
    }

    private fun tgz(entries:Map<String,ByteArray>):ByteArray=ByteArrayOutputStream().also { compressed ->
        GZIPOutputStream(compressed).use { gzip -> TarArchiveOutputStream(gzip).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { (name,content) ->
                val entry=TarArchiveEntry(name).apply { size=content.size.toLong() }
                tar.putArchiveEntry(entry);tar.write(content);tar.closeArchiveEntry()
            }
            tar.finish()
        }}
    }.toByteArray()
    private fun source(bytes: ByteArray) = AttachmentContentSource { bytes.inputStream() }
}
