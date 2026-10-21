plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.spring")
    jacoco
}

val integrationTestSourceSet = sourceSets.create("integrationTest") {
    kotlin.srcDir("src/integrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val realIndexTestSourceSet = sourceSets.create("realIndexTest") {
    kotlin.srcDir("src/realIndexTest/kotlin")
    compileClasspath += sourceSets.main.get().output + integrationTestSourceSet.output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val clusterShadowTestSourceSet = sourceSets.create("clusterShadowTest") {
    kotlin.srcDir("src/clusterShadowTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val architectureTestSourceSet = sourceSets.create("architectureTest") {
    kotlin.srcDir("src/architectureTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
val shadowPreviewTestSourceSet = sourceSets.create("shadowPreviewTest") {
    kotlin.srcDir("src/shadowPreviewTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
// RCA 生产入口的端到端验证。单独一个源集，因为它**会写库**（问题对象、成员、工作项、任务），
// 与上面几个只读/离线源集的失败模式不同，不能混在一起。
val rcaEntryTestSourceSet = sourceSets.create("rcaEntryTest") {
    kotlin.srcDir("src/rcaEntryTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}
// 消息通道的端到端验证。单独一个源集，因为它的前置条件与上面每一个都不同：
// 它需要一个**活着的 broker**（默认 127.0.0.1:9876/10911），而上面几个只需要 MySQL
// 或者什么都不需要。把「broker 没起」的失败混进只读门禁里，会让那些门禁
// 在没起 broker 的机器上变红，于是很快就没人看它们了。
val mqIntegrationTestSourceSet = sourceSets.create("mqIntegrationTest") {
    kotlin.srcDir("src/mqIntegrationTest/kotlin")
    compileClasspath += sourceSets.main.get().output + configurations.testRuntimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

configurations[integrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[integrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[realIndexTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[realIndexTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[clusterShadowTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[clusterShadowTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[architectureTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[architectureTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[shadowPreviewTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[shadowPreviewTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[rcaEntryTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[rcaEntryTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
configurations[mqIntegrationTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[mqIntegrationTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

group = "com.feedback"
version = "1.0.0-SNAPSHOT"

springBoot { mainClass.set("com.feedback.FeedbackApplicationKt") }

// 本地与打包部署都消费一个稳定、可预期的可执行产物。
// 尤其是本地安全启动器，不能直接运行在会被改动的
// build/classes 输出上——另一次 Gradle 调用随时可能替换掉那个目录。
tasks.bootJar {
    archiveFileName.set("feedback-platform-backend.jar")
}

tasks.register<JavaExec>("attachmentObjectMigration") {
    group = "application"
    description = "Runs the bounded attachment object migration until both migration and local cleanup candidates are empty."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.AttachmentObjectMigrationMainKt")
}

tasks.register<JavaExec>("clusterShadowMigrate") {
    group = "application"
    description = "Migrates the configured schema to the isolated cluster shadow generation V241 schema."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.ClusterShadowMigrationMainKt")
}

tasks.register<JavaExec>("clusterShadowGeneration") {
    group = "application"
    description = "Runs one isolated cluster shadow generation BUILD or VERIFY action and exits."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.ClusterShadowGenerationMainKt")
}

tasks.register<JavaExec>("applicationSymptomDryRun") {
    group = "application"
    description = "Runs one aggregate-only application review and stateless symptom snapshot; it has no write beans."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.ApplicationSymptomDryRunMainKt")
}

tasks.register<JavaExec>("applicationReviewedBindingPreflight") {
    group = "application"
    description = "Runs the aggregate-only reviewed binding preflight and writes an owner-only prepared command package."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.ApplicationReviewedBindingPreflightMainKt")
}

tasks.register<JavaExec>("applicationReviewedBindingApply") {
    group = "application"
    description = "Applies one fingerprinted reviewed-binding command package in a single transaction."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.ApplicationReviewedBindingApplyMainKt")
}

tasks.register<JavaExec>("redblackReviewRecovery") {
    group = "application"
    description = "Runs the exact-ID REDBLACK legacy masking recovery in DRY_RUN or explicitly confirmed APPLY mode."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.RedblackReviewRecoveryMainKt")
}

tasks.register<JavaExec>("redblackSourceDeletionCorrection") {
    group = "application"
    description = "Restores the single REDBLACK content row incorrectly soft-deleted from a source-side deletion flag."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.RedblackSourceDeletionCorrectionMainKt")
}

tasks.register<JavaExec>("rcaReplay414") {
    group = "application"
    description = "Replays the fixed 414 video-auth incident through the five-node RCA graph and asserts path and conclusion."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.agent.rca.replay.RcaReplayMainKt")
}

tasks.register<JavaExec>("rcaRecoveryInjection") {
    group = "application"
    description = "Injects crashes at four points around the RCA node boundary and asserts lease generation fencing and three-state recovery."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.agent.rca.replay.RcaRecoveryInjectionMainKt")
}

tasks.register<JavaExec>("rcaEpisodeSessionization") {
    group = "application"
    description = "Runs the episode sessionization intake (identity resolution, dedup impact, work item and RCA task dispatch) over a fixed fixture and asserts both episode-scope and cluster-scope impact."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.agent.rca.replay.EpisodeSessionizationMainKt")
}

dependencyManagement {
    dependencies {
        dependency("org.flywaydb:flyway-core:11.20.3")
        dependency("org.flywaydb:flyway-mysql:11.20.3")
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(project(":rpc-api"))
    implementation("org.apache.dubbo:dubbo-spring-boot-starter:3.3.5")
    implementation("org.apache.curator:curator-framework:5.2.0")
    implementation("org.apache.curator:curator-recipes:5.2.0")
    implementation("org.apache.curator:curator-x-discovery:5.2.0")
    // 保持执行器协议与公司学习机服务一致。
    implementation("com.xuxueli:xxl-job-core:1.9.1")
    // XXL-Job 1.9.1 内嵌 Jetty 9 的执行器 API。否则 Spring Boot 的依赖
    // 管理会把这些模块升级到 Jetty 12，而它的包路径
    // 与旧执行器二进制不兼容。
    listOf("jetty-server", "jetty-http", "jetty-io", "jetty-util").forEach { module ->
        implementation("org.eclipse.jetty:$module") {
            version { strictly("9.4.57.v20241219") }
        }
    }

    // RocketMQ —— 领域事件与既有两套 Outbox 的投递通道。
    // 版本与 ~/.local/middleware/rocketmq 里那个 5.5.1 broker 对齐：客户端与 broker
    // 协议版本不一致时，症状通常是「连得上但收不到」，这类问题在本地极难判因，
    // 因此不做「用 4.x 客户端接 5.x broker」这种省依赖的尝试。
    // 开关 app.mq.enabled 默认 false：关闭时既不建连接也不起消费者
    // （装配见 com.feedback.messaging，行为见 docs/delivery/29-rca-agent-runtime.md 第 9 节）。
    implementation("org.apache.rocketmq:rocketmq-client:5.5.1")

    // Spring Boot 起步依赖
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.30")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.mybatis.spring.boot:mybatis-spring-boot-starter:3.0.4")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Flyway — Spring Boot 自动配置模式
    runtimeOnly("org.flywaydb:flyway-mysql:11.20.3")
    runtimeOnly("org.flywaydb:flyway-core")
    compileOnly("org.flywaydb:flyway-core")

    // MySQL
    runtimeOnly("com.mysql:mysql-connector-j")

    // HikariCP (Spring Boot 默认内置，显式声明版本)
    implementation("com.zaxxer:HikariCP")

    // Elasticsearch — 使用 Spring Data Elasticsearch
    implementation("org.springframework.boot:spring-boot-starter-data-elasticsearch")

    // Milvus
    implementation("io.milvus:milvus-sdk-java:2.6.23")

    // Apache POI — Excel 解析
    implementation("org.apache.poi:poi:5.5.1")
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.commons:commons-compress:1.28.0")

    // OkHttp — HTTP 客户端（用于 LLM 调用等）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 兼容 S3 的附件对象存储。适配器在显式配置之前保持关闭。
    implementation(platform("software.amazon.awssdk:bom:2.31.77"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    // Prometheus
    implementation("io.micrometer:micrometer-registry-prometheus")

    // HanLP — 中文 NER 脱敏（Maven Central 最新版 portable-1.8.6）
    implementation("com.hankcs:hanlp:portable-1.8.6")

    // BGE-M3 向量化模型
    runtimeOnly("com.microsoft.onnxruntime:onnxruntime:1.28.0")
    runtimeOnly("ai.djl.huggingface:tokenizers:0.36.0")

    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    // Micrometer
    implementation("io.micrometer:micrometer-core")

    // 测试
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.mockk:mockk:1.14.11")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
    testImplementation("org.testcontainers:testcontainers:1.21.4")
    testImplementation("org.testcontainers:mysql:1.21.4")
    testImplementation("org.testcontainers:junit-jupiter:1.21.4")
    testImplementation("io.rest-assured:rest-assured:5.5.7")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperties(System.getProperties().entries
        .filter { (key, _) -> key.toString().startsWith("day4.") }
        .associate { (key, value) -> key.toString() to value })
}

tasks.test {
    // 环境门禁由 JUnit 在运行期判定；启用真实 P0 MySQL 契约时禁止复用此前的 skipped 结果。
    outputs.upToDateWhen { System.getenv("P0_MYBATIS_MYSQL_ENABLED") != "true" }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    violationRules {
        rule {
            // 2026-08-11 实测基线：行覆盖 43.98%、分支 20.08%、类 51.77%。
            // 留一点噪声余量；后续只允许调高这些门槛，不允许调低。
            limit { counter = "LINE"; minimum = "0.42".toBigDecimal() }
            limit { counter = "BRANCH"; minimum = "0.18".toBigDecimal() }
            limit { counter = "CLASS"; minimum = "0.48".toBigDecimal() }
        }
    }
}

val integrationTest by tasks.registering(Test::class) {
    description = "Runs isolated Day 9 integration tests. Docker is required by tests extending IntegrationEnvironment."
    group = "verification"
    testClassesDirs = integrationTestSourceSet.output.classesDirs
    classpath = integrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
    forkEvery = 1
    listOf("d9.external-infra", "d9.mysql.url", "d9.mysql.username", "d9.mysql.password", "d9.redis.host", "d9.redis.port")
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

val realIndexIntegrationTest by tasks.registering(Test::class) {
    description = "Runs isolated real Elasticsearch and Milvus convergence tests."
    group = "verification"
    dependsOn(tasks.named("integrationTestClasses"))
    testClassesDirs = realIndexTestSourceSet.output.classesDirs
    classpath = realIndexTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    listOf("d9.external-infra", "d9.mysql.url", "d9.mysql.username", "d9.mysql.password", "d9.redis.host", "d9.redis.port",
        "d9.elasticsearch.url", "d9.elasticsearch.index", "d9.milvus.host", "d9.milvus.port", "d9.milvus.collection")
        .forEach { key -> System.getProperty(key)?.let { systemProperty(key, it) } }
}

tasks.register<Test>("clusterShadowTest") {
    description = "运行不写生产数据的确定性归簇影子回放、攻击与性能测试。"
    group = "verification"
    testClassesDirs = clusterShadowTestSourceSet.output.classesDirs
    classpath = clusterShadowTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
}

tasks.register<Test>("shadowPreviewTest") {
    description = "Runs isolated read-only cluster shadow preview contract tests."
    group = "verification"
    testClassesDirs = shadowPreviewTestSourceSet.output.classesDirs
    classpath = shadowPreviewTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
}

tasks.register<Test>("rcaEntryTest") {
    description = "RCA 生产入口端到端验证（会写库，只跑本机 feedback-platform 的 3307）。"
    group = "verification"
    testClassesDirs = rcaEntryTestSourceSet.output.classesDirs
    classpath = rcaEntryTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
    // 门控变量从系统属性转发进测试 JVM 的 env：Gradle 守护进程会被复用，
    // 它的 env 不一定是本次客户端的（与 ~/.gradle/init-datasource.gradle.kts 同一理由）。
    providers.systemProperty("RCA_ENTRY_TEST_ENABLED").orNull
        ?.let { environment("RCA_ENTRY_TEST_ENABLED", it) }
    // 3306 是**别的项目**的库，绝不能连。application.yml 的默认值恰好指向 3306，
    // 所以这里在测试开始前就断言端口，宁可任务失败也不允许退到默认值。
    doFirst {
        val url = environment["DB_URL"]?.toString()
            ?: throw GradleException("RCA_ENTRY_TEST_DB_URL_MISSING：必须经 ~/.gradle/init-datasource.gradle.kts 注入 DB_URL")
        if (!url.contains(":3307")) {
            throw GradleException("RCA_ENTRY_TEST_DB_PORT_INVALID：DB_URL 未指向本机 feedback-platform 的 3307")
        }
        systemProperty("rca.entry.db.url", url)
    }
}

// 消息通道的端到端验证：真 broker + 真库。
//
// 两道门缺一不可，与 rcaEntryTest 同一个道理，但多了一道：
//   1. `-DMQ_INTEGRATION_TEST_ENABLED=true` —— 这个任务会连本机 broker 并**写消息**，
//      不该在任何人随手跑 `check` 的时候发生；
//   2. DB_URL 必须指向 3307 —— 3306 属于别的项目，application.yml 的默认值恰好指向它，
//      所以这里是「宁可任务失败也不允许退到默认值」；
//   3. broker 地址取自 MQ_NAMESRV_ADDR（缺省 127.0.0.1:9876）。**不在这里 ping broker**：
//      连不上 broker 时客户端会在明确的错误里说出地址，而一个手写的探活
//      （比如开个 socket）会把「端口开着但不是 broker」这种情形报成通过。
tasks.register<Test>("mqIntegrationTest") {
    description = "消息通道端到端验证（真 broker + 本机 3307）：RCA 领域事件、通知、索引三条轨。"
    group = "verification"
    testClassesDirs = mqIntegrationTestSourceSet.output.classesDirs
    classpath = mqIntegrationTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
    maxParallelForks = 1
    providers.systemProperty("MQ_INTEGRATION_TEST_ENABLED").orNull
        ?.let { environment("MQ_INTEGRATION_TEST_ENABLED", it) }
    doFirst {
        val url = environment["DB_URL"]?.toString()
            ?: throw GradleException("MQ_INTEGRATION_TEST_DB_URL_MISSING：必须经 ~/.gradle/init-datasource.gradle.kts 注入 DB_URL")
        if (!url.contains(":3307")) {
            throw GradleException("MQ_INTEGRATION_TEST_DB_PORT_INVALID：DB_URL 未指向本机 feedback-platform 的 3307")
        }
        systemProperty("mq.integration.db.url", url)
    }
}

// 把 db/migration 应用到**本机 3307 的部署库**，让 rcaEntryTest 能对着真库验证新列。
//
// 为什么是一个单独的人工入口，而不是 rcaEntryTest 自动带跑：
// 迁移是全局副作用（改整库结构）。让一次「验证」运行顺手改掉库结构，会把
// 「跑测试」与「改环境」混在一起——出问题时无法判断是代码不对还是环境被动过。
// 与 flywayRepair 同一范式：JavaExec + -Pconfirm 确认串 + 入口内两道闸门。
tasks.register<JavaExec>("flywayMigrateLocalDeploy") {
    group = "database"
    description = "Migrates db/migration into the local 3307 feedback_platform_local_deploy " +
        "with -PconfirmFlywayMigrate=feedback_platform_local_deploy"
    dependsOn(tasks.named("rcaEntryTestClasses"))
    classpath = rcaEntryTestSourceSet.runtimeClasspath
    mainClass.set("com.feedback.agent.rca.ops.RcaLocalDeployMigrateMainKt")
    val migrate = this
    doFirst {
        require(project.findProperty("confirmFlywayMigrate") == "feedback_platform_local_deploy") {
            "Refusing Flyway migrate without -PconfirmFlywayMigrate=feedback_platform_local_deploy"
        }
        // 凭据那一份来自 :backend:rcaEntryTest —— 它是 ~/.gradle/init-datasource.gradle.kts
        // 从 600 权限文件读出后注入的。抄过来而不是自己再读一次文件：
        // 凭据文件的解析只该有一处实现（memory: secrets-must-not-enter-argv）。
        migrate.environment(tasks.named<Test>("rcaEntryTest").get().environment)
    }
}

val architectureTest by tasks.registering(Test::class) {
    description = "Runs MyBatis binding and classic layering regression gates independently of legacy test debt."
    group = "verification"
    dependsOn(tasks.named("architectureTestClasses"))
    testClassesDirs = architectureTestSourceSet.output.classesDirs
    classpath = architectureTestSourceSet.runtimeClasspath
    useJUnitPlatform()
    reports.html.required.set(true)
    reports.junitXml.required.set(true)
    systemProperty("feedback.backend.project-dir", projectDir.absolutePath)
}

tasks.check {
    dependsOn(integrationTest)
    dependsOn(architectureTest)
}

tasks.register<JavaExec>("flywayRepair") {
    group = "database"
    description = "Repairs Flyway history only with -PconfirmFlywayRepair=feedback_platform"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.feedback.tools.FlywayRepairMainKt")
    doFirst {
        require(project.findProperty("confirmFlywayRepair") == "feedback_platform") {
            "Refusing Flyway repair without -PconfirmFlywayRepair=feedback_platform"
        }
    }
}
