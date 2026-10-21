package com.feedback.architecture

import com.feedback.manager.applicationidentity.ApplicationIdentityRebindWorker
import com.feedback.manager.applicationidentity.ApplicationIdentityShadowCommitWorker
import com.feedback.manager.applicationidentity.ApplicationIdentityShadowSnapshotReader
import com.feedback.service.impl.applicationidentity.ApplicationIdentityAdminServiceImpl
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingBatchServiceImpl
import com.feedback.service.impl.applicationidentity.ApplicationReviewedBindingPreflightServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

/** 应用身份完整调用链的分层、强类型、并发、批量、注释与 SQL 反回归门禁。 */
class ApplicationIdentityChainArchitectureTest {
    private val projectDir = Path.of(checkNotNull(System.getProperty("feedback.backend.project-dir")))

    /** 新增到应用身份目录的生产文件自动进入相同门禁，避免只抽查 Impl。 */
    private val roots = listOf(
        "src/main/kotlin/com/feedback/mapper/applicationidentity",
        "src/main/kotlin/com/feedback/manager/applicationidentity",
        "src/main/kotlin/com/feedback/model/po/applicationidentity",
        "src/main/kotlin/com/feedback/model/applicationidentity",
        "src/main/kotlin/com/feedback/job/applicationidentity",
        "src/main/kotlin/com/feedback/repository/applicationidentity",
        "src/main/kotlin/com/feedback/repository/impl/applicationidentity",
        "src/main/kotlin/com/feedback/service/applicationidentity",
        "src/main/kotlin/com/feedback/service/impl/applicationidentity"
    )

    /** HTTP 入口只能依赖 Service 契约，不得感知实现、Repository、Mapper 或 PO。 */
    @Test
    fun `application identity entrypoints depend on service contracts`() {
        val controller = source("src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminController.kt")
        listOf(
            "import com.feedback.service.impl.",
            "import com.feedback.repository.",
            "import com.feedback.mapper.",
            "import com.feedback.model.po."
        ).forEach { forbidden ->
            assertFalse(controller.contains(forbidden), "controller contains $forbidden")
        }
        assertTrue(controller.contains("ApplicationIdentityAdminService"))
        assertTrue(controller.contains("ApplicationIdentityShadowService"))
        val runner = source(
            "src/main/kotlin/com/feedback/job/applicationidentity/ApplicationReviewedBindingPreflightCommandRunner.kt"
        )
        assertFalse(runner.contains("import com.feedback.repository."))
        assertFalse(runner.contains("import com.feedback.mapper."))
        assertTrue(runner.contains("ApplicationReviewedBindingPreflightService"))
    }

    /** 四个业务 Service 保持接口与 applicationidentity 实现分离。 */
    @Test
    fun `business services keep interface implementation split`() {
        val contracts = listOf(
            "ApplicationIdentityAdminService.kt",
            "ApplicationIdentityShadowService.kt",
            "ApplicationReviewedBindingBatchService.kt",
            "ApplicationReviewedBindingPreflightService.kt"
        ).joinToString("\n") { source("src/main/kotlin/com/feedback/service/applicationidentity/$it") }
        listOf(
            "ApplicationIdentityAdminService",
            "ApplicationIdentityShadowService",
            "ApplicationReviewedBindingBatchService",
            "ApplicationReviewedBindingPreflightService"
        ).forEach { name ->
            assertTrue(contracts.contains("interface $name"), "$name must remain interface")
        }
        assertFalse(contracts.contains("@Service"))
        assertFalse(contracts.contains("@Transactional"))
        assertTrue(ApplicationIdentityAdminServiceImpl::class.java.interfaces.isNotEmpty())
        assertTrue(ApplicationReviewedBindingBatchServiceImpl::class.java.interfaces.isNotEmpty())
        assertTrue(ApplicationReviewedBindingPreflightServiceImpl::class.java.interfaces.isNotEmpty())
    }

    /** ServiceImpl 不直连 Mapper/PO，RepositoryImpl 不反向依赖内部 Service。 */
    @Test
    fun `application identity layers remain one way`() {
        filesUnder("src/main/kotlin/com/feedback/service/impl/applicationidentity").forEach { file ->
            val content = Files.readString(file)
            assertFalse(content.contains("import com.feedback.mapper."), file.toString())
            assertFalse(content.contains("import com.feedback.model.po."), file.toString())
        }
        filesUnder("src/main/kotlin/com/feedback/repository/impl/applicationidentity").forEach { file ->
            assertFalse(Files.readString(file).contains("import com.feedback.service."), file.toString())
        }
        filesUnder("src/main/kotlin/com/feedback/repository/applicationidentity").forEach { file ->
            val content = Files.readString(file)
            assertFalse(content.contains("import com.feedback.mapper."), file.toString())
            assertFalse(content.contains("import com.feedback.model.po."), file.toString())
        }
    }

    /** 查询、预检、影子读写与人工重绑事务按本地一致性阶段归属代理入口。 */
    @Test
    fun `transactions stay short and external computation stays outside`() {
        assertReadOnly(ApplicationIdentityAdminServiceImpl::class.java, "diagnostics")
        assertReadOnly(ApplicationIdentityAdminServiceImpl::class.java, "aliases")
        assertReadOnly(ApplicationReviewedBindingPreflightServiceImpl::class.java, "check")
        assertReadOnly(ApplicationIdentityShadowSnapshotReader::class.java, "loadInputs")
        assertReadOnly(ApplicationIdentityShadowSnapshotReader::class.java, "loadAliases")
        assertTransactional(ApplicationIdentityShadowCommitWorker::class.java, "commit")
        assertTransactional(ApplicationIdentityRebindWorker::class.java, "rebind")
        val worker = source("src/main/kotlin/com/feedback/manager/applicationidentity/ApplicationIdentityShadowWorker.kt")
        assertFalse(worker.contains("@Transactional"))
        assertTrue(worker.indexOf("ApplicationAliasInput::normalized") < worker.indexOf("commitWorker.commit(plan)"))
    }

    /** 人工重绑固定锁序、锁后幂等复核、双 identity CAS 与有限重试不可退化。 */
    @Test
    fun `rebind keeps lock idempotency and cas invariants`() {
        val worker = source("src/main/kotlin/com/feedback/manager/applicationidentity/ApplicationIdentityRebindWorker.kt")
        val body = worker.substringAfter("fun rebind(").substringBefore("private fun replayAfterLock")
        assertTrue(body.indexOf("lockActiveAlias") < body.indexOf("lockManualResult"))
        assertTrue(body.indexOf("lockManualResult") < body.indexOf("lockIdentitiesInOrder"))
        assertTrue(body.contains("listOf(alias.applicationIdentityId, target.id)"))
        assertTrue(body.contains("incrementVersions(listOf(lockedSource, lockedTarget))"))
        assertTrue(body.indexOf("incrementVersions") < body.indexOf("supersedeAlias"))
        assertTrue(body.indexOf("supersedeAlias") < body.indexOf("insertManualAlias"))
        assertTrue(body.indexOf("insertManualAlias") < body.indexOf("appendAudit"))
        val admin = source("src/main/kotlin/com/feedback/service/impl/applicationidentity/ApplicationIdentityAdminServiceImpl.kt")
        val retry = admin.substringAfter("private fun <T> retryDuplicateOnce").substringBefore("private fun ApplicationIdentityAliasSnapshot")
        assertEquals(2, Regex("action\\(\\)").findAll(retry).count())
    }

    /** 影子写入必须重读事务外 alias 快照并以版本/CAS/唯一键重新确认。 */
    @Test
    fun `shadow commit rejects stale snapshots and remains bounded`() {
        val commit = source("src/main/kotlin/com/feedback/manager/applicationidentity/ApplicationIdentityShadowCommitWorker.kt")
        val body = commit.substringAfter("fun commit(plan").substringBefore("private fun buildAutoBindings")
        assertTrue(body.indexOf("identities.activeAliases(allKeys)") < body.indexOf("requireUnchangedAliasSnapshot"))
        assertTrue(body.indexOf("requireUnchangedAliasSnapshot") < body.indexOf("createIdentities"))
        assertTrue(body.contains("incrementVersions(existingTargetsToBump)"))
        assertTrue(body.contains("facts.saveAll(rows)"))
        val repository = source(
            "src/main/kotlin/com/feedback/repository/impl/applicationidentity/MyBatisApplicationIdentitySourceRepositoryImpl.kt"
        )
        assertTrue(repository.contains("ApplicationIdentitySourceRepository.SHADOW_BATCH_SIZE"))
        assertTrue(repository.contains("APPLICATION_SOURCE_ORDER_INVALID"))
        assertTrue(repository.contains("\${code}_MISSING"))
        val preflight = source(
            "src/main/kotlin/com/feedback/service/impl/applicationidentity/ApplicationReviewedBindingPreflightServiceImpl.kt"
        )
        assertTrue(preflight.contains("activeByDisplayNames(targetNames)"))
        assertFalse(preflight.contains("inputs.map { identities.activeByDisplayName"))
    }

    /** 封闭状态与动作使用强类型，稳定字符串仅在 HTTP/MyBatis/审计边界转换。 */
    @Test
    fun `application identity business scalars stay typed`() {
        val models = source("src/main/kotlin/com/feedback/model/applicationidentity/ApplicationIdentityPersistenceModels.kt")
        listOf(
            "ApplicationResolutionStatus",
            "ApplicationAliasType",
            "ApplicationIdentityLifecycleStatus",
            "ApplicationAliasBindingStatus",
            "ApplicationAliasEvidenceType",
            "ApplicationSourceChannel"
        ).forEach { type ->
            assertTrue(models.contains("enum class $type"), "$type must remain typed")
        }
        val adminModels = source("src/main/kotlin/com/feedback/model/applicationidentity/ApplicationIdentityAdminModels.kt")
        assertTrue(adminModels.contains("val decision: ApplicationReviewedBindingDecision"))
        assertTrue(adminModels.contains("val lifecycleStatus: ApplicationIdentityLifecycleStatus"))
        assertTrue(adminModels.contains("val bindingStatus: ApplicationAliasBindingStatus"))
    }

    /** 七个 Mapper 是逐职责语义清单；SafeText 是单表只读安全边界，不是意外写域扩张。 */
    @Test
    fun `mapper xml inventory and sql remain semantic readable and bounded`() {
        val directory = projectDir.resolve("src/main/resources/mybatis/mapper/applicationidentity")
        val files = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".xml") }.toList()
        }
        assertEquals(
            setOf(
                "ApplicationIdentityAliasMapper.xml",
                "ApplicationIdentityMapper.xml",
                "ApplicationIdentityPrimaryFactMapper.xml",
                "ApplicationIdentitySafeTextMapper.xml",
                "ApplicationIdentitySdkMapper.xml",
                "ApplicationIdentitySourceMapper.xml",
                "ApplicationIdentityStationMapper.xml"
            ),
            files.map { it.fileName.toString() }.toSet()
        )
        files.forEach { file ->
            val xml = Files.readString(file)
            assertFalse(Regex("(?i)\\bJOIN\\b|\\bEXISTS\\s*\\(").containsMatchIn(xml), file.toString())
            assertFalse(xml.contains("\${"), file.toString())
            assertFalse(Regex("(?i)SELECT\\s+\\*").containsMatchIn(xml), file.toString())
            assertFalse(Regex("<foreach[^>]*>[^<\\n]+</foreach>").containsMatchIn(xml), file.toString())
            assertFalse(Regex("(?i)SELECT [^\\n]+ FROM ").containsMatchIn(xml), file.toString())
        }
    }

    /** 完整应用身份目录内每个函数都必须有紧邻中文 KDoc。 */
    @Test
    fun `application identity methods have adjacent chinese KDoc`() {
        val declaration = Regex(
            "^\\s*(?!fun\\s+interface)(?:(?:public|private|protected|internal|open|final|suspend|inline|tailrec|operator|infix|override)\\s+)*fun\\s+"
        )
        val chinese = Regex("[\\u4e00-\\u9fff]")
        val violations = chainFiles().flatMap { file ->
            val lines = Files.readAllLines(file)
            lines.mapIndexedNotNull { index, line ->
                if (declaration.containsMatchIn(line) && !adjacentKDoc(lines, index).contains(chinese)) {
                    "${projectDir.relativize(file)}:${index + 1} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "应用身份函数缺少紧邻中文 KDoc：\n${violations.joinToString("\n")}")
    }

    /** 注解、if/else 与执行体必须分行，锁/CAS/失败路径不能压缩隐藏。 */
    @Test
    fun `application identity control flow stays readable`() {
        val annotationAndFunction = Regex("^\\s*@[A-Za-z][^\\n]*\\s+fun\\s+")
        val compressedIf = Regex("^\\s*if\\s*\\(.*\\)\\s+(?:throw\\b|return\\b|[A-Za-z_][A-Za-z0-9_]*\\s*=)")
        val compressedElse = Regex("^\\s*else\\s+(?!if\\b|\\{)(?:throw\\b|return\\b|[A-Za-z_])")
        val violations = chainFiles().flatMap { file ->
            Files.readAllLines(file).mapIndexedNotNull { index, line ->
                if (annotationAndFunction.containsMatchIn(line) ||
                    compressedIf.containsMatchIn(line) ||
                    compressedElse.containsMatchIn(line)
                ) {
                    "${projectDir.relativize(file)}:${index + 1} -> ${line.trim()}"
                } else {
                    null
                }
            }
        }
        assertTrue(violations.isEmpty(), "应用身份代码存在压缩控制流：\n${violations.joinToString("\n")}")
    }

    /** 允许 KDoc 与函数间仅出现空行和注解。 */
    private fun adjacentKDoc(lines: List<String>, functionIndex: Int): String {
        var cursor = functionIndex - 1
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
            if (lines[cursor].trimStart().startsWith("/*")) {
                return ""
            }
            cursor--
        }
        return ""
    }

    /** 汇总完整应用身份目录以及 HTTP 契约/入口文件。 */
    private fun chainFiles(): List<Path> = buildList {
        roots.forEach { root -> addAll(filesUnder(root)) }
        add(projectDir.resolve("src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminContracts.kt"))
        add(projectDir.resolve("src/main/kotlin/com/feedback/controller/admin/ApplicationIdentityAdminController.kt"))
    }.distinct().sorted()

    /** 递归读取一个生产目录下所有 Kotlin 文件。 */
    private fun filesUnder(root: String): List<Path> = Files.walk(projectDir.resolve(root)).use { stream ->
        stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }.toList()
    }

    /** 读取后端工程相对路径。 */
    private fun source(path: String): String = Files.readString(projectDir.resolve(path))

    /** 断言 public Spring 代理入口拥有写事务。 */
    private fun assertTransactional(type: Class<*>, methodName: String) {
        val method = type.declaredMethods.single { it.name == methodName && !it.isBridge }
        assertTrue(method.isAnnotationPresent(Transactional::class.java), "${type.simpleName}.$methodName")
    }

    /** 断言 public Spring 代理入口拥有只读事务。 */
    private fun assertReadOnly(type: Class<*>, methodName: String) {
        val method = type.declaredMethods.single { it.name == methodName && !it.isBridge }
        assertTrue(method.getAnnotation(Transactional::class.java)?.readOnly == true, "${type.simpleName}.$methodName")
    }
}
