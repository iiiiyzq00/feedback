package com.feedback.job.applicationidentity

import com.feedback.strategy.applicationidentity.reviewedBindingCommandFingerprint
import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** 一次性只读预检入口；仅依赖 Service，目标 ID 只写入 owner-only 准备文件。 */
class ApplicationReviewedBindingPreflightCommandRunner(
    private val preflight: ApplicationReviewedBindingPreflightService,
    private val json: ObjectMapper,
    @param:Value("\${app.application-identity.reviewed-binding-preflight.confirmation:}")
    private val confirmation: String,
    @param:Value("\${app.application-identity.reviewed-binding-preflight.input-path:}")
    private val inputPath: String,
    @param:Value("\${app.application-identity.reviewed-binding-preflight.output-path:}")
    private val outputPath: String
) : ApplicationRunner {
    /** 文件 I/O、JSON 与哈希均在数据库事务外；Service 只负责批量业务查询和预检。 */
    override fun run(args: ApplicationArguments) {
        requireInput(confirmation == CONFIRMATION, "APPLICATION_PREFLIGHT_CONFIRMATION_REQUIRED")
        requireInput(inputPath.isNotBlank() && outputPath.isNotBlank(), "APPLICATION_PREFLIGHT_PATH_REQUIRED")
        val request = Files.newBufferedReader(Path.of(inputPath), StandardCharsets.UTF_8).use {
            json.readValue(it, ReviewedBindingPreflightFile::class.java)
        }
        requireInput(request.confirmation == CONFIRMATION, "APPLICATION_PREFLIGHT_FILE_CONFIRMATION_REQUIRED")
        val commands = preflight.prepare(request.decisions.map(::prepareInput))
        val report = preflight.check(commands)
        requireState(report.ready, "APPLICATION_PREFLIGHT_NOT_READY")

        val prepared = PreparedReviewedBindingCommandFile(
            schemaVersion = SCHEMA_VERSION,
            commands = commands.map { command ->
                PreparedReviewedBindingCommand(command, reviewedBindingCommandFingerprint(command))
            }
        )
        val bytes = json.writeValueAsBytes(prepared)
        writeOwnerOnly(Path.of(outputPath), bytes)
        val result = ReviewedBindingPreflightCliResult(
            decisionCount = report.decisionCount,
            newApplicationCount = report.newApplicationCount,
            existingApplicationAliasCount = report.existingApplicationAliasCount,
            expectedAffectedSourceCount = report.expectedAffectedSourceCount,
            replayableCount = report.replayableCount,
            ready = report.ready,
            commandFileSha256 = sha256(bytes)
        )
        System.out.println(json.writeValueAsString(result))
    }

    /** 将开放文件编码严格转换为强类型准备输入，不查询数据库。 */
    private fun prepareInput(input: ReviewedBindingPreflightDecision): ApplicationReviewedBindingPreparation {
        val decision = runCatching { ApplicationReviewedBindingDecision.fromCode(input.decision) }
            .getOrElse { throw ClientException(ErrorCode.BAD_REQUEST, "APPLICATION_PREFLIGHT_DECISION_INVALID") }
        return ApplicationReviewedBindingPreparation(
            candidateDisplayName = input.candidateDisplayName,
            expectedSourceCount = input.expectedSourceCount,
            decision = decision,
            targetDisplayName = input.targetDisplayName,
            reason = input.reason
        )
    }

    /** 以 0600 原子创建准备文件；写入失败会删除空/半成品文件。 */
    private fun writeOwnerOnly(path: Path, bytes: ByteArray) {
        requireInput(path.parent != null && Files.isDirectory(path.parent), "APPLICATION_PREFLIGHT_OUTPUT_PARENT_INVALID")
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
        Files.createFile(path, permissions)
        try {
            Files.newOutputStream(path, StandardOpenOption.WRITE).use { it.write(bytes) }
        } catch (error: Exception) {
            Files.deleteIfExists(path)
            throw error
        }
    }

    /** 对最终准备文件字节计算稳定 SHA-256，便于人工核对未被篡改。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CONFIRMATION = "READ_ONLY_APPLICATION_BINDING_PREFLIGHT"
        const val SCHEMA_VERSION = "application-reviewed-binding-command-v1"
    }
}

/** 离线审核输入文件顶层契约。 */
data class ReviewedBindingPreflightFile(
    val confirmation: String = "",
    val decisions: List<ReviewedBindingPreflightDecision> = emptyList()
)

/** 单条离线审核决策；目标仅以展示名出现于输入文件。 */
data class ReviewedBindingPreflightDecision(
    val candidateDisplayName: String = "",
    val expectedSourceCount: Long = 0,
    val decision: String = "",
    val targetDisplayName: String? = null,
    val reason: String = ""
)

/** owner-only 准备文件顶层契约。 */
data class PreparedReviewedBindingCommandFile(
    val schemaVersion: String,
    val commands: List<PreparedReviewedBindingCommand>
)

/** 正式命令及其稳定指纹，供后续人工执行前核验。 */
data class PreparedReviewedBindingCommand(
    val command: ApplicationReviewedBindingCommand,
    val commandFingerprintHex: String
)

/** 标准输出仅含计数与文件哈希，不泄露目标 ID 或来源文本。 */
data class ReviewedBindingPreflightCliResult(
    val decisionCount: Int,
    val newApplicationCount: Int,
    val existingApplicationAliasCount: Int,
    val expectedAffectedSourceCount: Long,
    val replayableCount: Int,
    val ready: Boolean,
    val commandFileSha256: String
)

/** 校验文件/CLI 输入条件并返回稳定 400 原因码。 */
private fun requireInput(condition: Boolean, reason: String) {
    if (!condition) {
        throw ClientException(ErrorCode.BAD_REQUEST, reason)
    }
}

/** 校验预检结果状态并返回稳定冲突原因码。 */
private fun requireState(condition: Boolean, reason: String) {
    if (!condition) {
        throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reason)
    }
}
