package com.feedback.architecture

import org.apache.ibatis.annotations.Delete
import org.apache.ibatis.annotations.DeleteProvider
import org.apache.ibatis.annotations.Insert
import org.apache.ibatis.annotations.InsertProvider
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Select
import org.apache.ibatis.annotations.SelectProvider
import org.apache.ibatis.annotations.Update
import org.apache.ibatis.annotations.UpdateProvider
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.GenericArrayType
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType
import java.nio.file.Files
import java.nio.file.Path

/**
 * 全量校验生产 Mapper 接口和 XML statement 的双向可达性。
 *
 * Mapper 从编译后的类目录发现；尚未迁移的 persistence 包暂时兼容多接口文件，已经迁移到
 * mapper 包的业务域逐批收紧为一文件一接口。XML 只负责 SQL statement，namespace 必须唯一并解析到 @Mapper 接口。
 */
class MyBatisBindingGateTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))
    private val sourceRoot = projectDir.resolve("src/main/kotlin")
    private val mapperRoot = projectDir.resolve("src/main/resources/mybatis/mapper")
    private val mapperClasses = productionMapperClasses(projectDir)

    @Test
    fun `all production mapper interfaces use the mapper root package and suffix`() {
        val violations = mapperClasses.mapNotNull { mapper ->
            mapper.name.takeUnless {
                it.startsWith("com.feedback.mapper.") && mapper.simpleName.endsWith("Mapper")
            }
        }
        assertTrue(violations.isEmpty(), "Mapper package or suffix violations:\n${violations.sorted().joinToString("\n")}")

        val legacySources = legacyKotlinSources(sourceRoot.resolve("com/feedback/persistence"))
        assertTrue(legacySources.isEmpty(), "Legacy persistence Kotlin sources remain:\n${legacySources.sorted().joinToString("\n")}")
    }

    @Test
    fun `every XML namespace is unique and resolves to a production mapper interface`() {
        val documents = mapperDocuments(mapperRoot)
        val duplicateNamespaces = documents.groupBy { it.namespace }.filterValues { it.size > 1 }
        val unresolved = documents.mapNotNull { document ->
            val type = runCatching {
                Class.forName(document.namespace, false, Thread.currentThread().contextClassLoader)
            }.getOrNull()
            document.namespace.takeIf {
                type == null || !type.isInterface || !type.isAnnotationPresent(Mapper::class.java)
            }
        }

        assertTrue(
            duplicateNamespaces.isEmpty() && unresolved.isEmpty(),
            "Duplicate MyBatis namespaces: ${duplicateNamespaces.keys.sorted()}; " +
                "namespaces not resolving to @Mapper interfaces: ${unresolved.sorted()}"
        )
    }

    @Test
    fun `every production mapper method has an XML statement or annotation SQL`() {
        val statements = mapperDocuments(mapperRoot)
            .flatMap { document -> document.statementIds.map { "${document.namespace}.$it" } }
            .toSet()
        val missing = mapperClasses.flatMap { mapper ->
            mapper.declaredMethods
                .filterNot { it.isSynthetic || it.isBridge || it.hasSqlAnnotation() }
                .mapNotNull { method -> "${mapper.name}.${method.name}".takeUnless(statements::contains) }
        }.sorted()

        assertTrue(missing.isEmpty(), "Production Mapper methods without SQL binding:\n${missing.joinToString("\n")}")
    }

    @Test
    fun `every XML statement resolves to a production mapper method`() {
        val methodsByMapper = mapperClasses.associate { mapper ->
            mapper.name to mapper.methods.filterNot { it.isSynthetic || it.isBridge }.mapTo(mutableSetOf()) { it.name }
        }
        val orphaned = mapperDocuments(mapperRoot).flatMap { document ->
            document.statementIds.mapNotNull { statementId ->
                "${document.namespace}.$statementId".takeUnless {
                    statementId in methodsByMapper.getOrDefault(document.namespace, emptySet())
                }
            }
        }.sorted()

        assertTrue(orphaned.isEmpty(), "MyBatis XML statements without Mapper methods:\n${orphaned.joinToString("\n")}")
    }

    @Test
    fun `production mapper methods do not return generic maps`() {
        val violations = mapperClasses.flatMap { mapper ->
            mapper.declaredMethods.mapNotNull { method ->
                "${mapper.name}.${method.name}".takeIf { method.genericReturnType.containsMapType() }
            }
        }.sorted()

        assertTrue(violations.isEmpty(), "Production Mapper methods returning Map values:\n${violations.joinToString("\n")}")
    }

    @Test
    fun `mapper XML uses explicit projections and safe parameter binding`() {
        val selectStar = mutableListOf<String>()
        val rawSubstitutions = mutableListOf<String>()
        Files.walk(mapperRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }.forEach { path ->
                val content = Files.readString(path).replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), " ")
                val relative = mapperRoot.relativize(path).toString().replace('\\', '/')
                if (Regex("""(?is)\bselect\b(?:(?!\bfrom\b).)*(?:^|,)\s*(?:[A-Za-z_][A-Za-z0-9_]*\.)?\*\s*(?:,|\bfrom\b)""")
                        .containsMatchIn(content)) {
                    selectStar += relative
                }
                if (Regex("""\$\{[^}]+}""").containsMatchIn(content)) {
                    rawSubstitutions += relative
                }
            }
        }

        assertTrue(
            selectStar.isEmpty() && rawSubstitutions.isEmpty(),
            "Mapper XML SELECT-star projections: ${selectStar.sorted()}; " +
                "raw substitutions requiring an explicit whitelist: ${rawSubstitutions.sorted()}"
        )
    }

    @Test
    fun `migrated mapper domains use one to one source and XML names`() {
        val migratedDomains = listOf("auth", "audit", "sdk", "attachment", "jira", "config", "imports", "station", "masking", "cluster", "feedback", "problem", "alert", "report", "scheduler", "ai", "applicationidentity", "core", "knowledge", "normalization", "query", "sync")
        val sourceViolations = migratedDomains.flatMap { domain ->
            val sourceRoot = projectDir.resolve("src/main/kotlin/com/feedback/mapper/$domain")
            Files.walk(sourceRoot).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .map { path ->
                        val mapperNames = Regex("""@Mapper\s+(?:public\s+)?interface\s+(\w+)""")
                            .findAll(Files.readString(path)).map { it.groupValues[1] }.toList()
                        path.takeIf {
                            mapperNames.size != 1 || path.fileName.toString() != "${mapperNames.singleOrNull()}.kt"
                        }?.toString()
                    }.filter { it != null }.map { checkNotNull(it) }.toList()
            }
        }
        val xmlViolations = migratedDomains.flatMap { domain ->
            mapperDocuments(mapperRoot.resolve(domain)).mapNotNull { document ->
                val expected = "${document.namespace.substringAfterLast('.')}.xml"
                document.path.takeIf {
                    !document.namespace.startsWith("com.feedback.mapper.$domain.") ||
                        it.fileName.toString() != expected
                }?.toString()
            }
        }

        assertTrue(
            sourceViolations.isEmpty() && xmlViolations.isEmpty(),
            "Migrated Mapper source/XML naming violations: ${(sourceViolations + xmlViolations).sorted()}",
        )
    }

    @Test
    fun `migrated persistence objects use one public PO per source file`() {
        val sourceRoot = projectDir.resolve("src/main/kotlin/com/feedback/model/po")
        val violations = Files.walk(sourceRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .map { path ->
                    val poNames = Regex("""\b(?:data\s+class|sealed\s+interface)\s+(\w+Po)\b""")
                        .findAll(Files.readString(path)).map { it.groupValues[1] }.toList()
                    path.takeIf {
                        poNames.size != 1 || path.fileName.toString() != "${poNames.singleOrNull()}.kt"
                    }?.toString()
                }.filter { it != null }.map { checkNotNull(it) }.toList()
        }

        assertTrue(violations.isEmpty(), "Migrated PO source naming violations: ${violations.sorted()}")
    }
}

private fun legacyKotlinSources(root: Path): List<Path> {
    if (!Files.exists(root)) return emptyList()
    return Files.walk(root).use { paths ->
        paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }
}

internal data class MapperDocument(val path: Path, val namespace: String, val statementIds: Set<String>)

internal fun mapperDocuments(root: Path): List<MapperDocument> = Files.walk(root).use { paths ->
    paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".xml") }
        .map { path ->
            val content = Files.readString(path)
            val namespace = Regex("""<mapper\s+namespace\s*=\s*[\"']([^\"']+)[\"']""")
                .find(content)?.groupValues?.get(1)
                ?: error("MyBatis XML has no mapper namespace: $path")
            val statements = Regex("""<(?:select|insert|update|delete)\b[^>]*>""", RegexOption.IGNORE_CASE)
                .findAll(content)
                .mapNotNull { tag ->
                    Regex("""\bid\s*=\s*[\"']([^\"']+)[\"']""").find(tag.value)?.groupValues?.get(1)
                }.toSet()
            MapperDocument(path, namespace, statements)
        }.toList()
}

internal fun productionMapperClasses(projectDir: Path): Set<Class<*>> {
    val classRoots = listOf(
        projectDir.resolve("build/classes/kotlin/main"),
        projectDir.resolve("build/classes/java/main")
    ).filter(Files::isDirectory)
    check(classRoots.isNotEmpty()) { "Compile production classes before running architecture gates" }
    return classRoots.flatMap { classesRoot ->
        Files.walk(classesRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".class") }
                .map { classesRoot.relativize(it).toString().removeSuffix(".class").replace('/', '.').replace('\\', '.') }
                .filter {
                    '$' !in it && (it.startsWith("com.feedback.persistence.") || it.startsWith("com.feedback.mapper."))
                }
                .map { name -> Class.forName(name, false, Thread.currentThread().contextClassLoader) }
                .filter { it.isInterface && it.isAnnotationPresent(Mapper::class.java) }
                .toList()
        }
    }.toSet()
}

private fun java.lang.reflect.Method.hasSqlAnnotation() =
    annotations.any { annotation ->
        annotation.annotationClass.java in setOf(
            Select::class.java,
            Insert::class.java,
            Update::class.java,
            Delete::class.java,
            SelectProvider::class.java,
            InsertProvider::class.java,
            UpdateProvider::class.java,
            DeleteProvider::class.java
        )
    }

private fun Type.containsMapType(): Boolean = when (this) {
    is Class<*> -> java.util.Map::class.java.isAssignableFrom(this)
    is ParameterizedType -> rawType.containsMapType() || actualTypeArguments.any(Type::containsMapType)
    is GenericArrayType -> genericComponentType.containsMapType()
    is WildcardType -> upperBounds.any(Type::containsMapType) || lowerBounds.any(Type::containsMapType)
    else -> false
}
