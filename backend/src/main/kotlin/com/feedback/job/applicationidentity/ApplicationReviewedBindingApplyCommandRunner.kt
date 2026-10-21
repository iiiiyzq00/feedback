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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/** 显式确认的一次性 APPLY 入口；只依赖 Service 契约且文件/哈希 I/O 位于事务外。 */
class ApplicationReviewedBindingApplyCommandRunner(
    private val preflight: ApplicationReviewedBindingPreflightService,
    private val batch: ApplicationReviewedBindingBatchService,
    private val json: ObjectMapper,
    @param:Value("\${app.application-identity.reviewed-binding-apply.confirmation:}")
    private val confirmation: String,
    @param:Value("\${app.application-identity.reviewed-binding-apply.command-path:}")
    private val commandPath: String,
    @param:Value("\${app.application-identity.reviewed-binding-apply.expected-sha256:}")
    private val expectedSha256: String,
    @param:Value("\${app.application-identity.reviewed-binding-apply.actor-id:0}")
    private val actorId: Long
) : ApplicationRunner {
    /** 校验确认口令、0600 文件、SHA、指纹、范围与预检后，才进入一个短写事务。 */
    override fun run(args: ApplicationArguments) {
        requireInput(confirmation == CONFIRMATION, "APPLICATION_REVIEW_APPLY_CONFIRMATION_REQUIRED")
        requireInput(commandPath.isNotBlank(), "APPLICATION_REVIEW_APPLY_COMMAND_PATH_REQUIRED")
        requireInput(expectedSha256.matches(SHA256), "APPLICATION_REVIEW_APPLY_SHA256_INVALID")
        requireInput(actorId > 0, "APPLICATION_REVIEW_APPLY_ACTOR_INVALID")
        val path = Path.of(commandPath)
        requireInput(Files.isRegularFile(path), "APPLICATION_REVIEW_APPLY_COMMAND_FILE_MISSING")
        requireInput(
            Files.getPosixFilePermissions(path) == PosixFilePermissions.fromString("rw-------"),
            "APPLICATION_REVIEW_APPLY_COMMAND_FILE_MODE_INVALID"
        )
        val bytes = Files.readAllBytes(path)
        requireInput(sha256(bytes) == expectedSha256, "APPLICATION_REVIEW_APPLY_COMMAND_FILE_CHANGED")
        val prepared = json.readValue(bytes, PreparedReviewedBindingCommandFile::class.java)
        requireInput(
            prepared.schemaVersion == ApplicationReviewedBindingPreflightCommandRunner.SCHEMA_VERSION,
            "APPLICATION_REVIEW_APPLY_SCHEMA_INVALID"
        )
        val commands = prepared.commands.map { row ->
            requireInput(
                row.commandFingerprintHex == reviewedBindingCommandFingerprint(row.command),
                "APPLICATION_REVIEW_APPLY_FINGERPRINT_INVALID"
            )
            row.command
        }
        requireInput(commands.size == EXPECTED_DECISION_COUNT, "APPLICATION_REVIEW_APPLY_DECISION_COUNT_INVALID")
        requireInput(
            commands.map { it.requestId }.distinct().size == commands.size,
            "APPLICATION_REVIEW_APPLY_REQUEST_DUPLICATED"
        )
        requireInput(
            commands.sumOf { it.expectedSourceCount } == EXPECTED_AFFECTED_COUNT,
            "APPLICATION_REVIEW_APPLY_SCOPE_INVALID"
        )
        requireInput(
            commands.count { it.decision == ApplicationReviewedBindingDecision.NEW_APPLICATION } == 3 &&
                commands.count { it.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING } == 1,
            "APPLICATION_REVIEW_APPLY_DECISION_MIX_INVALID"
        )
        val report = preflight.check(commands)
        requireState(report.ready, "APPLICATION_REVIEW_APPLY_PREFLIGHT_NOT_READY")
        requireState(
            report.replayableCount == 0 || report.replayableCount == commands.size,
            "APPLICATION_REVIEW_APPLY_PARTIAL_REPLAY"
        )
        val results = batch.createAll(commands, actorId)
        requireState(results.size == commands.size, "APPLICATION_REVIEW_APPLY_RESULT_INCOMPLETE")
        requireState(
            results.sumOf { it.affectedSourceCount } == EXPECTED_AFFECTED_COUNT,
            "APPLICATION_REVIEW_APPLY_RESULT_SCOPE_INVALID"
        )
        System.out.println(
            json.writeValueAsString(
                ApplicationReviewedBindingApplyResult(
                    decisionCount = results.size,
                    newApplicationCount = commands.count {
                        it.decision == ApplicationReviewedBindingDecision.NEW_APPLICATION
                    },
                    existingApplicationAliasCount = commands.count {
                        it.decision == ApplicationReviewedBindingDecision.ALIAS_OF_EXISTING
                    },
                    affectedSourceCount = results.sumOf { it.affectedSourceCount },
                    replayedCount = results.count { it.replayed },
                    applied = true,
                    commandFileSha256 = expectedSha256
                )
            )
        )
    }

    /** 对准备文件原始字节计算 SHA-256，禁止重序列化绕过篡改检测。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val CONFIRMATION = "APPLY_FOUR_APPROVED_APPLICATION_BINDINGS"
        const val EXPECTED_DECISION_COUNT = 4
        const val EXPECTED_AFFECTED_COUNT = 207L
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}

/** APPLY 标准输出只含计数与文件哈希，不泄露命令目标或候选文本。 */
data class ApplicationReviewedBindingApplyResult(
    val decisionCount: Int,
    val newApplicationCount: Int,
    val existingApplicationAliasCount: Int,
    val affectedSourceCount: Long,
    val replayedCount: Int,
    val applied: Boolean,
    val commandFileSha256: String
)

/** 校验 APPLY 输入并返回稳定 400 原因码。 */
private fun requireInput(condition: Boolean, reason: String) {
    if (!condition) {
        throw ClientException(ErrorCode.BAD_REQUEST, reason)
    }
}

/** 校验预检/写入结果并返回稳定 409 原因码。 */
private fun requireState(condition: Boolean, reason: String) {
    if (!condition) {
        throw ServiceException(ErrorCode.APPLICATION_IDENTITY_CONFLICT, reason)
    }
}
