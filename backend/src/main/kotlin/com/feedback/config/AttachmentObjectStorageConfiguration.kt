package com.feedback.config

import com.feedback.repository.remote.attachment.S3CompatibleAttachmentObjectStorage
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import java.net.URI
import org.springframework.beans.factory.SmartInitializingSingleton
import com.feedback.model.attachment.AttachmentObjectKey

@Configuration
class AttachmentObjectStorageConfiguration {
    @Bean
    @ConditionalOnExpression(
        "'\${app.attachment.object-storage.enabled:false}' == 'true'"
    )
    /** 构造仅使用显式静态凭据的私有 S3 客户端，端点与桶已由属性类启动校验。 */
    fun s3AttachmentObjectStorage(properties: AttachmentObjectStorageProperties): S3CompatibleAttachmentObjectStorage {
        val builder = S3Client.builder()
            .region(Region.of(properties.region))
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(properties.pathStyleAccess).build())
        if (properties.endpoint.isNotBlank()) {
            builder.endpointOverride(URI(properties.endpoint))
        }
        if (properties.accessKey.isNotBlank()) {
            builder.credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(properties.accessKey, properties.secretKey))
            )
        }
        return S3CompatibleAttachmentObjectStorage(builder.build(), properties.bucket)
    }

    /** 配置的私有桶无法访问或探测时阻止应用启动。 */
    @Bean
    @ConditionalOnExpression("'\${app.attachment.object-storage.enabled:false}' == 'true'")
    fun attachmentObjectStorageReadiness(storage: S3CompatibleAttachmentObjectStorage): SmartInitializingSingleton =
        SmartInitializingSingleton {
            storage.head(AttachmentObjectKey("readiness/nonexistent-probe"))
        }
}
