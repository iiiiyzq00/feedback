package com.feedback.integration

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.auth.AuthFeishuOrgFactMapper
import com.feedback.mapper.auth.AuthFeishuSyncRunMapper
import com.feedback.mapper.auth.AuthUserMapper
import com.feedback.model.po.auth.AuthUserPo
import com.feedback.mapper.sdk.SdkTransportTelemetryMapper
import com.feedback.model.po.sdk.SdkTransportTelemetryPo
import com.feedback.repository.auth.AuthUserRepository
import com.feedback.repository.auth.FeishuPolicyRepository
import com.feedback.repository.impl.auth.MyBatisFeishuPolicyRepositoryImpl
import com.feedback.model.auth.FeishuSyncStatus
import com.feedback.repository.impl.sdk.MyBatisSdkTelemetryRepositoryImpl
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.session.Configuration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.SqlSessionTemplate
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * P0 分层收口后的真实 MySQL 契约。
 *
 * 测试只允许显式连接 feedback_platform_test；所有业务写入均经过正式 Repository/Mapper，
 * 测试探针只按本用例生成的唯一键读取和清理，不执行 Flyway clean。
 */
@EnabledIfEnvironmentVariable(named = "P0_MYBATIS_MYSQL_ENABLED", matches = "true")
class P0MyBatisMysqlContractTest {
    @Test
    fun `Feishu synchronization start lock remains held until the creating transaction completes`() {
        P0MyBatisMysqlContext.fromEnvironment().use { context ->
            context.flyway.migrate()
            val probes = context.mapper(P0MyBatisMysqlProbeMapper::class.java)
            val users = AuthUserRepository(context.mapper(AuthUserMapper::class.java))
            val policies = MyBatisFeishuPolicyRepositoryImpl(
                context.mapper(AuthFeishuSyncRunMapper::class.java),
                context.mapper(com.feedback.mapper.auth.AuthPolicyDataPermissionMapper::class.java),
                context.mapper(AuthFeishuOrgFactMapper::class.java)
            )
            val userId = context.inTransaction {
                users.createLocal(
                    AuthUserPo(
                        username = "p0-sync-lock-${UUID.randomUUID()}",
                        displayName = "P0 Sync Lock MySQL Contract",
                        passwordHash = "not-a-login-hash",
                        authProvider = "LOCAL"
                    )
                )
            }
            val executor = Executors.newFixedThreadPool(2)
            val firstRunCreated = CountDownLatch(1)
            val allowFirstCommit = CountDownLatch(1)
            var firstRun: Long? = null
            try {
                val first = executor.submit<Long> {
                    context.inTransaction {
                        policies.startExclusive(userId, LocalDateTime.now().minusHours(6)).also {
                            firstRunCreated.countDown()
                            check(allowFirstCommit.await(5, TimeUnit.SECONDS)) {
                                "P0_FEISHU_FIRST_SYNC_COMMIT_TIMEOUT"
                            }
                        }
                    }
                }
                check(firstRunCreated.await(5, TimeUnit.SECONDS)) { "P0_FEISHU_FIRST_SYNC_START_TIMEOUT" }
                val error = executor.submit<ServiceException> {
                    assertThrows(ServiceException::class.java) {
                        context.inTransaction {
                            policies.startExclusive(userId, LocalDateTime.now().minusHours(6))
                        }
                    }
                }.get(5, TimeUnit.SECONDS)
                assertEquals(ErrorCode.FEISHU_SYNC_ALREADY_RUNNING, error.errorCode)
                allowFirstCommit.countDown()
                firstRun = first.get(5, TimeUnit.SECONDS)
                assertEquals(1, probes.countSyncRunsByRequester(userId))
            } finally {
                allowFirstCommit.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "P0_FEISHU_LOCK_EXECUTOR_DID_NOT_TERMINATE" }
                context.inTransaction {
                    firstRun?.let { check(probes.deleteSyncRun(it) == 1) }
                    check(probes.deleteUser(userId) == 1) { "P0_SYNC_LOCK_USER_CLEANUP_INCOMPLETE" }
                }
            }
        }
    }

    @Test
    fun `Feishu synchronization rejects a fresh run and safely takes over a stale run`() {
        P0MyBatisMysqlContext.fromEnvironment().use { context ->
            context.flyway.migrate()
            val probes = context.mapper(P0MyBatisMysqlProbeMapper::class.java)
            val users = AuthUserRepository(context.mapper(AuthUserMapper::class.java))
            val policies = MyBatisFeishuPolicyRepositoryImpl(
                context.mapper(AuthFeishuSyncRunMapper::class.java),
                context.mapper(com.feedback.mapper.auth.AuthPolicyDataPermissionMapper::class.java),
                context.mapper(AuthFeishuOrgFactMapper::class.java)
            )
            val userId = context.inTransaction {
                users.createLocal(
                    AuthUserPo(
                        username = "p0-takeover-${UUID.randomUUID()}",
                        displayName = "P0 Sync Takeover MySQL Contract",
                        passwordHash = "not-a-login-hash",
                        authProvider = "LOCAL"
                    )
                )
            }
            val firstRun = context.inTransaction {
                policies.startExclusive(userId, LocalDateTime.now().minusHours(6))
            }
            var duplicateStaleRun: Long? = null
            var replacementRun: Long? = null
            try {
                val conflict = assertThrows(ServiceException::class.java) {
                    context.inTransaction {
                        policies.startExclusive(userId, LocalDateTime.now().minusHours(6))
                    }
                }
                assertEquals(ErrorCode.FEISHU_SYNC_ALREADY_RUNNING, conflict.errorCode)
                assertEquals(1, probes.ageSyncRun(firstRun, LocalDateTime.now().minusHours(7)))
                val duplicate = context.inTransaction { policies.start(userId) }
                duplicateStaleRun = duplicate
                assertEquals(1, probes.ageSyncRun(duplicate, LocalDateTime.now().minusHours(8)))

                replacementRun = context.inTransaction {
                    policies.startExclusive(userId, LocalDateTime.now().minusHours(6))
                }

                val expired = probes.findSyncRun(firstRun)
                assertEquals("FAILED", expired?.runStatus)
                assertEquals("FEISHU_SYNC_STALE", expired?.errorCode)
                assertNotNull(expired?.finishedAt)
                assertEquals("FAILED", probes.findSyncRun(duplicate)?.runStatus)
                assertEquals("RUNNING", probes.findSyncRun(replacementRun)?.runStatus)
            } finally {
                context.inTransaction {
                    replacementRun?.let { check(probes.deleteSyncRun(it) == 1) }
                    duplicateStaleRun?.let { check(probes.deleteSyncRun(it) == 1) }
                    check(probes.deleteSyncRun(firstRun) == 1) { "P0_STALE_SYNC_RUN_CLEANUP_INCOMPLETE" }
                    check(probes.deleteUser(userId) == 1) { "P0_SYNC_TAKEOVER_USER_CLEANUP_INCOMPLETE" }
                }
            }
        }
    }

    @Test
    fun `Feishu failure upsert preserves successful facts and joins caller rollback`() {
        P0MyBatisMysqlContext.fromEnvironment().use { context ->
            context.flyway.migrate()
            val probes = context.mapper(P0MyBatisMysqlProbeMapper::class.java)
            val users = AuthUserRepository(context.mapper(AuthUserMapper::class.java))
            val policies = MyBatisFeishuPolicyRepositoryImpl(
                context.mapper(AuthFeishuSyncRunMapper::class.java),
                context.mapper(com.feedback.mapper.auth.AuthPolicyDataPermissionMapper::class.java),
                context.mapper(AuthFeishuOrgFactMapper::class.java)
            )
            val userId = context.inTransaction {
                users.createLocal(
                    AuthUserPo(
                        username = "p0-feishu-${UUID.randomUUID()}",
                        displayName = "P0 Feishu MySQL Contract",
                        passwordHash = "not-a-login-hash",
                        authProvider = "LOCAL"
                    )
                )
            }
            try {
                context.inTransaction {
                    policies.saveFacts(userId, "[\"group-p0\"]", "[\"dept-p0\"]", active = true)
                    policies.markFailure(userId, "FEISHU_TIMEOUT_FIRST")
                }

                assertEquals(
                    P0FeishuOrgFactProbe(1, "group-p0", 1, "dept-p0", "ACTIVE", "FEISHU_TIMEOUT_FIRST"),
                    probes.findOrgFact(userId)
                )

                assertThrows(IllegalStateException::class.java) {
                    context.inTransaction {
                        policies.markFailure(userId, "MUST_ROLL_BACK")
                        error("ROLL_BACK_P0_FEISHU_FACT")
                    }
                }
                assertEquals("FEISHU_TIMEOUT_FIRST", probes.findOrgFact(userId)?.lastErrorCode)
            } finally {
                context.inTransaction {
                    probes.deleteOrgFact(userId)
                    check(probes.deleteUser(userId) == 1) { "P0_FEISHU_USER_CLEANUP_INCOMPLETE" }
                }
            }
        }
    }

    @Test
    fun `Feishu sync finish persists the first error summary and counters`() {
        P0MyBatisMysqlContext.fromEnvironment().use { context ->
            context.flyway.migrate()
            val probes = context.mapper(P0MyBatisMysqlProbeMapper::class.java)
            val users = AuthUserRepository(context.mapper(AuthUserMapper::class.java))
            val policies = MyBatisFeishuPolicyRepositoryImpl(
                context.mapper(AuthFeishuSyncRunMapper::class.java),
                context.mapper(com.feedback.mapper.auth.AuthPolicyDataPermissionMapper::class.java),
                context.mapper(AuthFeishuOrgFactMapper::class.java)
            )
            val userId = context.inTransaction {
                users.createLocal(
                    AuthUserPo(
                        username = "p0-sync-${UUID.randomUUID()}",
                        displayName = "P0 Sync MySQL Contract",
                        passwordHash = "not-a-login-hash",
                        authProvider = "LOCAL"
                    )
                )
            }
            val runId = context.inTransaction { policies.start(userId) }
            try {
                assertThrows(IllegalStateException::class.java) {
                    context.inTransaction {
                        policies.finish(runId, FeishuSyncStatus.FAILED, 99, 0, 0, 99, "MUST_ROLL_BACK")
                        error("ROLL_BACK_P0_SYNC_RUN")
                    }
                }
                assertEquals("RUNNING", probes.findSyncRun(runId)?.runStatus)

                context.inTransaction {
                    policies.finish(runId, FeishuSyncStatus.PARTIAL_SUCCESS, 7, 3, 1, 2, "FEISHU_DIRECTORY_TIMEOUT")
                }
                val stored = probes.findSyncRun(runId)
                assertNotNull(stored?.finishedAt)
                assertEquals(
                    P0FeishuSyncRunProbe("PARTIAL_SUCCESS", 7, 3, 1, 2, "FEISHU_DIRECTORY_TIMEOUT", stored?.finishedAt),
                    stored
                )
            } finally {
                context.inTransaction {
                    check(probes.deleteSyncRun(runId) == 1) { "P0_FEISHU_RUN_CLEANUP_INCOMPLETE" }
                    check(probes.deleteUser(userId) == 1) { "P0_SYNC_USER_CLEANUP_INCOMPLETE" }
                }
            }
        }
    }

    @Test
    fun `SDK telemetry uses one multi value statement and rolls back as a unit`() {
        P0MyBatisMysqlContext.fromEnvironment().use { context ->
            context.flyway.migrate()
            val probes = context.mapper(P0MyBatisMysqlProbeMapper::class.java)
            val repository = MyBatisSdkTelemetryRepositoryImpl(
                context.mapper(SdkTransportTelemetryMapper::class.java),
                context.mapper(com.feedback.mapper.sdk.SdkRequestMetricMapper::class.java)
            )
            val targetIds = List(3) { "p0-sdk-${UUID.randomUUID()}" }
            assertThrows(IllegalStateException::class.java) {
                context.inTransaction {
                    repository.insertTransportEvents(
                        targetIds.mapIndexed { index, targetId ->
                            SdkTransportTelemetryPo(
                                targetId = targetId,
                                detailJson = "{\"sequence\":$index}",
                                ipAddress = "127.0.0.1"
                            )
                        }
                    )
                    assertEquals(3, probes.countTelemetry(targetIds))
                    error("ROLL_BACK_P0_SDK_TELEMETRY")
                }
            }
            assertEquals(0, probes.countTelemetry(targetIds))
        }
    }
}

private class P0MyBatisMysqlContext private constructor(
    val flyway: Flyway,
    private val dataSource: HikariDataSource,
    private val sessions: SqlSessionTemplate,
    private val transactions: TransactionTemplate
) : AutoCloseable {
    fun <T> mapper(type: Class<T>): T = sessions.getMapper(type)

    fun <T> inTransaction(block: () -> T): T = requireNotNull(
        transactions.execute { P0TransactionValue(block()) }
    ).value

    override fun close() = dataSource.close()

    companion object {
        fun fromEnvironment(): P0MyBatisMysqlContext {
            val url = requireNotNull(System.getenv("P0_MYBATIS_MYSQL_URL"))
            val databaseName = url.substringBefore('?').substringAfterLast('/')
            require(databaseName == "feedback_platform_test") { "P0_MYBATIS_DATABASE_FORBIDDEN:$databaseName" }
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    username = System.getenv("P0_MYBATIS_MYSQL_USERNAME") ?: "root"
                    password = System.getenv("P0_MYBATIS_MYSQL_PASSWORD") ?: ""
                    maximumPoolSize = 4
                    poolName = "p0-mybatis-mysql-contract"
                }
            )
            return try {
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                val resolver = PathMatchingResourcePatternResolver()
                val sessionFactory = SqlSessionFactoryBean().apply {
                    setDataSource(dataSource)
                    setConfiguration(Configuration().apply { isMapUnderscoreToCamelCase = true })
                    setMapperLocations(
                        *listOf(
                            "classpath:mybatis/mapper/auth/AuthUserMapper.xml",
                            "classpath:mybatis/mapper/auth/AuthFeishuSyncRunMapper.xml",
                            "classpath:mybatis/mapper/auth/AuthPolicyDataPermissionMapper.xml",
                            "classpath:mybatis/mapper/auth/AuthFeishuOrgFactMapper.xml",
                            "classpath:mybatis/mapper/sdk/SdkTransportTelemetryMapper.xml",
                            "classpath:mybatis/mapper/sdk/SdkRequestMetricMapper.xml",
                            "classpath:mybatis/mapper/p0/P0MyBatisMysqlProbeMapper.xml"
                        ).flatMap { resolver.getResources(it).asList() }.toTypedArray()
                    )
                    afterPropertiesSet()
                }.`object` ?: error("P0_MYBATIS_FACTORY_MISSING")
                P0MyBatisMysqlContext(
                    flyway,
                    dataSource,
                    SqlSessionTemplate(sessionFactory),
                    TransactionTemplate(DataSourceTransactionManager(dataSource))
                )
            } catch (error: Throwable) {
                dataSource.close()
                throw error
            }
        }
    }
}

private data class P0TransactionValue<T>(val value: T)

internal data class P0FeishuOrgFactProbe(
    val groupCount: Int,
    val firstGroup: String?,
    val departmentCount: Int,
    val firstDepartment: String?,
    val employmentStatus: String,
    val lastErrorCode: String?
)

internal data class P0FeishuSyncRunProbe(
    val runStatus: String,
    val totalUsers: Int,
    val changedUsers: Int,
    val disabledUsers: Int,
    val failedUsers: Int,
    val errorCode: String?,
    val finishedAt: java.time.LocalDateTime?
)

internal interface P0MyBatisMysqlProbeMapper {
    fun findOrgFact(userId: Long): P0FeishuOrgFactProbe?
    fun findSyncRun(id: Long): P0FeishuSyncRunProbe?
    fun countSyncRunsByRequester(requestedBy: Long): Int
    fun ageSyncRun(@Param("id") id: Long, @Param("startedAt") startedAt: LocalDateTime): Int
    fun countTelemetry(@Param("targetIds") targetIds: List<String>): Int
    fun deleteOrgFact(userId: Long): Int
    fun deleteSyncRun(id: Long): Int
    fun deleteUser(id: Long): Int
}
