package com.feedback.service.masking

import com.feedback.model.po.sdk.SdkChannelPo
import com.feedback.model.po.sdk.SdkIdentityPo
import com.feedback.model.po.sdk.SdkProcessingEnvelopePo
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.sdk.SdkChannelMutationRepository
import com.feedback.repository.sdk.SdkIdentityMutationRepository
import com.feedback.repository.sdk.SdkProcessingEnvelopeRepository
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.security.sdk.TransportKeyService
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import javax.crypto.AEADBadTagException

/**
 * 以 source ID keyset 分批迁移 SDK 受控诊断和身份密文。
 *
 * 每批最多读取 200 个 SDK 渠道事实，再分别批量读取接入信封和身份表；纯内存完成解密与命令组装，
 * 单表批量写入后在同一事务标脏。单条无法解密只跳过该条且不产生写入，数据库写入或标脏失败则整批回滚。
 */
@Service
class SdkControlledDiagnosticBackfillBatchService(
    private val ingests: SdkProcessingEnvelopeRepository,
    private val identities: SdkIdentityMutationRepository,
    private val channels: SdkChannelMutationRepository,
    private val transportKeys: TransportKeyService,
    private val envelopeCrypto: EnvelopeCrypto,
    private val contacts: ContactStorageCrypto,
    private val fingerprints: IdentityFingerprintService,
    private val objectMapper: ObjectMapper,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker
) {
    private val log = LoggerFactory.getLogger(javaClass)

/** 处理一页 SDK 诊断回填候选；高成本解密在事务外，写入使用条件更新。 */
    @Transactional
    fun process(channelRows: List<SdkChannelPo>): Int {
        if(channelRows.size !in 1..MAX_BATCH_SIZE) maskingInvalid("SDK_DIAGNOSTIC_BACKFILL_BATCH_INVALID")
        val sourceIds = channelRows.map(SdkChannelPo::sourceId).distinct().sorted()
        val ingestBySource = ingests.findBySourceIds(sourceIds).associateBy { it.feedbackSourceId }
        val identityByKey = identities.findBySourceIds(sourceIds).associateBy {
            Triple(it.sourceId, it.identityType, it.identityScope)
        }
        val channelUpdates = mutableListOf<SdkChannelPo>()
        val identityUpdates = mutableListOf<SdkIdentityPo>()
        val identityChangedSources = linkedSetOf<Long>()
        var completed = 0

        channelRows.forEach { channel ->
            val prepared = try {
                prepare(channel, ingestBySource[channel.sourceId], identityByKey)
            } catch (error: LegacySdkPayloadUnreadableException) {
                log.warn(
                    "SDK controlled diagnostic backfill skipped: sourceId={}, errorType={}",
                    channel.sourceId,
                    error.cause?.javaClass?.simpleName ?: error.javaClass.simpleName
                )
                return@forEach
            }
            prepared.channelUpdate?.let(channelUpdates::add)
            identityUpdates += prepared.identityUpdates
            if (prepared.identityUpdates.isNotEmpty()) identityChangedSources += channel.sourceId
            completed++
        }

        if (channelUpdates.isNotEmpty()) channels.upsertControlledDiagnostics(channelUpdates)
        if (identityUpdates.isNotEmpty()) identities.upsertBatch(identityUpdates)
        val channelSources = channelUpdates.map(SdkChannelPo::sourceId).distinct().sorted()
        if (channelSources.isNotEmpty()) {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(channelSources, FeedbackListProjectionDirtyReason.CHANNEL))
        }
        val identityOnlySources = (identityChangedSources - channelSources.toSet()).sorted()
        if (identityOnlySources.isNotEmpty()) {
            dirtyMarker.mark(FeedbackListProjectionDirtyCommand(identityOnlySources, FeedbackListProjectionDirtyReason.IDENTITY))
        }
        return completed
    }

/** 在事务外解密、规范化并组装 SDK 回填命令，不在准备阶段写数据库。 */
    private fun prepare(
        channel: SdkChannelPo,
        ingest: SdkProcessingEnvelopePo?,
        existingIdentities: Map<Triple<Long, String, String>, SdkIdentityPo>
    ): PreparedBackfill {
        val controlled = linkedMapOf<String, String>()
        channel.appInternalLogs?.takeIf(String::isNotBlank)?.let { controlled["app_internal_logs"] = it }
        channel.accountContext?.takeIf(String::isNotBlank)?.let { controlled["account_context"] = it }
        channel.privateFileManifest?.takeIf(String::isNotBlank)?.let { controlled["private_file_manifest"] = it }
        val channelUpdate = controlled.takeIf(Map<String, String>::isNotEmpty)?.let {
            val encrypted = contacts.encrypt(objectMapper.writeValueAsString(it))
            channel.copy(
                diagnosticCiphertext = encrypted.ciphertext,
                diagnosticNonce = encrypted.nonce,
                diagnosticTag = encrypted.tag,
                diagnosticKeyId = contacts.keyId,
                diagnosticAlgorithm = "AES-256-GCM",
                diagnosticTypes = it.keys.joinToString(","),
                appInternalLogs = null,
                accountContext = null,
                privateFileManifest = null
            )
        }
        val payload = ingest?.let(::decryptPayload)
        val identityUpdates = buildList {
            payload?.path("business_context")?.let { business ->
                addIdentityIfNeeded(channel.sourceId, "DEVICE_SN", "DEVICE", business.path("device_sn"), existingIdentities)
                    ?.let(::add)
                addIdentityIfNeeded(channel.sourceId, "USER_ID", "PERSON", business.path("user_id"), existingIdentities)
                    ?.let(::add)
            }
        }
        return PreparedBackfill(channelUpdate, identityUpdates)
    }

/** 解密并解析 SDK 载荷；密钥和明文字节在最短作用域使用且不得记录。 */
    private fun decryptPayload(ingest: SdkProcessingEnvelopePo): JsonNode? {
        val wrapped = ingest.encryptedDataKey ?: return null
        val cipher = ingest.payloadCiphertext ?: return null
        val nonce = ingest.payloadNonce ?: return null
        val tag = ingest.payloadTag ?: return null
        // 解封装失败无法可靠区分“历史密文损坏”和“配置了错误私钥”，因此一律整批失败关闭。
        val key = transportKeys.unwrapDataKey(wrapped, ingest.keyId)
        try {
            if (key.size != 32 || nonce.size != 12 || tag.size != 16) {
                throw LegacySdkPayloadUnreadableException(
                    IllegalArgumentException("invalid historical AES-GCM envelope")
                )
            }
            val plaintext = try {
                envelopeCrypto.decrypt(cipher, tag, nonce, key)
            } catch (error: AEADBadTagException) {
                throw LegacySdkPayloadUnreadableException(error)
            }
            return try {
                objectMapper.readTree(plaintext)
            } catch (error: JsonProcessingException) {
                throw LegacySdkPayloadUnreadableException(error)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            key.fill(0)
        }
    }

/** 仅在当前批次不存在相同类型与作用域时加入身份，避免重复写入。 */
    private fun addIdentityIfNeeded(
        sourceId: Long,
        type: String,
        scope: String,
        node: JsonNode,
        existing: Map<Triple<Long, String, String>, SdkIdentityPo>
    ): SdkIdentityPo? {
        val value = node.asText(null)?.takeIf(String::isNotBlank) ?: return null
        if (existing[Triple(sourceId, type, scope)]?.contactCiphertext != null) {
            return null
        }
        val normalized = fingerprints.normalize(value, type)
        val encrypted = contacts.encrypt(normalized)
        return SdkIdentityPo(
            sourceId = sourceId,
            identityType = type,
            identityScope = scope,
            fingerprint = fingerprints.fingerprint(normalized, type),
            fingerprintKeyId = fingerprints.keyId,
            contactCiphertext = encrypted.ciphertext,
            contactNonce = encrypted.nonce,
            contactTag = encrypted.tag,
            contactKeyId = contacts.keyId,
            contactAlgorithm = "AES-256-GCM",
            contactAuthorizationBasis = "CHANNEL_GOVERNANCE",
            consentedAt = LocalDateTime.now(),
            expiresAt = LocalDateTime.of(2099, 12, 31, 23, 59, 59)
        )
    }

/** PreparedBackfill 链路组件；职责边界、外部 I/O 和失败策略由公开方法契约约束。 */
    private data class PreparedBackfill(
        val channelUpdate: SdkChannelPo?,
        val identityUpdates: List<SdkIdentityPo>
    )

    companion object { const val MAX_BATCH_SIZE = 200 }
}

/** 仅表示历史 SDK payload 已无法解密或解析；其他安全加工失败必须让整批回滚。 */
private class LegacySdkPayloadUnreadableException(cause: Exception) : RuntimeException(cause)

@Component
@ConditionalOnProperty(
    name = ["app.sdk.controlled-diagnostic-backfill-enabled"],
    havingValue = "true"
)
/** SDK 受控诊断回填入口；按有界页执行事务外解密和条件物化。 */
class SdkControlledDiagnosticBackfillRunner(
    private val channels: SdkChannelMutationRepository,
    private val batchService: SdkControlledDiagnosticBackfillBatchService
) : ApplicationRunner {
/** 响应应用启动参数触发一次受控回填；未显式启用时不执行数据修改。 */
    override fun run(args: ApplicationArguments) {
        backfill()
    }

/** 按游标和固定页大小完成 SDK 诊断回填，空页即终止并返回处理计数。 */
    fun backfill(): Int {
        var cursor = 0L
        var completed = 0
        while (true) {
            val page = channels.findPageAfterId(cursor, SdkControlledDiagnosticBackfillBatchService.MAX_BATCH_SIZE)
            if (page.isEmpty()) {
                return completed
            }
            completed += batchService.process(page)
            cursor = page.last().sourceId
        }
    }
}
