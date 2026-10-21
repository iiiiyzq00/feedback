package com.feedback.architecture

import com.feedback.manager.station.StationFeedbackIngestCommitWorker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/** 旧反馈平台 Station 全调用链的分层、短事务、租约、幂等、批量和 SQL 门禁。 */
class StationChainArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    /** HTTP、RPC、Runner 和 Job 入口不得依赖实现、Repository、Mapper 或 PO。 */
    @Test
    fun `station entrypoints depend on contracts or workers`() {
        val files = listOf(
            "src/main/kotlin/com/feedback/controller/station/StationFeedbackController.kt",
            "src/main/kotlin/com/feedback/rpc/station/FeedbackPlatformRpcApiImpl.kt",
            "src/main/kotlin/com/feedback/job/station/StationLegacyExportImportRunner.kt",
            "src/main/kotlin/com/feedback/job/station/StationLegacyRecoveryRunner.kt",
            "src/main/kotlin/com/feedback/job/station/StationJobHandlers.kt"
        )
        files.forEach { path ->
            val content = source(path)
            assertFalse(content.contains("import com.feedback.service.impl."), path)
            assertFalse(content.contains("import com.feedback.repository."), path)
            assertFalse(content.contains("import com.feedback.mapper."), path)
            assertFalse(content.contains("import com.feedback.model.po."), path)
        }
    }

    /** Station 业务 Service 必须保持契约与 station 实现目录分离。 */
    @Test
    fun `station services keep interface implementation split`() {
        val contracts = listOf(
            "StationFeedbackIngestService", "StationFeedbackOperationsService", "StationOnlineRetryService",
            "StationRawPayloadService", "StationOnlineIdentityService", "StationOnlineFinalizationService",
            "StationDualWriteTaskService", "StationLegacyExportImportService",
            "StationLegacyImportApplicationService", "StationOnlineMaskingRecoveryService"
        )
        contracts.forEach { name ->
            assertTrue(source("src/main/kotlin/com/feedback/service/station/$name.kt").contains("interface $name"), name)
            assertTrue(Files.exists(projectDir.resolve("src/main/kotlin/com/feedback/service/impl/station/${name}Impl.kt")), name)
        }
    }

    /** Service 实现不直连 Mapper，Repository 实现不反向依赖内部 Service。 */
    @Test
    fun `station layers remain one way`() {
        filesUnder("src/main/kotlin/com/feedback/service/impl/station").forEach { file ->
            assertFalse(Files.readString(file).contains("import com.feedback.mapper."), file.toString())
        }
        filesUnder("src/main/kotlin/com/feedback/repository/impl/station").forEach { file ->
            assertFalse(Files.readString(file).contains("import com.feedback.service."), file.toString())
        }
        val repositories = source("src/main/kotlin/com/feedback/repository/station/online/StationOnlineRepositories.kt") +
            source("src/main/kotlin/com/feedback/repository/station/legacy/StationLegacyRepositories.kt")
        assertFalse(repositories.contains("@Repository"))
        assertFalse(repositories.contains("import com.feedback.mapper."))
    }

    /** 在线受理高成本工作在事务外，提交器只保留一个短写事务并在定位后复核指纹。 */
    @Test
    fun `station ingest uses prepared command and short commit transaction`() {
        val service = source("src/main/kotlin/com/feedback/service/impl/station/StationFeedbackIngestServiceImpl.kt")
        assertFalse(service.contains("@Transactional"))
        listOf("masking.mask", "requestFingerprint", "rawPayloads.prepare", "identities.preparePhone")
            .forEach { operation -> assertTrue(service.contains(operation), operation) }
        assertTrue(service.indexOf("rawPayloads.prepare") < service.indexOf("commitWorker.commit"))
        val method = StationFeedbackIngestCommitWorker::class.java.getMethod(
            "commit", com.feedback.model.station.PreparedStationIngest::class.java
        )
        assertNotNull(method.getAnnotation(Transactional::class.java))
        val worker = source("src/main/kotlin/com/feedback/manager/station/StationFeedbackIngestCommitWorker.kt")
        assertTrue(worker.indexOf("insertOrLocate") < worker.indexOf("MessageDigest.isEqual"))
        assertFalse(worker.contains("masking.mask"))
        assertFalse(worker.contains("writeValueAs"))
        assertFalse(worker.contains("encrypt("))
    }

    /** Worker 必须使用 owner 约束租约、续租与有限退避，禁止无界批处理。 */
    @Test
    fun `station workers retain lease owner and bounded retry invariants`() {
        val worker = source("src/main/kotlin/com/feedback/manager/station/StationFeedbackWorker.kt")
        listOf("claimOne(workerId)", "renewLease(sourceId, workerId)", "retry >= max", "coerceAtMost(1800)")
            .forEach { invariant -> assertTrue(worker.contains(invariant), invariant) }
        val schedulerBody = source("src/main/kotlin/com/feedback/job/station/StationFeedbackWorkerScheduler.kt")
        assertTrue(schedulerBody.contains("0 until 20"))
        val dual = source("src/main/kotlin/com/feedback/manager/station/StationDualWriteWorker.kt")
        assertTrue(dual.contains("claimOne(workerId)"))
        val dualTasks = source("src/main/kotlin/com/feedback/service/impl/station/StationDualWriteTaskServiceImpl.kt")
        assertTrue(dualTasks.contains("retry >= task.maxRetryCount"))
        assertTrue(dualTasks.contains("coerceAtMost(1800)"))
    }

    /** 十三个 Mapper 是 Online 七张表和 Legacy 六张表的语义清单，SQL 必须参数化且保持单表。 */
    @Test
    fun `station mapper inventory and sql stay bounded readable and single table`() {
        val online = xmlFiles("src/main/resources/mybatis/mapper/station/online")
        val legacy = xmlFiles("src/main/resources/mybatis/mapper/station/legacy")
        assertEquals(7, online.size)
        assertEquals(6, legacy.size)
        (online + legacy).forEach { file ->
            val xml = Files.readString(file)
            assertFalse(xml.contains("\${"), file.toString())
            assertFalse(Regex("(?i)SELECT\\s+\\*").containsMatchIn(xml), file.toString())
            assertFalse(Regex("(?i)\\bJOIN\\b|\\bEXISTS\\s*\\(").containsMatchIn(xml), file.toString())
            assertFalse(Regex("<foreach[^>]*>[^<\\n]+</foreach>").containsMatchIn(xml), file.toString())
        }
    }

    /** Station 完整链路的命名类型与函数必须有紧邻中文 KDoc，控制流和注解不得压缩。 */
    @Test
    fun `station comments and control flow remain readable`() {
        assertReadable(stationFiles(), "Station")
    }

    /** 汇总 Station 入口、配置、DTO/PO、Service、Worker、Repository、Mapper 与安全组件。 */
    private fun stationFiles(): List<Path> = listOf(
        "src/main/kotlin/com/feedback/service/station",
        "src/main/kotlin/com/feedback/service/impl/station",
        "src/main/kotlin/com/feedback/repository/station/online",
        "src/main/kotlin/com/feedback/repository/station/legacy",
        "src/main/kotlin/com/feedback/repository/impl/station",
        "src/main/kotlin/com/feedback/mapper/station",
        "src/main/kotlin/com/feedback/model/po/station",
        "src/main/kotlin/com/feedback/dto/station",
        "src/main/kotlin/com/feedback/controller/station"
        ,"src/main/kotlin/com/feedback/manager/station"
        ,"src/main/kotlin/com/feedback/job/station"
        ,"src/main/kotlin/com/feedback/model/station"
        ,"src/main/kotlin/com/feedback/rpc/station"
        ,"src/main/kotlin/com/feedback/security/station"
        ,"src/main/kotlin/com/feedback/repository/remote/station"
        ,"src/main/kotlin/com/feedback/convert/station"
    ).flatMap(::filesUnder).plus(
        listOf(
            "src/main/kotlin/com/feedback/config/StationDualWriteProperties.kt",
            "src/main/kotlin/com/feedback/config/StationExternalPortConfiguration.kt",
            "src/main/kotlin/com/feedback/controller/query/IngestionOperationsController.kt",
            "src/main/kotlin/com/feedback/job/station/StationJobHandlers.kt",
            "src/main/kotlin/com/feedback/security/station/StationGatewayAuthenticationFilter.kt"
        ).map(projectDir::resolve)
    ).distinct()

    /** 校验紧邻中文 KDoc、注解换行和非压缩 if/else。 */
    private fun assertReadable(files: List<Path>, label: String) {
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
        assertTrue(violations.isEmpty(), "$label 注释或控制流违规：\n${violations.joinToString("\n")}")
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

    /** 递归读取指定生产目录下的 Kotlin 文件。 */
    private fun filesUnder(root: String): List<Path> = Files.walk(projectDir.resolve(root)).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    /** 读取指定目录下的 Mapper XML。 */
    private fun xmlFiles(root: String): List<Path> = Files.list(projectDir.resolve(root)).use { stream ->
        stream.filter { it.fileName.toString().endsWith(".xml") }.toList()
    }

    /** 读取后端工程相对路径。 */
    private fun source(path: String): String = Files.readString(projectDir.resolve(path))
}
