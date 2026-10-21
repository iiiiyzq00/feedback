package com.feedback.repository.remote.attachment

import com.feedback.common.ErrorCode
import com.feedback.common.exception.RemoteException
import com.feedback.repository.attachment.*
import com.feedback.model.attachment.*
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * S3 兼容附件对象存储适配器。
 *
 * 适配器不生成永久公开 URL。不存在只在 `head` 中转换为 `null`；读写、鉴权、网络和服务端错误
 * 均向上抛出，使调用用例能够失败关闭并记录审计。对象级缺失错误在转换前额外确认桶存在，
 * 避免 MinIO 将缺桶伪装成 `NoSuchKey`；删除只在桶可确认存在时采用幂等缺失语义。
 */
class S3CompatibleAttachmentObjectStorage(
    private val client: S3Client,
    private val bucket: String
) : AttachmentObjectStorage, AutoCloseable {
    init {
        if (bucket.isBlank()) {
            throw RemoteException(ErrorCode.ATTACHMENT_STORAGE_UNAVAILABLE, "ATTACHMENT_OBJECT_BUCKET_BLANK")
        }
    }

    /** 通过同步 S3 客户端上传流，不生成永久公开 URL。 */
    override fun put(command: PutAttachmentObjectCommand): StoredAttachmentObject {
        val requestBuilder = PutObjectRequest.builder()
            .bucket(bucket)
            .key(command.key.value)
            .contentLength(command.contentLength)
            .contentType(command.contentType)
        command.checksumSha256Base64?.let(requestBuilder::checksumSHA256)
        val response = client.putObject(
            requestBuilder.build(),
            RequestBody.fromInputStream(command.content, command.contentLength)
        )
        return StoredAttachmentObject(
            backend = AttachmentStorageBackend.S3_COMPATIBLE,
            bucket = bucket,
            key = command.key,
            version = response.versionId(),
            etag = response.eTag()
        )
    }

    /** 读取对象元数据；仅在确认桶存在后把明确缺对象转换为 null。 */
    override fun head(key: AttachmentObjectKey): AttachmentObjectMetadata? = try {
        val response = client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key.value).build())
        AttachmentObjectMetadata(
            backend = AttachmentStorageBackend.S3_COMPATIBLE,
            bucket = bucket,
            key = key,
            version = response.versionId(),
            etag = response.eTag(),
            contentLength = requireContentLength(response.contentLength()),
            contentType = response.contentType(),
            lastModified = response.lastModified()
        )
    } catch (error: NoSuchKeyException) {
        verifyBucketExistsBeforeMissingTargetConversion(error)
        null
    } catch (error: S3Exception) {
        if (!error.isDefiniteMissingKey()) {
            throw error
        }
        verifyBucketExistsBeforeMissingTargetConversion(error)
        null
    }

    /** 打开私有对象网络流，元数据解析失败时主动关闭响应流。 */
    override fun read(key: AttachmentObjectKey): AttachmentObjectRead {
        val response: ResponseInputStream<GetObjectResponse> = client.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key.value).build()
        )
        val metadata = try {
            AttachmentObjectMetadata(
                backend = AttachmentStorageBackend.S3_COMPATIBLE,
                bucket = bucket,
                key = key,
                version = response.response().versionId(),
                etag = response.response().eTag(),
                contentLength = requireContentLength(response.response().contentLength()),
                contentType = response.response().contentType(),
                lastModified = response.response().lastModified()
            )
        } catch (error: Throwable) {
            runCatching { response.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
        return AttachmentObjectRead(metadata, response)
    }

    /** 幂等删除精确对象；版本缺失只在确认桶存在后视为成功。 */
    override fun delete(command: DeleteAttachmentObjectCommand) {
        val builder = DeleteObjectRequest.builder().bucket(bucket).key(command.key.value)
        command.version?.let(builder::versionId)
        try {
            client.deleteObject(builder.build())
        } catch (error: NoSuchKeyException) {
            verifyBucketExistsBeforeMissingTargetConversion(error)
        } catch (error: S3Exception) {
            if (!error.isDefiniteMissingDeleteTarget(command.version)) {
                throw error
            }
            verifyBucketExistsBeforeMissingTargetConversion(error)
        }
    }

    /** 关闭底层 S3 客户端及其连接资源。 */
    override fun close() = client.close()

    /** 校验远端内容长度存在且非负，异常时失败关闭。 */
    private fun requireContentLength(value: Long?): Long {
        val length = value ?: throw RemoteException(
            ErrorCode.ATTACHMENT_STORAGE_UNAVAILABLE,
            "ATTACHMENT_OBJECT_CONTENT_LENGTH_MISSING"
        )
        if (length < 0) {
            throw RemoteException(
                ErrorCode.ATTACHMENT_STORAGE_UNAVAILABLE,
                "ATTACHMENT_OBJECT_CONTENT_LENGTH_INVALID"
            )
        }
        return length
    }

    /**
     * MinIO 的对象 HEAD 可能把缺桶也报告为 `NoSuchKey`。只有 `HeadBucket` 成功才能把原错误转换为
     * 对象缺失；缺桶、权限或网络错误必须成为主异常，原对象错误作为 suppressed 事实保留。
     */
    private fun verifyBucketExistsBeforeMissingTargetConversion(missingTargetError: S3Exception) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
        } catch (bucketVerificationError: Throwable) {
            bucketVerificationError.addSuppressed(missingTargetError)
            throw bucketVerificationError
        }
    }

    /** HTTP 404 和对象错误码都可能掩盖缺桶；此判断之后仍必须验证桶存在。 */
    private fun S3Exception.isDefiniteMissingKey(): Boolean =
        awsErrorDetails()?.errorCode() == "NoSuchKey"

    /** 判断精确对象或指定版本是否明确不存在；调用后仍须确认桶存在。 */
    private fun S3Exception.isDefiniteMissingDeleteTarget(version: String?): Boolean =
        isDefiniteMissingKey() || (version != null && awsErrorDetails()?.errorCode() == "NoSuchVersion")
}
