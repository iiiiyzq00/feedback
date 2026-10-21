package com.feedback.service.attachment.storage

import com.feedback.manager.attachment.*
import com.feedback.manager.attachment.*
import com.feedback.security.attachment.*
import com.feedback.repository.remote.attachment.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.RemoteException
import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.awscore.exception.AwsErrorDetails
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketResponse
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.HeadObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectResponse
import software.amazon.awssdk.services.s3.model.S3Exception
import java.io.ByteArrayInputStream
import java.time.Instant

class S3CompatibleAttachmentObjectStorageTest {
    private val client = mockk<S3Client>()
    private val storage = S3CompatibleAttachmentObjectStorage(client, "private-attachments")
    private val key = AttachmentObjectKey("feedback/7/log.txt")

    @Test
    fun `put maps private bucket key metadata and checksum without creating a URL`() {
        val request = slot<PutObjectRequest>()
        every { client.putObject(capture(request), any<RequestBody>()) } returns
            PutObjectResponse.builder().versionId("v7").eTag("etag-7").build()

        val stored = storage.put(
            PutAttachmentObjectCommand(
                key = key,
                content = ByteArrayInputStream("hello".toByteArray()),
                contentLength = 5,
                contentType = "text/plain",
                checksumSha256Base64 = "checksum"
            )
        )

        assertEquals("private-attachments", request.captured.bucket())
        assertEquals(key.value, request.captured.key())
        assertEquals("text/plain", request.captured.contentType())
        assertEquals("checksum", request.captured.checksumSHA256())
        assertEquals("v7", stored.version)
        assertEquals("etag-7", stored.etag)
    }

    @Test
    fun `head maps metadata and only maps definite missing key to null`() {
        val modified = Instant.parse("2026-09-01T01:02:03Z")
        every { client.headBucket(any<HeadBucketRequest>()) } returns HeadBucketResponse.builder().build()
        every { client.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder()
            .versionId("v1").eTag("etag").contentLength(11).contentType("text/plain").lastModified(modified).build()

        val metadata = storage.head(key)

        assertEquals(11, metadata?.contentLength)
        assertEquals(modified, metadata?.lastModified)

        every { client.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder()
            .statusCode(404).message("missing key").build()
        assertNull(storage.head(key))

        every { client.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build())
            .message("compatible missing key")
            .build()
        assertNull(storage.head(key))
        verify(exactly = 2) { client.headBucket(any<HeadBucketRequest>()) }
    }

    @Test
    fun `metadata gaps and non-not-found service errors fail closed`() {
        every { client.headObject(any<HeadObjectRequest>()) } returns HeadObjectResponse.builder().build()
        val metadataError = assertThrows(RemoteException::class.java) { storage.head(key) }
        assertEquals(ErrorCode.ATTACHMENT_STORAGE_UNAVAILABLE, metadataError.errorCode)
        assertEquals("ATTACHMENT_OBJECT_CONTENT_LENGTH_MISSING", metadataError.reasonCode)

        every { client.headObject(any<HeadObjectRequest>()) } throws
            S3Exception.builder().statusCode(503).message("unavailable").build()
        assertThrows(S3Exception::class.java) { storage.head(key) }
    }

    @Test
    fun `head rejects missing bucket and ambiguous generic 404`() {
        every { client.headObject(any<HeadObjectRequest>()) } throws NoSuchBucketException.builder()
            .statusCode(404).message("missing bucket").build()
        assertThrows(NoSuchBucketException::class.java) { storage.head(key) }

        every { client.headObject(any<HeadObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404).message("ambiguous not found").build()
        assertThrows(S3Exception::class.java) { storage.head(key) }

        every { client.headObject(any<HeadObjectRequest>()) } throws NoSuchKeyException.builder()
            .statusCode(404).message("MinIO disguises missing bucket as missing key").build()
        every { client.headBucket(any<HeadBucketRequest>()) } throws NoSuchBucketException.builder()
            .statusCode(404).message("missing bucket").build()
        val error = assertThrows(NoSuchBucketException::class.java) { storage.head(key) }
        assertTrue(error.suppressed.single() is NoSuchKeyException)
    }

    @Test
    fun `read owns response stream and maps response metadata`() {
        val response = GetObjectResponse.builder()
            .versionId("v2").eTag("etag-2").contentLength(4).contentType("text/plain").build()
        every { client.getObject(any<GetObjectRequest>()) } returns ResponseInputStream(
            response,
            AbortableInputStream.create(ByteArrayInputStream("data".toByteArray()))
        )

        storage.read(key).use { read ->
            assertEquals("data", read.content.reader().readText())
            assertEquals("v2", read.metadata.version)
        }
    }

    @Test
    fun `read closes response stream when metadata validation fails`() {
        val input = CloseTrackingInputStream("data".toByteArray())
        every { client.getObject(any<GetObjectRequest>()) } returns ResponseInputStream(
            GetObjectResponse.builder().contentType("text/plain").build(),
            AbortableInputStream.create(input)
        )

        val error = assertThrows(RemoteException::class.java) { storage.read(key) }

        assertEquals(ErrorCode.ATTACHMENT_STORAGE_UNAVAILABLE, error.errorCode)
        assertEquals("ATTACHMENT_OBJECT_CONTENT_LENGTH_MISSING", error.reasonCode)
        assertTrue(input.closed)
    }

    @Test
    fun `delete passes version and treats definite missing key as idempotent success`() {
        val request = slot<DeleteObjectRequest>()
        every { client.headBucket(any<HeadBucketRequest>()) } returns HeadBucketResponse.builder().build()
        every { client.deleteObject(capture(request)) } returns DeleteObjectResponse.builder().build()
        storage.delete(DeleteAttachmentObjectCommand(key, "v3"))
        assertEquals("v3", request.captured.versionId())

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws NoSuchKeyException.builder()
            .statusCode(404).message("already absent").build()
        storage.delete(DeleteAttachmentObjectCommand(key))

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").build())
            .message("compatible already absent")
            .build()
        storage.delete(DeleteAttachmentObjectCommand(key))

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchVersion").build())
            .message("version already absent")
            .build()
        storage.delete(DeleteAttachmentObjectCommand(key, "v3"))
        verify(exactly = 3) { client.headBucket(any<HeadBucketRequest>()) }
    }

    @Test
    fun `delete rejects missing bucket and ambiguous generic 404`() {
        every { client.deleteObject(any<DeleteObjectRequest>()) } throws NoSuchBucketException.builder()
            .statusCode(404).message("missing bucket").build()
        assertThrows(NoSuchBucketException::class.java) { storage.delete(DeleteAttachmentObjectCommand(key)) }

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404).message("ambiguous not found").build()
        assertThrows(S3Exception::class.java) { storage.delete(DeleteAttachmentObjectCommand(key)) }

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws S3Exception.builder()
            .statusCode(404)
            .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchVersion").build())
            .message("unexpected version error without version command")
            .build()
        assertThrows(S3Exception::class.java) { storage.delete(DeleteAttachmentObjectCommand(key)) }

        every { client.deleteObject(any<DeleteObjectRequest>()) } throws NoSuchKeyException.builder()
            .statusCode(404).message("compatible missing target or bucket").build()
        every { client.headBucket(any<HeadBucketRequest>()) } throws S3Exception.builder()
            .statusCode(403).message("bucket existence cannot be verified").build()
        val error = assertThrows(S3Exception::class.java) { storage.delete(DeleteAttachmentObjectCommand(key)) }
        assertEquals(403, error.statusCode())
        assertTrue(error.suppressed.single() is NoSuchKeyException)
    }

    @Test
    fun `close releases the S3 client`() {
        every { client.close() } returns Unit
        storage.close()
        verify(exactly = 1) { client.close() }
    }

    private class CloseTrackingInputStream(content: ByteArray) : ByteArrayInputStream(content) {
        var closed: Boolean = false

        override fun close() {
            closed = true
            super.close()
        }
    }
}
