package com.feedback.integration

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.apache.ibatis.annotations.Param
import org.apache.ibatis.session.Configuration
import org.flywaydb.core.Flyway
import org.mybatis.spring.SqlSessionFactoryBean
import org.mybatis.spring.SqlSessionTemplate
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * P3-01 专用 MySQL 测试上下文。数据库访问全部通过强类型 MyBatis Mapper，且只允许固定测试库。
 */
internal class ApplicationIdentityMysqlTestContext private constructor(
    val databaseName: String,
    val flyway: Flyway,
    private val dataSource: HikariDataSource,
    private val sessions: SqlSessionTemplate,
    private val transactions: TransactionTemplate
) : AutoCloseable {
    fun <T> mapper(type: Class<T>): T = sessions.getMapper(type)

    fun <T> inTransaction(block: () -> T): T = requireNotNull(
        transactions.execute { TransactionValue(block()) }
    ).value

    override fun close() {
        dataSource.close()
    }

    companion object {
        fun fromEnvironment(): ApplicationIdentityMysqlTestContext {
            val url = requireNotNull(System.getenv("APPLICATION_IDENTITY_MYSQL_URL"))
            val databaseName = url.substringBefore('?').substringAfterLast('/')
            require(databaseName == "feedback_platform_test") { "APPLICATION_IDENTITY_MYSQL_DATABASE_FORBIDDEN" }
            val username = System.getenv("APPLICATION_IDENTITY_MYSQL_USERNAME") ?: "root"
            val password = System.getenv("APPLICATION_IDENTITY_MYSQL_PASSWORD") ?: ""
            val dataSource = HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = url
                    this.username = username
                    this.password = password
                    maximumPoolSize = 6
                    poolName = "application-identity-mysql-test"
                }
            )
            return try {
                val flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                val sessionFactory = SqlSessionFactoryBean().apply {
                    setDataSource(dataSource)
                    setConfiguration(Configuration().apply { isMapUnderscoreToCamelCase = true })
                    setMapperLocations(
                        *PathMatchingResourcePatternResolver()
                            .getResources("classpath*:mybatis/mapper/applicationidentity/*.xml")
                    )
                    afterPropertiesSet()
                }.`object` ?: error("APPLICATION_IDENTITY_MYBATIS_FACTORY_MISSING")
                ApplicationIdentityMysqlTestContext(
                    databaseName,
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

private data class TransactionValue<T>(val value: T)

/** 只读取 information_schema.tables，验证迁移后的物理表预算。 */
internal interface ApplicationIdentitySchemaTableTestMapper {
    fun countBusinessTables(@Param("schemaName") schemaName: String): Int
    fun countNamedTables(@Param("schemaName") schemaName: String, @Param("tableNames") tableNames: List<String>): Int
}

/** 只读取 Flyway 历史表，验证 V237 已成功且唯一。 */
internal interface ApplicationIdentityFlywayHistoryTestMapper {
    fun countSuccessfulVersion(@Param("version") version: String): Int
}

/** 只清理本测试唯一 alias 键对应的版本历史，不触碰共享 alias。 */
internal interface ApplicationIdentityAliasCleanupTestMapper {
    fun deleteByKey(
        @Param("sourceChannel") sourceChannel: String,
        @Param("aliasType") aliasType: String,
        @Param("normalizedAlias") normalizedAlias: String
    ): Int
}

/** 只清理本测试生成的应用公开 ID，不触碰共享身份。 */
internal interface ApplicationIdentityCleanupTestMapper {
    fun deleteByPublicIds(@Param("publicIds") publicIds: List<String>): Int
}
