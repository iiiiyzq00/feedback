package com.feedback.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 登录、权限、附件、应用身份、Station 与 SDK 六条已迁移链路的目录纯度总门禁。 */
class MigratedServicePackagePurityArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))
    private val domains = listOf("auth", "attachment", "applicationidentity", "station", "sdk")

    /** Service 目录只允许一文件一契约的 `*Service.kt`，并禁止实现注解和职责后缀回流。 */
    @Test
    fun `migrated service packages contain only service contracts`() {
        domains.forEach { domain ->
            directKotlinFiles("src/main/kotlin/com/feedback/service/$domain").forEach { file ->
                val name = file.fileName.toString()
                val contract = name.removeSuffix(".kt")
                val source = Files.readString(file)
                assertTrue(name.endsWith("Service.kt"), "$file 不是 Service 契约文件")
                assertTrue(source.contains("interface $contract"), "$file 未声明同名 Service 接口")
                assertFalse(Regex("(?m)^(?:data\\s+|enum\\s+|sealed\\s+)?class\\s+").containsMatchIn(source), "$file 不得混放模型或实现")
                assertFalse(source.contains("@Service"), "$file 不得声明 Spring Service 实现")
                assertFalse(source.contains("@Transactional"), "$file 不得持有事务实现")
            }
        }
    }

    /** Impl 目录只允许与契约一一对应的 `*ServiceImpl.kt`，禁止 Worker 等组件伪装成实现。 */
    @Test
    fun `migrated impl packages contain only service implementations`() {
        domains.forEach { domain ->
            directKotlinFiles("src/main/kotlin/com/feedback/service/impl/$domain").forEach { file ->
                val name = file.fileName.toString()
                val implementation = name.removeSuffix(".kt")
                val contract = implementation.removeSuffix("Impl")
                val source = Files.readString(file)
                assertTrue(name.endsWith("ServiceImpl.kt"), "$file 不是 ServiceImpl 文件")
                assertTrue(source.contains("class $implementation"), "$file 未声明同名实现")
                val topLevelTypes = Regex("(?m)^(?:data\\s+|enum\\s+|sealed\\s+)?(?:class|interface|object)\\s+")
                    .findAll(source)
                    .count()
                assertTrue(topLevelTypes == 1, "$file 只能声明一个顶级 ServiceImpl 类型")
                assertTrue(source.contains(Regex(":\\s*$contract\\b")), "$file 未实现 $contract")
                assertTrue(
                    Files.exists(projectDir.resolve("src/main/kotlin/com/feedback/service/$domain/$contract.kt")),
                    "$implementation 缺少唯一契约 $contract"
                )
                assertFalse(source.contains("import com.feedback.mapper."), "$file 不得直连 Mapper")
            }
        }
    }

    /** 每个已迁移 Service 契约必须有唯一同名实现，禁止留下 Operations 或 UseCase 平行入口。 */
    @Test
    fun `migrated contracts have one matching implementation`() {
        domains.forEach { domain ->
            directKotlinFiles("src/main/kotlin/com/feedback/service/$domain").forEach { contractFile ->
                val contract = contractFile.fileName.toString().removeSuffix(".kt")
                val expected = projectDir.resolve(
                    "src/main/kotlin/com/feedback/service/impl/$domain/${contract}Impl.kt"
                )
                assertTrue(Files.exists(expected), "$contract 缺少对应 ServiceImpl")
            }
        }
    }

    /** 旧 entity 聚合包必须消失，源码、测试、XML 与文档不得继续引用旧全限定名。 */
    @Test
    fun `legacy entity package is fully retired`() {
        assertFalse(Files.exists(projectDir.resolve("src/main/kotlin/com/feedback/entity")))
        listOf("applicationidentity", "attachment", "client", "sdk", "station").forEach { legacy ->
            assertFalse(
                Files.exists(projectDir.resolve("src/main/kotlin/com/feedback/$legacy")),
                "旧领域顶级包尚未清理：$legacy"
            )
        }
        val stale = filesUnder("src").filter { file ->
            if (file.startsWith(projectDir.resolve("src/architectureTest"))) {
                false
            } else {
                val source = Files.readString(file)
                source.contains("com.feedback.entity") || source.contains("kotlin/com/feedback/entity")
            }
        }
        assertTrue(stale.isEmpty(), "仍引用旧 entity 包：\n${stale.joinToString("\n")}")
    }

    /** 新职责目录与归位后的 PO 必须保持中文 KDoc 和展开控制流，防止迁移只改路径不保可读性。 */
    @Test
    fun `migrated components and persistent models remain readable`() {
        val roots = listOf(
            "src/main/kotlin/com/feedback/service/auth",
            "src/main/kotlin/com/feedback/service/attachment",
            "src/main/kotlin/com/feedback/service/applicationidentity",
            "src/main/kotlin/com/feedback/service/station",
            "src/main/kotlin/com/feedback/service/sdk",
            "src/main/kotlin/com/feedback/service/impl/auth",
            "src/main/kotlin/com/feedback/service/impl/attachment",
            "src/main/kotlin/com/feedback/service/impl/applicationidentity",
            "src/main/kotlin/com/feedback/service/impl/station",
            "src/main/kotlin/com/feedback/service/impl/sdk",
            "src/main/kotlin/com/feedback/manager/applicationidentity",
            "src/main/kotlin/com/feedback/manager/attachment",
            "src/main/kotlin/com/feedback/manager/station",
            "src/main/kotlin/com/feedback/manager/sdk",
            "src/main/kotlin/com/feedback/job/applicationidentity",
            "src/main/kotlin/com/feedback/job/attachment",
            "src/main/kotlin/com/feedback/job/station",
            "src/main/kotlin/com/feedback/job/sdk",
            "src/main/kotlin/com/feedback/strategy/applicationidentity",
            "src/main/kotlin/com/feedback/strategy/sdk",
            "src/main/kotlin/com/feedback/convert/station",
            "src/main/kotlin/com/feedback/rpc/station",
            "src/main/kotlin/com/feedback/security/attachment",
            "src/main/kotlin/com/feedback/security/station",
            "src/main/kotlin/com/feedback/security/sdk",
            "src/main/kotlin/com/feedback/dto/attachment",
            "src/main/kotlin/com/feedback/dto/auth",
            "src/main/kotlin/com/feedback/dto/station",
            "src/main/kotlin/com/feedback/dto/sdk",
            "src/main/kotlin/com/feedback/model/applicationidentity",
            "src/main/kotlin/com/feedback/model/attachment",
            "src/main/kotlin/com/feedback/model/auth",
            "src/main/kotlin/com/feedback/model/ai",
            "src/main/kotlin/com/feedback/model/audit",
            "src/main/kotlin/com/feedback/model/channel",
            "src/main/kotlin/com/feedback/model/cluster",
            "src/main/kotlin/com/feedback/model/config",
            "src/main/kotlin/com/feedback/model/feedback",
            "src/main/kotlin/com/feedback/model/imports",
            "src/main/kotlin/com/feedback/model/jira",
            "src/main/kotlin/com/feedback/model/masking",
            "src/main/kotlin/com/feedback/model/sdk",
            "src/main/kotlin/com/feedback/model/station",
            "src/main/kotlin/com/feedback/repository/applicationidentity",
            "src/main/kotlin/com/feedback/repository/attachment",
            "src/main/kotlin/com/feedback/repository/auth",
            "src/main/kotlin/com/feedback/repository/station",
            "src/main/kotlin/com/feedback/repository/sdk",
            "src/main/kotlin/com/feedback/repository/impl/applicationidentity",
            "src/main/kotlin/com/feedback/repository/impl/attachment",
            "src/main/kotlin/com/feedback/repository/impl/auth",
            "src/main/kotlin/com/feedback/repository/impl/station",
            "src/main/kotlin/com/feedback/repository/impl/sdk",
            "src/main/kotlin/com/feedback/repository/remote/auth",
            "src/main/kotlin/com/feedback/repository/remote/attachment",
            "src/main/kotlin/com/feedback/repository/remote/station",
            "src/main/kotlin/com/feedback/mapper/applicationidentity",
            "src/main/kotlin/com/feedback/mapper/attachment",
            "src/main/kotlin/com/feedback/mapper/auth",
            "src/main/kotlin/com/feedback/mapper/station",
            "src/main/kotlin/com/feedback/mapper/sdk"
        )
        val files = roots.flatMap(::filesUnder).plus(
            listOf(
                "src/main/kotlin/com/feedback/controller/query/FeedbackController.kt",
                "src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminController.kt",
                "src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminContracts.kt",
                "src/main/kotlin/com/feedback/controller/station/StationFeedbackController.kt",
                "src/main/kotlin/com/feedback/controller/sdk/SdkFeedbackController.kt",
                "src/main/kotlin/com/feedback/controller/auth/AuthController.kt",
            ).map(projectDir::resolve)
        ).distinct()
        val declaration = Regex(
            "^\\s*(?:(?:public|private|protected|internal|open|final|abstract|sealed|data|enum|annotation|value|inline|suspend|tailrec|operator|infix|override|actual|expect)\\s+)*(?:(?:class|interface|object)\\s+[A-Za-z_]|fun\\s+[A-Za-z_])"
        )
        val chinese = Regex("[\\u4e00-\\u9fff]")
        val compressed = Regex(
            "\\bif\\s*\\([^\\n]*\\)\\s+(?:throw\\b|return\\b|break\\b|continue\\b)|" +
                "\\belse\\s+(?:throw\\b|return\\b)|^\\s*@[A-Za-z][^\\n]*\\s+(?:class|fun)\\s+"
        )
        val violations = files.flatMap { file ->
            val lines = Files.readAllLines(file)
            lines.mapIndexedNotNull { index, line ->
                val needsKDoc = declaration.containsMatchIn(line)
                val kdoc = adjacentKDoc(lines, index)
                if ((needsKDoc && !kdoc.contains(chinese)) || compressed.containsMatchIn(line)) {
                    "${projectDir.relativize(file)}:${index + 1}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "迁移目录注释或控制流违规：\n${violations.joinToString("\n")}")
    }

    /** 只读取目标目录的直接 Kotlin 文件，子目录不属于 Service/Impl 纯度判定。 */
    private fun directKotlinFiles(root: String): List<Path> = Files.list(projectDir.resolve(root)).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    /** 递归读取存在目录下的人工维护文本文件。 */
    private fun filesUnder(root: String): List<Path> {
        val path = projectDir.resolve(root)
        if (!Files.exists(path)) {
            return emptyList()
        }
        return Files.walk(path).use { stream ->
            stream.filter {
                Files.isRegularFile(it) && it.fileName.toString().substringAfterLast('.', "") in
                    setOf("kt", "xml", "md")
            }.toList()
        }
    }

    /** 允许 KDoc 与声明之间只有空行和注解。 */
    private fun adjacentKDoc(lines: List<String>, declarationIndex: Int): String {
        var cursor = declarationIndex - 1
        while (cursor >= 0 && (lines[cursor].isBlank() || lines[cursor].trimStart().startsWith("@"))) {
            cursor--
        }
        if (cursor < 0 || !lines[cursor].trimEnd().endsWith("*/")) {
            return ""
        }
        val result = mutableListOf<String>()
        while (cursor >= 0) {
            result += lines[cursor]
            if (lines[cursor].trimStart().startsWith("/**")) {
                return result.asReversed().joinToString("\n")
            }
            cursor--
        }
        return ""
    }
}
