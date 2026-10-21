package com.feedback.architecture

import com.feedback.manager.sdk.SdkTelemetryCommitWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/** SDK 调用链的入口、分层、幂等、短事务、批量和 SQL 反回归门禁。 */
class SdkChainArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    /** SDK HTTP 入口只能依赖窄 Service 契约。 */
    @Test
    fun `sdk controller depends on service contracts`() {
        val controller = source("src/main/kotlin/com/feedback/controller/sdk/SdkFeedbackController.kt")
        listOf("import com.feedback.service.impl.", "import com.feedback.repository.", "import com.feedback.mapper.", "import com.feedback.model.po.")
            .forEach { forbidden -> assertFalse(controller.contains(forbidden), forbidden) }
        listOf("SdkIngestService", "SdkAttachmentService", "SdkTelemetryService", "SdkConfigService")
            .forEach { contract -> assertTrue(controller.contains(contract), contract) }
    }

    /** SDK 业务实现与契约分离，ServiceImpl 不直接引用 Mapper。 */
    @Test
    fun `sdk service and repository layers remain one way`() {
        listOf("SdkIngestServiceImpl.kt", "SdkAttachmentServiceImpl.kt", "SdkTelemetryServiceImpl.kt", "SdkConfigServiceImpl.kt")
            .forEach { name ->
                val content = source("src/main/kotlin/com/feedback/service/impl/sdk/$name")
                assertFalse(content.contains("import com.feedback.mapper."), name)
            }
        filesUnder("src/main/kotlin/com/feedback/repository/impl/sdk").forEach { file ->
            assertFalse(Files.readString(file).contains("import com.feedback.service."), file.toString())
        }
        val contracts = source("src/main/kotlin/com/feedback/repository/sdk/SdkMaterializationRepositories.kt")
        assertFalse(contracts.contains("@Repository"))
        assertFalse(contracts.contains("import com.feedback.mapper."))
    }

    /** SDK 验签、Redis nonce、限流和命令组装必须位于单表短写事务之外。 */
    @Test
    fun `sdk ingest keeps authentication and idempotency outside short persistence`() {
        val ingest = source("src/main/kotlin/com/feedback/service/impl/sdk/SdkIngestServiceImpl.kt")
        assertFalse(ingest.contains("@Transactional"))
        assertTrue(ingest.indexOf("requestAuthenticator.authenticate") < ingest.indexOf("persistence.saveReceived"))
        assertTrue(ingest.contains("requireSameCommand(existing, bodyHash)"))
        assertTrue(ingest.contains("causedByConstraint(IDEMPOTENCY_CONSTRAINT)"))
        val persistence = source("src/main/kotlin/com/feedback/manager/sdk/TransactionalSdkIngestPersistence.kt")
        assertTrue(persistence.contains("@Transactional"))
        assertFalse(persistence.contains("requestAuthenticator"))
        assertFalse(persistence.contains("StringRedisTemplate"))
    }

    /** 遥测 JSON 组装位于事务外，提交器只执行一次最多 100 条的批量写入。 */
    @Test
    fun `sdk telemetry uses prepared bounded short transaction`() {
        val service = source("src/main/kotlin/com/feedback/service/impl/sdk/SdkTelemetryServiceImpl.kt")
        assertFalse(service.contains("@Transactional"))
        assertTrue(service.indexOf("writeValueAsString") < service.indexOf("commitWorker.commit"))
        val method = SdkTelemetryCommitWorker::class.java.getMethod("commit", List::class.java)
        assertNotNull(method.getAnnotation(Transactional::class.java))
        val repository = source("src/main/kotlin/com/feedback/repository/impl/sdk/MyBatisSdkMaterializationRepositoryImpl.kt")
        assertTrue(repository.contains("rows.size <= 100"))
        assertTrue(repository.contains("insertTransportEvents(rows)"))
    }

    /** SDK 九个物化 Mapper 组成固定职责清单，SQL 必须参数化、无 SELECT 星号。 */
    @Test
    fun `sdk mapper inventory and sql stay readable and parameterized`() {
        val files = Files.list(projectDir.resolve("src/main/resources/mybatis/mapper/sdk")).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".xml") }.toList()
        }
        assertEquals(9, files.size)
        files.forEach { file ->
            val xml = Files.readString(file)
            assertFalse(xml.contains("\${"), file.toString())
            assertFalse(Regex("(?i)SELECT\\s+\\*").containsMatchIn(xml), file.toString())
            assertFalse(Regex("(?i)\\bJOIN\\b|\\bEXISTS\\s*\\(").containsMatchIn(xml), file.toString())
            assertFalse(Regex("<foreach[^>]*>[^<\\n]+</foreach>").containsMatchIn(xml), file.toString())
        }
    }

    /** SDK 完整链路的命名类型与函数必须有紧邻中文 KDoc，控制流与注解不得压缩。 */
    @Test
    fun `sdk comments and control flow remain readable`() {
        val files = listOf(
            "src/main/kotlin/com/feedback/service/sdk",
            "src/main/kotlin/com/feedback/service/impl/sdk",
            "src/main/kotlin/com/feedback/repository/sdk",
            "src/main/kotlin/com/feedback/repository/impl/sdk",
            "src/main/kotlin/com/feedback/mapper/sdk",
            "src/main/kotlin/com/feedback/model/po/sdk",
            "src/main/kotlin/com/feedback/dto/sdk",
            "src/main/kotlin/com/feedback/controller/sdk"
        ).flatMap(::filesUnder).plus(
            listOf(
                "src/main/kotlin/com/feedback/config/SdkAuthProperties.kt",
                "src/main/kotlin/com/feedback/model/sdk/ChannelSdk.kt",
                "src/main/kotlin/com/feedback/model/sdk/SdkIngest.kt",
                "src/main/kotlin/com/feedback/job/sdk/SdkJobHandlers.kt",
                "src/main/kotlin/com/feedback/mapper/core/CoreChannelSdkMapper.kt",
                "src/main/kotlin/com/feedback/mapper/core/CoreSdkIngestMapper.kt",
                "src/main/kotlin/com/feedback/repository/sdk/ChannelSdkRepository.kt",
                "src/main/kotlin/com/feedback/repository/sdk/SdkIngestRepository.kt",
                "src/main/kotlin/com/feedback/service/masking/SdkControlledDiagnosticBackfillRunner.kt",
                "src/main/kotlin/com/feedback/service/masking/SdkSecureProcessingScheduler.kt",
                "src/main/kotlin/com/feedback/service/masking/SdkSecureProcessingService.kt"
            ).map(projectDir::resolve)
        ).distinct()
        val declaration = Regex(
            "^\\s*(?:(?:public|private|protected|internal|open|final|abstract|sealed|data|enum|annotation|value|inline|suspend|tailrec|operator|infix|override|actual|expect|fun)\\s+)*(?:class|interface|object|fun)\\b"
        )
        val chinese = Regex("[\\u4e00-\\u9fff]")
        val placeholder = Regex("执行本类型声明|对应的领域操作|所声明的操作")
        val compressed = Regex("\\bif\\s*\\([^\\n]*\\)\\s+(?:throw\\b|return\\b|break\\b|continue\\b)|\\belse\\s+(?:throw\\b|return\\b)|^\\s*@[A-Za-z][^\\n]*\\s+(?:class|fun)\\s+")
        val violations = files.flatMap { file ->
            val lines = Files.readAllLines(file)
            lines.mapIndexedNotNull { index, line ->
                val guarded = declaration.containsMatchIn(line)
                val kdoc = adjacentKDoc(lines, index)
                val missing = guarded && !kdoc.contains(chinese)
                val meaningless = guarded && placeholder.containsMatchIn(kdoc)
                if (missing || meaningless || compressed.containsMatchIn(line)) {
                    "${projectDir.relativize(file)}:${index + 1}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "SDK 注释或控制流违规：\n${violations.joinToString("\n")}")
    }

    /** 允许 KDoc 与函数之间只有空行和注解。 */
    private fun adjacentKDoc(lines: List<String>, functionIndex: Int): String {
        var cursor = functionIndex - 1
        while (cursor >= 0 && (lines[cursor].isBlank() || lines[cursor].trimStart().startsWith("@"))) cursor--
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

    /** 递归读取 SDK Repository 实现目录。 */
    private fun filesUnder(root: String): List<Path> = Files.walk(projectDir.resolve(root)).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    /** 读取后端工程相对路径。 */
    private fun source(path: String): String = Files.readString(projectDir.resolve(path))
}
