package com.feedback.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** 附件域分层、强类型、短事务、逐方法注释与 SQL 可读性反回归门禁。 */
class AttachmentChainArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    /** 附件调用链的生产源码根；新增文件会自动进入注释和控制流检查。 */
    private val attachmentRoots = listOf(
        "src/main/kotlin/com/feedback/application/port/feedback/attachment",
        "src/main/kotlin/com/feedback/mapper/attachment",
        "src/main/kotlin/com/feedback/manager/attachment",
        "src/main/kotlin/com/feedback/model/attachment",
        "src/main/kotlin/com/feedback/model/po/attachment",
        "src/main/kotlin/com/feedback/repository/attachment",
        "src/main/kotlin/com/feedback/repository/impl/attachment",
        "src/main/kotlin/com/feedback/service/attachment",
        "src/main/kotlin/com/feedback/service/impl/attachment",
        "src/main/kotlin/com/feedback/security/attachment"
    )

    /** 与附件链直接相关但不能整目录纳入的跨层生产文件。 */
    private val attachmentFiles = listOf(
        "src/main/kotlin/com/feedback/common/AttachmentBusinessTypes.kt",
        "src/main/kotlin/com/feedback/config/AttachmentMediaPreviewProperties.kt",
        "src/main/kotlin/com/feedback/config/AttachmentObjectStorageConfiguration.kt",
        "src/main/kotlin/com/feedback/config/AttachmentObjectStorageProperties.kt",
        "src/main/kotlin/com/feedback/job/attachment/AttachmentJobHandlers.kt",
        "src/main/kotlin/com/feedback/repository/audit/AttachmentAuditRepository.kt",
        "src/main/kotlin/com/feedback/service/impl/attachment/FeedbackAttachmentApplicationServiceImpl.kt",
        "src/main/kotlin/com/feedback/tools/AttachmentObjectMigrationMain.kt"
    )

    /** HTTP 与后台入口不得越过 Service/UseCase 依赖实现、Repository 或 Mapper。 */
    @Test
    fun `attachment entrypoints depend on service contracts`() {
        listOf(
            "src/main/kotlin/com/feedback/controller/query/FeedbackController.kt",
            "src/main/kotlin/com/feedback/job/attachment/AttachmentJobHandlers.kt"
        ).forEach { path ->
            val content = source(path)
            assertFalse(content.contains("import com.feedback.service.impl."), "$path imports ServiceImpl")
            assertFalse(content.contains("import com.feedback.mapper."), "$path imports Mapper")
            assertFalse(content.contains("import com.feedback.repository."), "$path imports Repository")
        }
    }

    /** 业务 ServiceImpl 只依赖端口或领域组件，不能直连 MyBatis Mapper。 */
    @Test
    fun `attachment service implementations do not import mapper`() {
        attachmentFiles()
            .filter { projectDir.relativize(it).toString().contains("/service/impl/") }
            .forEach { file ->
                assertFalse(
                    Files.readString(file).contains("import com.feedback.mapper."),
                    "${projectDir.relativize(file)} imports Mapper"
                )
            }
    }

    /** 三类复杂附件仓储保持接口与 MyBatis 实现分离，避免上层重新依赖具体类。 */
    @Test
    fun `complex attachment repositories keep interface implementation split`() {
        val contracts = source("src/main/kotlin/com/feedback/repository/attachment/SecureAttachmentRepository.kt") +
            source("src/main/kotlin/com/feedback/repository/attachment/AttachmentGrantRepository.kt") +
            source("src/main/kotlin/com/feedback/repository/attachment/AttachmentSecurityScanRepository.kt") +
            source("src/main/kotlin/com/feedback/repository/attachment/FeedbackAttachmentMutationRepository.kt")
        listOf(
            "SecureAttachmentRepository",
            "AttachmentPreviewRepository",
            "AttachmentObjectLifecycleRepository",
            "AttachmentGrantRepository",
            "AttachmentSecurityScanRepository",
            "FeedbackAttachmentMutationRepository"
        ).forEach { name ->
            assertTrue(contracts.contains("interface $name"), "$name must remain an interface")
        }
        val implementations = source("src/main/kotlin/com/feedback/repository/impl/attachment/MyBatisSecureAttachmentRepositoryImpl.kt") +
            source("src/main/kotlin/com/feedback/repository/impl/attachment/MyBatisAttachmentGrantRepositoryImpl.kt") +
            source("src/main/kotlin/com/feedback/repository/impl/attachment/MyBatisAttachmentSecurityScanRepositoryImpl.kt") +
            source("src/main/kotlin/com/feedback/repository/impl/attachment/MyBatisFeedbackAttachmentMutationRepositoryImpl.kt")
        assertFalse(implementations.contains("@Transactional"), "single-statement attachment CAS must not open transactions")
    }

    /** 附件状态与授权动作必须通过领域类型传播，字符串只允许出现在集中字典和边界映射。 */
    @Test
    fun `attachment business scalars remain typed`() {
        val contracts = source("src/main/kotlin/com/feedback/application/port/feedback/attachment/FeedbackAttachmentReadContracts.kt") +
            source("src/main/kotlin/com/feedback/model/attachment/FeedbackAttachmentAccessModels.kt")
        listOf(
            "AttachmentType?",
            "AttachmentUploadStatus?",
            "AttachmentIntegrityStatus?",
            "AttachmentDecryptionStatus?",
            "AttachmentMalwareScanStatus?",
            "AttachmentContentReviewStatus?",
            "AttachmentPreviewStatus?",
            "AttachmentAuthorizationAction",
            "AttachmentAuditOperation"
        ).forEach { type ->
            assertTrue(contracts.contains(type), "$type must remain in internal contracts")
        }
        val implementation = source("src/main/kotlin/com/feedback/service/impl/attachment/FeedbackAttachmentApplicationServiceImpl.kt")
        val forbidden = Regex("\"(?:PENDING|PROCESSING|AVAILABLE|UNAVAILABLE|VERIFIED|DECRYPTED|CLEAN|PASS|DIRECT_VIEW|DERIVED_PREVIEW|ORIGINAL_LOG_DOWNLOAD|ATTACHMENT_PREVIEW|ATTACHMENT_DECRYPT)\"")
        assertFalse(forbidden.containsMatchIn(implementation), "attachment application service reintroduced scalar literals")
        val batchAuthorization = implementation.substringAfter("override fun authorizePreviews").substringBefore("private fun authorizePreview")
        assertTrue(batchAuthorization.contains("findAuthorizationFacts(ids, command.sourceId)"))
        assertFalse(
            batchAuthorization.contains("findAuthorizationFacts(attachmentId, command.sourceId)"),
            "batch preview authorization must not reintroduce N+1 reads"
        )
    }

    /** 授权与审计保持一个短写事务，签发器和单条 CAS 不重复扩大事务。 */
    @Test
    fun `attachment transactions stay short and explicit`() {
        val authorization = source("src/main/kotlin/com/feedback/service/impl/attachment/FeedbackAttachmentApplicationServiceImpl.kt")
        assertTrue(authorization.substringAfter("@Transactional\n    override fun authorize(").isNotEmpty())
        assertTrue(authorization.substringAfter("@Transactional\n    override fun authorizePreviews(").isNotEmpty())
        val streamService = source("src/main/kotlin/com/feedback/service/impl/attachment/AttachmentSecureStreamServiceImpl.kt")
        assertFalse(streamService.contains("@Transactional"), "stream operations must not wrap file or object I/O in a transaction")
        val support = source("src/main/kotlin/com/feedback/manager/attachment/AttachmentSecureStreamSupport.kt")
        val consumer = support.substringAfter("class AttachmentGrantConsumer").substringBefore("class SpringAttachmentGrantTransactionAdapter")
        assertTrue(consumer.contains("transactions.execute"))
        assertTrue(consumer.indexOf("grantConsumer") == -1)
        assertFalse(consumer.contains("provider.open"), "content must open only after grant transaction commits")
    }

    /** 附件 Mapper XML 必须字段显式、动态集合换行、候选查询有界。 */
    @Test
    fun `attachment sql remains readable and bounded`() {
        val xml = source("src/main/resources/mybatis/mapper/attachment/FeedbackAttachmentMapper.xml")
        assertFalse(xml.contains("SELECT *"))
        assertFalse(Regex("<(?:select|insert|update)[^>]*>\\s*(?:SELECT|INSERT|UPDATE) [^\\n]+(?:FROM|SET|VALUES)").containsMatchIn(xml))
        assertFalse(Regex("<foreach[^>]*>[^<\\n]+</foreach>").containsMatchIn(xml))
        listOf("findPendingSecurityScan", "findMissingLogPreviewIds", "findMediaPreviewCandidates").forEach { id ->
            val statement = xml.substringAfter("id=\"$id\"").substringBefore(if (id.startsWith("find")) "</select>" else "</update>")
            assertTrue(statement.contains("LIMIT #{limit}"), "$id must remain bounded")
        }
    }

    /** 所有附件实现函数都必须有紧邻中文 KDoc，接口注释不能替代实现说明。 */
    @Test
    fun `attachment implementation methods have adjacent chinese KDoc`() {
        val functionDeclaration = Regex(
            "^\\s*(?!fun\\s+interface)(?:(?:public|private|protected|internal|open|final|suspend|inline|tailrec|operator|infix|override)\\s+)*fun\\s+"
        )
        val chinese = Regex("[\\u4e00-\\u9fff]")
        val violations = attachmentFiles().flatMap { file ->
            val lines = Files.readAllLines(file)
            lines.mapIndexedNotNull { index, line ->
                if (functionDeclaration.containsMatchIn(line) &&
                    (!hasAdjacentKDoc(lines, index) || !adjacentKDoc(lines, index).contains(chinese))
                ) {
                    "${projectDir.relativize(file)}:${index + 1} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "附件函数必须有紧邻中文 KDoc：\n${violations.joinToString("\n")}")
    }

    /** 禁止注解、条件和执行体压缩在同一行，避免锁/CAS/失败路径被隐藏。 */
    @Test
    fun `attachment control flow remains readable`() {
        val annotationAndFunction = Regex("^\\s*@[A-Za-z][^\\n]*\\s+fun\\s+")
        val compressedIf = Regex("^\\s*if\\s*\\(.*\\)\\s+(?:throw\\b|return\\b|[A-Za-z_][A-Za-z0-9_]*\\s*=)")
        val violations = attachmentFiles().flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                if (annotationAndFunction.containsMatchIn(line) || compressedIf.containsMatchIn(line)) {
                    "${projectDir.relativize(file)}:${index + 1} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "附件代码存在压缩控制流：\n${violations.joinToString("\n")}")
    }

    /** 取得函数前紧邻的 KDoc 文本；普通块注释不计入。 */
    private fun adjacentKDoc(lines: List<String>, functionIndex: Int): String {
        var cursor = functionIndex - 1
        while (cursor >= 0 && (lines[cursor].isBlank() || lines[cursor].trimStart().startsWith("@"))) {
            cursor--
        }
        if (cursor < 0 || !lines[cursor].trimEnd().endsWith("*/")) {
            return ""
        }
        val collected = mutableListOf<String>()
        while (cursor >= 0) {
            collected += lines[cursor]
            if (lines[cursor].trimStart().startsWith("/**")) {
                return collected.asReversed().joinToString("\n")
            }
            if (lines[cursor].trimStart().startsWith("/*")) {
                return ""
            }
            cursor--
        }
        return ""
    }

    /** 判断函数前是否存在紧邻 KDoc。 */
    private fun hasAdjacentKDoc(lines: List<String>, functionIndex: Int): Boolean =
        adjacentKDoc(lines, functionIndex).isNotEmpty()

    /** 递归收集附件生产源码，使新增文件自动接受相同门禁。 */
    private fun attachmentFiles(): List<Path> = buildList {
        attachmentRoots.forEach { root ->
            Files.walk(projectDir.resolve(root)).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                    .forEach(::add)
            }
        }
        attachmentFiles.forEach { add(projectDir.resolve(it)) }
    }.distinct().sorted()

    /** 读取后端工程内的相对路径源码。 */
    private fun source(path: String): String = Files.readString(projectDir.resolve(path))
}
