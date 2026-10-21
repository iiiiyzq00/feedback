package com.feedback.agent.rca.ops

import org.flywaydb.core.Flyway

/**
 * 把 `db/migration` 里的迁移应用到**本机 feedback-platform 的 3307 库**
 * （`feedback_platform_local_deploy`），供 `:backend:rcaEntryTest` 对着真库验证。
 *
 * ## 为什么需要它，以及为什么它必须显式触发
 *
 * 生产上这份迁移是应用启动时由 Spring 的 Flyway 自动执行的；本机这个部署库没有
 * 「启动一次应用」这一步，于是库里会停在旧版本上——`V243` 引入的 `trace_id` / `span_id`
 * 两列不存在，而「链路标识真的落进库里了」这件事只有对着真库才验得出来。
 *
 * 它**不是**由 `:backend:rcaEntryTest` 自动带跑的。是刻意不带的：
 * 迁移是**全局副作用**（改的是整库结构），让一次「验证」运行顺手改掉库结构，
 * 会让「跑测试」与「改环境」这两件事混在一起——出了问题时无法判断
 * 是代码不对还是环境被上一次运行改掉了。所以本入口只在人工显式执行时跑，
 * 与 `flywayRepair` 是同一个范式（`JavaExec` + `-Pconfirm...` 确认串）。
 *
 * 同理，本入口**不执行任何字面 SQL**：它只是把 `db/migration` 这个受版本管理的目录
 * 交给 Flyway，执行内容、校验和与历史记录都由 Flyway 负责。绕过 Flyway 手工 `ALTER`
 * 会让库结构与迁移文件脱钩，而那正是「Flyway 的历史不可修辞化处理」要防的。
 *
 * ## 两道闸门，少一道都不允许
 *
 * 1. 端口必须是 `3307`。3306 是**别的项目**的库，地址配错就是在改别人的表结构。
 * 2. 库名必须**恰好**是 `feedback_platform_local_deploy`。前缀匹配不够：
 *    一个叫 `feedback_platform_local_deploy_backup` 的库同样能通过 `contains` 检查。
 *
 * 两道闸门都在这里、也只在**这里**判一次：入口是唯一的，就不存在某个调用方漏判的可能。
 * 凭据从环境变量读（`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`），
 * 由 `~/.gradle/init-datasource.gradle.kts` 从 600 权限的文件注入——
 * 密码不进 argv，也不进 shell 历史。
 */
fun main() {
    val url = System.getenv("DB_URL") ?: error(
        "RCA_MIGRATE_DB_URL_MISSING：必须先由 ~/.gradle/init-datasource.gradle.kts 注入 DB_URL"
    )
    require(url.contains(":3307")) {
        "RCA_MIGRATE_DB_PORT_INVALID：只允许对本机 3307 执行迁移，当前地址端口不对"
    }
    val database = url.substringBefore('?').substringAfterLast('/')
    require(database == EXPECTED_DATABASE) {
        "RCA_MIGRATE_DB_NAME_FORBIDDEN：本入口只对 $EXPECTED_DATABASE 执行，当前是 $database"
    }

    val username = System.getenv("DB_USERNAME") ?: error("RCA_MIGRATE_DB_USERNAME_MISSING")
    val password = System.getenv("DB_PASSWORD") ?: error("RCA_MIGRATE_DB_PASSWORD_MISSING")

    val result = Flyway.configure()
        .dataSource(url, username, password)
        .locations("classpath:db/migration")
        .load()
        .migrate()

    // 输出「执行了几条」而不是只说成功：0 条与 1 条是两种不同的现场
    // （已经是最新，还是刚补上这一条），事后要能从这里读出来。
    println(
        "RCA_FLYWAY_MIGRATE_SUCCESS schema=$database " +
            "applied=${result.migrationsExecuted} " +
            "versions=${result.migrations.map { it.version }} " +
            "targetSchemaVersion=${result.targetSchemaVersion}"
    )
}

/** 只允许这一个库。改这里等于放宽上面第二道闸门，必须同时改文档。 */
private const val EXPECTED_DATABASE = "feedback_platform_local_deploy"
