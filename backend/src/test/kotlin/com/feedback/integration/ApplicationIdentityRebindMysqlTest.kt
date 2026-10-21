package com.feedback.integration

import com.feedback.repository.applicationidentity.*
import com.feedback.model.applicationidentity.*
import com.feedback.audit.BusinessAuditService
import com.feedback.mapper.applicationidentity.ApplicationIdentityAliasMapper
import com.feedback.mapper.applicationidentity.ApplicationIdentityMapper
import com.feedback.repository.applicationidentity.ApplicationIdentityRepository
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import com.feedback.model.applicationidentity.NewApplicationIdentity
import com.feedback.model.applicationidentity.ApplicationAliasRebindCommand
import com.feedback.manager.applicationidentity.ApplicationIdentityRebindWorker
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 真实 MySQL 并发合同：同 requestId 的两个事务只能形成一次写入和一次稳定重放。 */
@EnabledIfEnvironmentVariable(named = "APPLICATION_IDENTITY_MYSQL_ENABLED", matches = "true")
class ApplicationIdentityRebindMysqlTest {
    @Test
    fun `concurrent identical request replays the committed result instead of returning a version conflict`() {
        ApplicationIdentityMysqlTestContext.fromEnvironment().use { context ->
            val schema = context.mapper(ApplicationIdentitySchemaTableTestMapper::class.java)
            val history = context.mapper(ApplicationIdentityFlywayHistoryTestMapper::class.java)
            assertEquals(
                3,
                schema.countNamedTables(
                    context.databaseName,
                    listOf(
                        "t_application_identity",
                        "t_application_identity_alias",
                        "t_feedback_primary_problem_fact"
                    )
                )
            )
            assertEquals(1, history.countSuccessfulVersion("237"))
            val identityMapper = context.mapper(ApplicationIdentityMapper::class.java)
            val aliasMapper = context.mapper(ApplicationIdentityAliasMapper::class.java)
            val repository = spyk(MyBatisApplicationIdentityRepositoryImpl(identityMapper, aliasMapper))
            val audits = mockk<BusinessAuditService>()
            every { audits.append(any()) } just runs
            val worker = ApplicationIdentityRebindWorker(repository, audits)
            val sourcePublicId = UUID.randomUUID().toString()
            val targetPublicId = UUID.randomUUID().toString()
            val requestId = UUID.randomUUID().toString()
            val key = ApplicationAliasKey(
                ApplicationSourceChannel.STATION_FEEDBACK,
                ApplicationAliasType.STATION_SOURCE_APP_ID,
                "p3-r1-${UUID.randomUUID()}"
            )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val created = context.inTransaction {
                    repository.createIdentities(
                        listOf(
                            NewApplicationIdentity(sourcePublicId, "并发源应用"),
                            NewApplicationIdentity(targetPublicId, "并发目标应用")
                        )
                    )
                }
                context.inTransaction { repository.createAutoAliases(mapOf(key to created.getValue(sourcePublicId))) }
                val alias = context.inTransaction { repository.activeAliases(listOf(key)).getValue(key) }
                val command = ApplicationAliasRebindCommand(
                    targetApplicationId = targetPublicId,
                    aliasId = alias.publicId,
                    expectedAliasVersion = alias.aliasVersion,
                    expectedTargetVersion = created.getValue(targetPublicId).mappingVersion,
                    requestId = requestId,
                    reason = "并发幂等真实数据库验证"
                )
                val initialReads = AtomicInteger()
                val bothReadMissing = CyclicBarrier(2)
                every { repository.findManualResult(requestId) } answers {
                    val existing = callOriginal()
                    if (initialReads.incrementAndGet() <= 2) {
                        bothReadMissing.await(10, TimeUnit.SECONDS)
                    }
                    existing
                }
                val futures = List(2) {
                    executor.submit<com.feedback.model.applicationidentity.ApplicationAliasRebindResult> {
                        context.inTransaction { worker.rebind(command, 99) }
                    }
                }
                val results = futures.map { it.get(20, TimeUnit.SECONDS) }

                assertEquals(setOf(false, true), results.map { it.replayed }.toSet())
                assertEquals(1, results.map { it.aliasVersion }.distinct().size)
                assertEquals(2L, results.single { !it.replayed }.aliasVersion)
                assertEquals(2L, results.single { !it.replayed }.targetMappingVersion)
                val state = context.inTransaction {
                    val source = requireNotNull(repository.findByPublicId(sourcePublicId))
                    val target = requireNotNull(repository.findByPublicId(targetPublicId))
                    Triple(source, target, repository.aliasesFor(source.id) + repository.aliasesFor(target.id))
                }
                assertEquals(2L, state.first.mappingVersion)
                assertEquals(2L, state.second.mappingVersion)
                assertEquals(2, state.third.size)
                assertEquals(1, state.third.count {
                    it.bindingStatus == ApplicationAliasBindingStatus.ACTIVE && it.aliasVersion == 2L
                })
                assertEquals(1, state.third.count {
                    it.bindingStatus == ApplicationAliasBindingStatus.SUPERSEDED && it.aliasVersion == 1L
                })
                assertTrue(context.inTransaction { repository.findManualResult(requestId) } != null)
                verify(exactly = 1) { audits.append(match { it.requestId == requestId }) }
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(20, TimeUnit.SECONDS)) {
                    "APPLICATION_IDENTITY_CONCURRENCY_TEST_DID_NOT_TERMINATE"
                }
                cleanup(context, key, listOf(sourcePublicId, targetPublicId))
            }
        }
    }

    private fun cleanup(
        context: ApplicationIdentityMysqlTestContext,
        key: ApplicationAliasKey,
        publicIds: List<String>
    ) {
        context.inTransaction {
            val aliases = context.mapper(ApplicationIdentityAliasCleanupTestMapper::class.java)
            val identities = context.mapper(ApplicationIdentityCleanupTestMapper::class.java)
            check(aliases.deleteByKey(key.sourceChannel.name, key.aliasType.name, key.normalizedAlias) == 2) {
                "APPLICATION_IDENTITY_ALIAS_TEST_CLEANUP_INCOMPLETE"
            }
            check(identities.deleteByPublicIds(publicIds) == 2) {
                "APPLICATION_IDENTITY_TEST_CLEANUP_INCOMPLETE"
            }
        }
    }
}
