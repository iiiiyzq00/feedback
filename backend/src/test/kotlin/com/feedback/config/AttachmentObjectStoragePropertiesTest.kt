package com.feedback.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class AttachmentObjectStoragePropertiesTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(ObjectStorageTestConfiguration::class.java)

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AttachmentObjectStorageProperties::class)
    @Import(AttachmentObjectStorageConfiguration::class)
    class ObjectStorageTestConfiguration

    @Test
    fun `disabled storage requires no runtime credentials or endpoint`() {
        assertTrue(AttachmentObjectStorageProperties().readinessErrors().isEmpty())
    }

    @Test
    fun `enabled storage fails closed without external S3 contract`() {
        val properties = AttachmentObjectStorageProperties().apply { enabled = true }

        assertEquals(
            setOf(
                "ATTACHMENT_OBJECT_ENDPOINT_MISSING",
                "ATTACHMENT_OBJECT_REGION_MISSING",
                "ATTACHMENT_OBJECT_BUCKET_INVALID",
                "ATTACHMENT_OBJECT_STATIC_CREDENTIALS_MISSING"
            ),
            properties.readinessErrors().toSet()
        )
    }

    @Test
    fun `static credentials must be complete and endpoint rejects embedded authority data`() {
        val properties = AttachmentObjectStorageProperties().apply {
            enabled = true
            endpoint = "https://user@example.invalid/storage?token=secret"
            region = "us-east-1"
            bucket = "private-attachments"
            accessKey = "access"
        }

        assertTrue("ATTACHMENT_OBJECT_ENDPOINT_INVALID" in properties.readinessErrors())
        assertTrue("ATTACHMENT_OBJECT_STATIC_CREDENTIALS_INCOMPLETE" in properties.readinessErrors())
    }

    @Test
    fun `private bucket must follow S3 compatible DNS naming`() {
        val properties = AttachmentObjectStorageProperties().apply {
            enabled = true
            endpoint = "https://minio.example.invalid"
            region = "us-east-1"
            bucket = "Private_Attachments"
        }

        assertTrue("ATTACHMENT_OBJECT_BUCKET_INVALID" in properties.readinessErrors())
    }

    @Test
    fun `migration scheduling remains bounded`() {
        val properties = AttachmentObjectStorageProperties().apply {
            enabled = true
            endpoint = "https://minio.example.invalid"
            region = "us-east-1"
            bucket = "private-attachments"
            accessKey = "access"
            secretKey = "secret"
            migrationDelayMs = 999
        }

        assertTrue("ATTACHMENT_OBJECT_MIGRATION_DELAY_INVALID" in properties.readinessErrors())
    }

    @Test
    fun `Spring configuration exposes no local filesystem provider`() {
        assertFalse(
            AttachmentObjectStorageConfiguration::class.java.declaredMethods.any {
                it.name.contains("local", ignoreCase = true)
            }
        )
    }

    @Test
    fun `disabled Spring configuration creates no object storage bean`() {
        contextRunner.run { context ->
            assertTrue(context.startupFailure == null)
            assertFalse(context.containsBean("s3AttachmentObjectStorage"))
        }
    }

    @Test
    fun `enabled Spring configuration fails startup when external endpoint is incomplete`() {
        contextRunner
            .withPropertyValues("app.attachment.object-storage.enabled=true")
            .run { context ->
                assertTrue(context.startupFailure != null)
            }
    }

    @Test
    fun `removed local provider property is rejected instead of silently selecting S3`() {
        contextRunner
            .withPropertyValues(
                "app.attachment.object-storage.enabled=false",
                "app.attachment.object-storage.provider=LOCAL_TEST"
            )
            .run { context -> assertTrue(context.startupFailure != null) }
    }
}
