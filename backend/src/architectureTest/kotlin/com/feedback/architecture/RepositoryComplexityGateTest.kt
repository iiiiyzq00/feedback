package com.feedback.architecture

import org.apache.ibatis.annotations.Mapper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.stereotype.Repository
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path

/** 第七阶段 Repository 构造器、Mapper 注入和公共 API 复杂度审查门禁。 */
class RepositoryComplexityGateTest {
    @Test
    fun `all repository components use the repository suffix`() {
        val violations = productionRepositoryClasses(projectDir)
            .map(Class<*>::getName)
            .filterNot {
                val simpleName = it.substringAfterLast('.')
                simpleName.endsWith("Repository") || simpleName.endsWith("RepositoryImpl")
            }
            .sorted()
        assertTrue(violations.isEmpty(), "Repository suffix violations:\n${violations.joinToString("\n")}")
    }

    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    @Test
    fun `large repositories stay within reviewed responsibility ceilings`() {
        val measured = productionRepositoryClasses(projectDir).associate { type ->
            val constructor = type.declaredConstructors.maxByOrNull { it.parameterCount }
            type.name to RepositoryComplexity(
                constructorParameters = constructor?.parameterCount ?: 0,
                mapperParameters = constructor?.parameterTypes?.count { it.isAnnotationPresent(Mapper::class.java) } ?: 0,
                publicMethods = type.declaredMethods.count {
                    Modifier.isPublic(it.modifiers) && !it.isSynthetic && !it.isBridge && '$' !in it.name
                }
            )
        }
        val large = measured.filterValues {
            it.constructorParameters > MAX_CONSTRUCTOR_PARAMETERS ||
                it.mapperParameters > MAX_MAPPER_PARAMETERS || it.publicMethods > MAX_PUBLIC_METHODS
        }
        val unknown = large.keys - reviewed.keys
        val exceeded = large.mapNotNull { (name, actual) ->
            reviewed[name]?.takeIf { actual.constructorParameters > it.maxConstructorParameters ||
                actual.mapperParameters > it.maxMapperParameters || actual.publicMethods > it.maxPublicMethods
            }?.let { "$name actual=$actual reviewed=$it" }
        }
        val retired = reviewed.keys - large.keys

        assertTrue(
            unknown.isEmpty() && exceeded.isEmpty() && retired.isEmpty(),
            "Repository complexity review changed. Unknown=${unknown.sorted()}; exceeded=${exceeded.sorted()}; " +
                "retired reviews that must be removed=${retired.sorted()}"
        )
    }

    private companion object {
        const val MAX_CONSTRUCTOR_PARAMETERS = 8
        const val MAX_MAPPER_PARAMETERS = 8
        const val MAX_PUBLIC_METHODS = 15

        val reviewed = mapOf(
            "com.feedback.repository.alert.AlertMetricRepository" to Review(14, 14, 80, "bounded cross-table alert metric read model"),
            "com.feedback.repository.alert.AlertQueryRepository" to Review(15, 15, 35, "alert center query and local command boundary"),
            "com.feedback.repository.report.SixReportFactsRepository" to Review(14, 14, 20, "six-report bounded fact collector"),
            "com.feedback.repository.alert.InAppNotificationRepository" to Review(13, 13, 25, "unified inbox read and receipt boundary"),
            "com.feedback.repository.imports.UnifiedQualityRepository" to Review(10, 10, 10, "cross-channel quality snapshot boundary"),
            "com.feedback.repository.report.ClusterReportRepository" to Review(9, 9, 10, "cluster report bounded fact collector"),
            "com.feedback.repository.alert.EntityClosureRepository" to Review(9, 9, 5, "atomic entity-closure fact and mutation boundary"),
            "com.feedback.repository.masking.MaskingSecurityRepository" to Review(9, 7, 15, "masking security transaction boundary"),
            "com.feedback.repository.ai.SkillPromptRepository" to Review(8, 8, 40, "skill and prompt lifecycle API"),
            "com.feedback.repository.cluster.ClusterReclassificationRepository" to Review(8, 8, 40, "cluster reclassification lifecycle API"),
            "com.feedback.repository.alert.NotificationEscalationRepository" to Review(8, 8, 40, "notification escalation lifecycle API"),
            "com.feedback.repository.feedback.detail.FeedbackDetailRepository" to Review(5, 0, 25, "stable facade over five detail fact repositories"),
            "com.feedback.repository.feedback.listprojection.FeedbackListProjectionRepository" to Review(8, 0, 25, "stable facade over eight projection repositories"),
            "com.feedback.repository.ImportBatchRepository" to Review(3, 3, 20, "legacy import batch lifecycle API"),
            "com.feedback.repository.alert.AlertProductionGovernanceRepository" to Review(5, 5, 25, "alert production governance lifecycle API"),
            "com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl" to Review(2,2,30,"application identity and alias locking, CAS and bounded lifecycle implementation"),
            "com.feedback.repository.auth.AuthUserRepository" to Review(2, 2, 20, "authentication user lifecycle API"),
            "com.feedback.repository.cluster.ClusterMutationRepository" to Review(5, 5, 60, "cluster and link mutation lifecycle API"),
            "com.feedback.repository.jiraowner.JiraOwnerInitializationRepository" to Review(9, 6, 20, "bounded Jira owner initialization use case"),
            "com.feedback.repository.impl.station.online.MyBatisStationOnlineChannelRepositoryImpl" to Review(1,1,25,"Station ingest channel lease, CAS and bounded lifecycle implementation")
        ).also { reviews -> check(reviews.values.all { it.reason.isNotBlank() }) }
    }
}

private data class RepositoryComplexity(
    val constructorParameters: Int,
    val mapperParameters: Int,
    val publicMethods: Int
)

private data class Review(
    val maxConstructorParameters: Int,
    val maxMapperParameters: Int,
    val maxPublicMethods: Int,
    val reason: String
)

private fun productionRepositoryClasses(projectDir: Path): Set<Class<*>> {
    val roots = listOf(projectDir.resolve("build/classes/kotlin/main"), projectDir.resolve("build/classes/java/main"))
        .filter(Files::isDirectory)
    return roots.flatMap { root ->
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .map { root.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .filter { '$' !in it && (it.startsWith("com.feedback.repository.") || it.startsWith("com.feedback.persistence.")) }
                .map { Class.forName(it, false, Thread.currentThread().contextClassLoader) }
                .filter { it.isAnnotationPresent(Repository::class.java) }
                .toList()
        }
    }.toSet()
}
