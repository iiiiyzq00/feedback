package com.feedback.service.jira

import com.feedback.config.Jira400Properties
import com.feedback.repository.attachment.FeedbackAttachmentMutationRepository
import com.feedback.model.po.jira.JiraAttachmentTaskPo
import com.feedback.repository.jira.JiraAttachmentTaskRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.dao.CannotAcquireLockException
import java.time.LocalDateTime
import java.nio.file.Files

class JiraAttachmentEvidenceTest {
    private val mapper=jacksonObjectMapper()

    @Test fun `classifies screenshots and logs but excludes unrelated documents`() {
        val service=JiraLogAttachmentImportService(Jira400Properties(),mockk(),mockk(),mockk<FeedbackAttachmentMutationRepository>(),"/tmp/jira-attachment-test")
        fun node(name:String,mime:String)=mapper.createObjectNode().put("filename",name).put("mimeType",mime)
        assertEquals("IMAGE",service.attachmentType(node("failure-screen.png","image/png")))
        assertEquals("LOG",service.attachmentType(node("device-log.zip","application/zip")))
        assertEquals("VIDEO",service.attachmentType(node("reproduction.mov","video/quicktime")))
        assertEquals("VIDEO",service.attachmentType(node("reproduction.mp4","")))
        assertEquals(null,service.attachmentType(node("requirement.pdf","application/pdf")))
    }

    @Test fun `extracts governed phone and serial from Jira template lines`() {
        val text="""1、【型号与序列号】：ModelX SERIAL1234567890
            |2、【客户信息】：demo_user 13800138000 浙江省
        """.trimMargin()
        assertEquals(listOf("DEVICE_SN" to "SERIAL1234567890","PHONE" to "13800138000"),JiraDescriptionDimensions.extractControlledIdentities(text))
    }

    @Test fun `queues one durable pull per source and resets it only for a newer Jira update`() {
        val repository=mockk<JiraAttachmentTaskRepository>()
        every{repository.enqueue(any(),any(),any())} returns Unit
        val queue=JiraAttachmentPullQueue(repository,mockk())
        queue.enqueue(42,"demo-7",LocalDateTime.parse("2026-08-24T12:00:00"))
        verify(exactly=1){repository.enqueue(42,"DEMO-7",LocalDateTime.parse("2026-08-24T12:00:00"))}
    }

    @Test fun `claims at most twenty Jira tasks with skip locked leases`() {
        val repository=mockk<JiraAttachmentTaskRepository>()
        every{repository.claim("worker-1",20,10)} returns listOf(JiraAttachmentTaskPo(41,"DEMO-41",0,5),JiraAttachmentTaskPo(42,"DEMO-42",0,5))
        val claimed=JiraAttachmentPullQueue(repository,mockk()).claimBatch("worker-1")
        assertEquals(listOf("DEMO-41","DEMO-42"),claimed.map{it["jira_key"]})
        verify(exactly=1){repository.claim("worker-1",20,10)}
    }

    @Test fun `batch failure falls back to isolated Jira issue execution`() {
        val repository=mockk<JiraAttachmentTaskRepository>(relaxed=true)
        val importer=mockk<JiraLogAttachmentImportService>()
        val tasks=listOf(
            mapOf<String,Any?>("id" to 51L,"jira_key" to "DEMO-51","retry_count" to 0,"max_retries" to 5),
            mapOf<String,Any?>("id" to 52L,"jira_key" to "DEMO-52","retry_count" to 0,"max_retries" to 5)
        )
        every{importer.pull(listOf("DEMO-51","DEMO-52"))} returns JiraLogAttachmentImportResult(2,1,0,0,1,mapOf("JIRA_ATTACHMENT_HTTP_404" to 1))
        every{importer.pull(listOf("DEMO-51"))} returns JiraLogAttachmentImportResult(1,0,0,0,0,emptyMap())
        every{importer.pull(listOf("DEMO-52"))} returns JiraLogAttachmentImportResult(1,0,0,0,1,mapOf("JIRA_ATTACHMENT_HTTP_404" to 1))
        JiraAttachmentPullQueue(repository,importer).executeBatch(tasks,"worker-2")
        verify(exactly=1){importer.pull(listOf("DEMO-51"))}
        verify(exactly=1){importer.pull(listOf("DEMO-52"))}
        verify{repository.complete(51L,"worker-2")}
        verify{repository.fail(52L,"worker-2","FAILED",1,true,2,"JIRA_REQUEST_REJECTED",any())}
    }

    @Test fun `worker retries transient deadlock while claiming Jira batch`() {
        val queue=mockk<JiraAttachmentPullQueue>()
        every{queue.ready()} returns true
        every{queue.claimBatch(any(),any())} throws CannotAcquireLockException("deadlock") andThen emptyList()

        assertFalse(JiraAttachmentPullWorker(queue).runBatch())

        verify(exactly=2){queue.claimBatch(any(),any())}
    }

    @Test fun `detects original png bytes when Jira omits mime type`() {
        val service=JiraLogAttachmentImportService(Jira400Properties(),mockk(),mockk(),mockk<FeedbackAttachmentMutationRepository>(),"/tmp/jira-attachment-test")
        val file=Files.createTempFile("jira-original-",".png")
        try {
            Files.write(file,byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a,1,2,3))
            val node=mapper.createObjectNode().put("filename","screenshot.png")
            assertEquals("image/png",service.resolvedMimeType(node,file))
        } finally { Files.deleteIfExists(file) }
    }
}
