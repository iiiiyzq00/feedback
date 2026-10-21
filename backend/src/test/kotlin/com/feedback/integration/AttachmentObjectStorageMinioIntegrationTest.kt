package com.feedback.integration

import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import com.feedback.config.AttachmentObjectStorageConfiguration
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.model.attachment.DeleteAttachmentObjectCommand
import com.feedback.model.attachment.PutAttachmentObjectCommand
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteBucketRequest
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectVersionsRequest
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest
import software.amazon.awssdk.services.s3.model.S3Exception
import software.amazon.awssdk.services.s3.model.VersioningConfiguration
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.UUID

/**
 * 当前生产 S3 适配器与本机真实 MinIO 的显式集成门禁。
 *
 * 默认禁用且强制回环端点，避免测试误连共享或生产对象存储。每次使用随机私有版本桶，并在结束时
 * 删除全部版本、删除标记和桶；项目目录不会保存附件或 MinIO 数据。
 */
@EnabledIfEnvironmentVariable(named = "ATTACHMENT_OBJECT_STORAGE_MINIO_ENABLED", matches = "true")
class AttachmentObjectStorageMinioIntegrationTest {
    @Test
    fun `production adapter fails closed for missing bucket and supports versioned object lifecycle`() {
        val endpoint = requireNotNull(System.getenv("ATTACHMENT_OBJECT_STORAGE_MINIO_ENDPOINT"))
        val endpointUri = URI(endpoint)
        require(endpointUri.host in setOf("127.0.0.1", "localhost")) {
            "ATTACHMENT_OBJECT_STORAGE_MINIO_ENDPOINT_NOT_LOOPBACK"
        }
        val accessKey = requireNotNull(System.getenv("ATTACHMENT_OBJECT_STORAGE_MINIO_ACCESS_KEY"))
        val secretKey = requireNotNull(System.getenv("ATTACHMENT_OBJECT_STORAGE_MINIO_SECRET_KEY"))
        val region = System.getenv("ATTACHMENT_OBJECT_STORAGE_MINIO_REGION") ?: "us-east-1"
        val bucket = "p1a-${UUID.randomUUID()}"
        val missingBucket = "$bucket-missing"
        val key = AttachmentObjectKey("verification/current-adapter.txt")
        val firstContent = "p1a-real-minio-first".toByteArray()
        val secondContent = "p1a-real-minio-second".toByteArray()

        fixtureClient(endpointUri, region, accessKey, secretKey).use { fixture ->
            fixture.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            var primaryFailure: Throwable? = null
            try {
                fixture.putBucketVersioning(
                    PutBucketVersioningRequest.builder()
                        .bucket(bucket)
                        .versioningConfiguration(
                            VersioningConfiguration.builder().status(BucketVersioningStatus.ENABLED).build()
                        )
                        .build()
                )
                productionStorage(endpoint, region, bucket, accessKey, secretKey).use { storage ->
                    assertNull(storage.head(AttachmentObjectKey("verification/missing.txt")))

                    val first = storage.put(putCommand(key, firstContent))
                    assertNotNull(first.version)
                    assertEquals(firstContent.size.toLong(), storage.head(key)?.contentLength)
                    storage.read(key).use { read ->
                        assertArrayEquals(firstContent, read.content.readAllBytes())
                    }

                    val second = storage.put(putCommand(key, secondContent))
                    assertNotNull(second.version)
                    assertNotEquals(first.version, second.version)

                    storage.delete(DeleteAttachmentObjectCommand(key, first.version))
                    assertThrows(S3Exception::class.java) {
                        fixture.getObject(
                            GetObjectRequest.builder()
                                .bucket(bucket)
                                .key(key.value)
                                .versionId(first.version)
                                .build()
                        )
                    }
                    storage.delete(DeleteAttachmentObjectCommand(key, first.version))
                    storage.delete(DeleteAttachmentObjectCommand(AttachmentObjectKey("verification/never-created.txt")))

                    storage.read(key).use { read ->
                        assertArrayEquals(secondContent, read.content.readAllBytes())
                    }
                }

                productionStorage(endpoint, region, missingBucket, accessKey, secretKey).use { missingStorage ->
                    assertThrows(S3Exception::class.java) { missingStorage.head(key) }
                    assertThrows(S3Exception::class.java) {
                        missingStorage.delete(DeleteAttachmentObjectCommand(key))
                    }
                }
            } catch (error: Throwable) {
                primaryFailure = error
                throw error
            } finally {
                try {
                    deleteAllVersions(fixture, bucket)
                    fixture.deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
                } catch (cleanupError: Throwable) {
                    primaryFailure?.addSuppressed(cleanupError) ?: throw cleanupError
                }
            }
        }
    }

    private fun productionStorage(
        endpoint: String,
        region: String,
        bucket: String,
        accessKey: String,
        secretKey: String
    ) = AttachmentObjectStorageConfiguration().s3AttachmentObjectStorage(
        AttachmentObjectStorageProperties().apply {
            enabled = true
            this.endpoint = endpoint
            this.region = region
            this.bucket = bucket
            pathStyleAccess = true
            allowInsecureEndpoint = true
            this.accessKey = accessKey
            this.secretKey = secretKey
            failClosedWhenEnabledAndIncomplete()
        }
    )

    private fun fixtureClient(endpoint: URI, region: String, accessKey: String, secretKey: String): S3Client =
        S3Client.builder()
            .endpointOverride(endpoint)
            .region(Region.of(region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            )
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build()

    private fun putCommand(key: AttachmentObjectKey, content: ByteArray) = PutAttachmentObjectCommand(
        key = key,
        content = ByteArrayInputStream(content),
        contentLength = content.size.toLong(),
        contentType = "text/plain"
    )

    private fun deleteAllVersions(client: S3Client, bucket: String) {
        var keyMarker: String? = null
        var versionIdMarker: String? = null
        do {
            val request = ListObjectVersionsRequest.builder().bucket(bucket).also { builder ->
                keyMarker?.let(builder::keyMarker)
                versionIdMarker?.let(builder::versionIdMarker)
            }.build()
            val response = client.listObjectVersions(request)
            response.versions().forEach { version ->
                client.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(version.key())
                        .versionId(version.versionId())
                        .build()
                )
            }
            response.deleteMarkers().forEach { marker ->
                client.deleteObject(
                    DeleteObjectRequest.builder()
                        .bucket(bucket)
                        .key(marker.key())
                        .versionId(marker.versionId())
                        .build()
                )
            }
            keyMarker = response.nextKeyMarker()
            versionIdMarker = response.nextVersionIdMarker()
        } while (response.isTruncated)
    }
}
