package com.feedback.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/** 防止经典 Controller -> Service -> Repository -> Mapper 分层在迁移期间继续恶化。 */
class ClassicLayeringGateTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))
    private val sourceRoot = projectDir.resolve("src/main/kotlin")

    @Test
    fun `controller does not directly depend on repository or mapper`() {
        val controllerRoot = sourceRoot.resolve("com/feedback/controller")
        val imported = importViolations(controllerRoot) { dependency ->
            dependency.startsWith("com.feedback.repository.") || isPersistenceMapperImport(dependency)
        }
        val qualifiedRepositories = qualifiedDependencyViolations(
            controllerRoot,
            Regex("""\bcom\.feedback\.repository\.[A-Za-z0-9_.]+""")
        )
        val qualifiedMappers = qualifiedDependencyViolations(
            controllerRoot,
            Regex("""\bcom\.feedback\.persistence\.[A-Za-z0-9_.]*Mapper\b""")
        )
        verifyBaseline(
            rule = "controller-direct-data-access",
            actual = imported + qualifiedRepositories + qualifiedMappers,
            baseline = emptySet()
        )
    }

    @Test
    fun `controller does not depend on persistence objects`() {
        verifyBaseline(
            rule = "controller-persistence-object-dependency",
            actual = qualifiedDependencyViolations(
                sourceRoot.resolve("com/feedback/controller"),
                Regex("""\bcom\.feedback\.model\.po\.[A-Za-z0-9_.]+""")
            ),
            baseline = emptySet()
        )
    }

    @Test
    fun `service does not directly depend on MyBatis mapper`() {
        val mapperNames = productionMapperClasses(projectDir).mapTo(mutableSetOf()) { it.name }
        val actual = kotlinSources(sourceRoot.resolve("com/feedback/service")).flatMap { path ->
            val content = Files.readString(path)
            mapperNames.mapNotNull { mapperName ->
                val simpleName = mapperName.substringAfterLast('.')
                val explicitImport = "import $mapperName" in content
                val wildcardImport = "import ${mapperName.substringBeforeLast('.')}.*" in content &&
                    Regex("""\b${Regex.escape(simpleName)}\b""").containsMatchIn(content)
                val qualifiedUse = mapperName in content
                violation(path, mapperName).takeIf { explicitImport || wildcardImport || qualifiedUse }
            }
        }.toSet()

        verifyBaseline(
            rule = "service-direct-mybatis-mapper",
            actual = actual,
            baseline = emptySet()
        )
    }

    @Test
    fun `repository does not depend on service types`() {
        verifyBaseline(
            rule = "repository-reverse-service-dependency",
            actual = qualifiedDependencyViolations(
                sourceRoot.resolve("com/feedback/repository"),
                Regex("""\bcom\.feedback\.service\.[A-Za-z0-9_.]+""")
            ),
            baseline = emptySet()
        )
    }

    private fun verifyBaseline(rule: String, actual: Set<String>, baseline: Set<String>) {
        val expiresOn = LocalDate.parse("2026-10-31")
        assertTrue(!LocalDate.now().isAfter(expiresOn), "$rule debt baseline expired on $expiresOn")
        assertEquals(
            baseline,
            actual,
            "$rule changed. Remove retired entries from the dated baseline; reject every unlisted violation."
        )
    }

    private fun importViolations(root: Path, forbidden: (String) -> Boolean): Set<String> =
        kotlinSources(root).flatMap { path ->
            Files.readAllLines(path).mapNotNull { line ->
                val imported = line.trim().takeIf { it.startsWith("import ") }?.removePrefix("import ")
                imported?.takeIf(forbidden)?.let { violation(path, it) }
            }
        }.toSet()

    private fun qualifiedDependencyViolations(root: Path, dependencyPattern: Regex): Set<String> =
        kotlinSources(root).flatMap { path ->
            dependencyPattern.findAll(Files.readString(path))
                .map { match -> violation(path, match.value) }
                .toList()
        }.toSet()

    private fun violation(path: Path, dependency: String) =
        "${sourceRoot.relativize(path).toString().replace('\\', '/')} -> $dependency"
}

private fun kotlinSources(root: Path): List<Path> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
}

private fun isPersistenceMapperImport(imported: String) =
    imported.startsWith("com.feedback.persistence.") &&
        (imported.endsWith("Mapper") || imported.endsWith(".*"))
