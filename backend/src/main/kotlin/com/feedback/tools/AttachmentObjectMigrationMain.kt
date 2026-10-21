package com.feedback.tools

import com.feedback.manager.attachment.AttachmentLocalCopyCleanup
import com.feedback.manager.attachment.AttachmentObjectMigrationWorker
import com.feedback.manager.attachment.AttachmentObjectPublisher
import com.feedback.repository.impl.attachment.JsonLineAttachmentMigrationAudit
import com.feedback.config.AttachmentObjectStorageConfiguration
import com.feedback.config.AttachmentObjectStorageProperties
import com.feedback.mapper.attachment.FeedbackAttachmentMapper
import com.feedback.repository.attachment.AttachmentObjectLifecycleRepository
import com.feedback.repository.impl.attachment.MyBatisAttachmentObjectLifecycleRepositoryImpl
import com.feedback.repository.impl.attachment.MyBatisSecureAttachmentRepositoryImpl
import com.feedback.security.attachment.AttachmentLocalPathPolicy
import com.feedback.security.attachment.PlatformAttachmentStorageCrypto
import com.feedback.repository.attachment.AttachmentMigrationAudit
import org.mybatis.spring.annotation.MapperScan
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile

/**
 * 仅用于附件对象迁移的非 Web 进程，避免无关业务 Runner 或调度任务竞争内存与 CPU。
 *
 * 该配置位于主应用的组件扫描包下，因此必须用专用 Profile 隔离。若缺少此隔离，这里的窄范围
 * [MapperScan] 会取代 Web 进程的 MyBatis 自动扫描，导致其他仓储缺少 Mapper Bean。下方命令入口会
 * 显式启用该 Profile，普通 Web 启动不得加载本配置。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableConfigurationProperties(AttachmentObjectStorageProperties::class)
@Profile("attachment-migration")
@MapperScan(basePackageClasses = [FeedbackAttachmentMapper::class])
@Import(
    AttachmentObjectStorageConfiguration::class,
    PlatformAttachmentStorageCrypto::class,
    AttachmentLocalPathPolicy::class,
    JsonLineAttachmentMigrationAudit::class,
    MyBatisSecureAttachmentRepositoryImpl::class,
    MyBatisAttachmentObjectLifecycleRepositoryImpl::class,
    AttachmentObjectPublisher::class,
    AttachmentLocalCopyCleanup::class,
    AttachmentObjectMigrationWorker::class
)
class AttachmentObjectMigrationApplication {
    /** 循环执行有界迁移与清理，停滞时按配置退避，未闭合删除意图会阻止成功退出。 */
    @Bean
    fun migrationUntilEmpty(
        repository: AttachmentObjectLifecycleRepository,
        worker: AttachmentObjectMigrationWorker,
        properties: AttachmentObjectStorageProperties,
        audit: AttachmentMigrationAudit
    ) = ApplicationRunner {
        check(properties.enabled && properties.migrationEnabled && !properties.migrationDryRun) {
            "ATTACHMENT_OBJECT_MIGRATION_NOT_LIVE"
        }
        val logger = LoggerFactory.getLogger("AttachmentObjectMigrationCommand")
        var batch = 0L
        while (true) {
            val migrationPending = repository.findObjectMigrationCandidates(0, 1).isNotEmpty()
            val cleanupPending = properties.cleanupLocalAfterPublish &&
                repository.findObjectCleanupCandidates(1).isNotEmpty()
            if (!migrationPending && !cleanupPending) {
                val unclosedDeleteIntents = audit.countUnclosedDeleteIntents()
                check(unclosedDeleteIntents == 0L) {
                    "ATTACHMENT_MIGRATION_UNCLOSED_DELETE_INTENTS:$unclosedDeleteIntents"
                }
                logger.info(
                    "Attachment migration command complete batches={} candidates=0 cleanup_candidates=0 source_unavailable={} unclosed_delete_intents=0",
                    batch,
                    repository.countObjectMigrationSourceUnavailable()
                )
                break
            }
            batch++
            val beforeMigrationId = repository.findObjectMigrationCandidates(0, 1).firstOrNull()?.id
            val beforeCleanupId = repository.findObjectCleanupCandidates(1).firstOrNull()?.id
            worker.migrateBatch()
            val stalled = beforeMigrationId == repository.findObjectMigrationCandidates(0, 1).firstOrNull()?.id &&
                beforeCleanupId == repository.findObjectCleanupCandidates(1).firstOrNull()?.id
            if (stalled) {
                Thread.sleep(properties.migrationDelayMs)
            }
            if (batch % 10L == 0L) {
                logger.info(
                    "Attachment migration command checkpoint batches={} migration_pending={} cleanup_pending={}",
                    batch, migrationPending, cleanupPending
                )
            }
        }
    }
}

/** 以独立 profile 启动附件迁移命令并在完成后关闭 Spring 上下文。 */
fun main(args: Array<String>) {
    runApplication<AttachmentObjectMigrationApplication>(*args) {
        setAdditionalProfiles("attachment-migration")
    }.close()
}
