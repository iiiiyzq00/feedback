package com.feedback.tools

import org.flywaydb.core.Flyway

/** 只把现有 schema 迁移到 V241；不启动 Spring，也不会执行任何业务 Runner。 */
fun main() {
    val url = requiredEnvironment("DB_URL")
    val username = requiredEnvironment("DB_USERNAME")
    val password = requiredEnvironment("DB_PASSWORD")
    val result = Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .target("241")
        .load()
        .migrate()
    check(result.targetSchemaVersion == "241") { "CLUSTER_SHADOW_MIGRATION_TARGET_INVALID" }
    println("CLUSTER_SHADOW_MIGRATION_OK target=241 executed=${result.migrationsExecuted}")
}

private fun requiredEnvironment(name: String): String = System.getenv(name)?.takeIf(String::isNotBlank)
    ?: error("CLUSTER_SHADOW_MIGRATION_ENV_REQUIRED:$name")
