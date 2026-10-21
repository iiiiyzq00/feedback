package com.feedback.service.masking

import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.feedback.listprojection.FeedbackListProjectionDirtyReason
import com.feedback.repository.sdk.*
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyCommand
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.feedback.security.sdk.TransportKeyService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.transaction.annotation.Transactional
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import java.time.LocalDateTime
import java.util.*
import javax.crypto.AEADBadTagException

/** SDK 安全物化服务；事务外解密与脱敏，短事务写入来源、正文、身份和渠道状态。 */
@Service
class SdkSecureProcessingService(
    private val ingestRepository: SdkProcessingEnvelopeRepository,
    private val sourceRepository: SdkFeedbackSourceMutationRepository,
    private val contentRepository: SdkFeedbackContentMutationRepository,
    private val channelSdkRepository: SdkChannelMutationRepository,
    private val identityRepository: SdkIdentityMutationRepository,
    private val attachmentRepository: SdkAttachmentMutationRepository,
    private val authorizedContacts: SdkAuthorizedContactMutationRepository,
    private val dirtyMarker: FeedbackListProjectionDirtyMarker,
    private val transportKeys: TransportKeyService,
    private val envelopeCrypto: EnvelopeCrypto,
    private val maskingPipeline: MaskingPipeline,
    private val fingerprints: IdentityFingerprintService,
    private val contactStorage: ContactStorageCrypto,
    private val objectMapper: ObjectMapper,
    private val failureAlerts: IngestFailureAlertPublisher? = null,
    @Value("\${app.sdk.review-retention-days:90}") private val reviewRetentionDays: Long = 90
) {
    private val log = LoggerFactory.getLogger(javaClass)

/** 处理单个 SDK 信封；校验当前状态后进入独立事务推进正式事实和处理状态。 */
    @Transactional
    fun processOne(ingestId: Long) {
        val ingest = ingestRepository.get(ingestId)
        if (ingest.ingestStatus !in setOf("RECEIVED", "RETRY_PENDING", "REVIEW_NEEDED", "FAILED")) {
            return
        }
        ingest.ingestStatus = "PROCESSING"
        ingest.lastErrorCode = null
        ingest.lastErrorDetail = null
        ingestRepository.saveState(ingest)

        var dataKey: ByteArray? = null
        var bodyPlaintext: ByteArray? = null
        try {
            dataKey = transportKeys.unwrapDataKey(ingest.encryptedDataKey ?: maskingFailed("SDK_ENCRYPTED_DATA_KEY_MISSING"), ingest.keyId)
        } catch (error: Exception) {
            recordProcessingFailure(ingest, error, bodyFailed = true, contactFailed = ingest.contactCiphertext != null)
            return
        }

        // 联系方式是一份独立的、已认证的载荷。它成功与否都不改变
        // 正文的判定；正文失败也不能回滚已经落库的、用户已同意的联系方式。
        val contactResult = runCatching { processContactPayload(ingest, dataKey) }
            .onFailure { ingest.contactProcessingStatus = "FAILED" }

        if (ingest.bodyProcessingStatus == "PASS") {
            var identityChanged = false
            if (contactResult.isSuccess) {
                identityChanged = ingest.feedbackSourceId?.let { sourceId ->
                    materializeContactStaging(ingest.id, sourceId, loadIdentityFacts(sourceId))
                } == true
                ingest.ingestStatus = "COMPLETED"
                ingest.dispositionStatus = "RECOVERED"
                ingest.nextRetryAt = null
            } else {
                scheduleContactRetry(ingest, contactResult.exceptionOrNull()!!)
            }
            ingestRepository.saveState(ingest)
            if (identityChanged) {
                markDirty(ingest.feedbackSourceId ?: maskingFailed("SDK_FEEDBACK_SOURCE_ID_MISSING"), FeedbackListProjectionDirtyReason.IDENTITY)
            }
            dataKey.fill(0)
            return
        }

        var formalFactsStarted = false
        try {
            ingest.bodyProcessingStatus = "PROCESSING"
            bodyPlaintext = envelopeCrypto.decrypt(
                ingest.payloadCiphertext ?: maskingFailed("SDK_PAYLOAD_CIPHERTEXT_MISSING"),
                ingest.payloadTag ?: maskingFailed("SDK_PAYLOAD_TAG_MISSING"),
                ingest.payloadNonce ?: maskingFailed("SDK_PAYLOAD_NONCE_MISSING"), dataKey
            )
            val payload = objectMapper.readTree(bodyPlaintext)
            val description = payload.path("description").asText("").take(20_000)
            val business = payload.path("business_context")
            val masked = maskingPipeline.mask(description, "sdk_ingest_${ingest.id}")
            val diagnosticFields = listOf(
                "app_internal_logs", "crash_stack", "account_context", "course_id", "question_id", "order_id",
                "page_route", "business_id", "api_request_summary", "state_machine", "custom_events", "private_file_manifest"
            ).mapNotNull { field ->
                val node = payload.path(field).takeUnless { it.isMissingNode || it.isNull }
                    ?: business.path(field).takeUnless { it.isMissingNode || it.isNull }
                    ?: return@mapNotNull null
                val rawValue = if (node.isTextual) node.asText() else objectMapper.writeValueAsString(node)
                field to maskingPipeline.mask(rawValue.take(20_000), "sdk_${field}_${ingest.id}")
            }.toMap()

            if (masked.maskingStatus != "PASS" || diagnosticFields.values.any { it.maskingStatus != "PASS" }) {
                ingest.processedAt = LocalDateTime.now()
                ingest.ingestStatus = "REVIEW_NEEDED"
                ingest.dispositionStatus = "OPEN"
                ingest.bodyProcessingStatus = "REVIEW_NEEDED"
                ingest.lastErrorCode = "MASKING_RESIDUAL_RISK"
                val reviewRetention = LocalDateTime.now().plusDays(reviewRetentionDays.coerceIn(30, 365))
                if (ingest.retentionUntil == null || ingest.retentionUntil!!.isBefore(reviewRetention)) {
                    ingest.retentionUntil = reviewRetention
                }
                ingestRepository.saveState(ingest)
                return
            }

            formalFactsStarted = true
            val source = existingOrCreateSource(ingest, business)
            val controlledDiagnostics = linkedMapOf<String,String>().apply {
                listOf("app_internal_logs","account_context","private_file_manifest").forEach { key ->
                    diagnosticFields[key]?.maskedText?.takeIf(String::isNotBlank)?.let { put(key,it) }
                }
            }
            val encryptedDiagnostics = controlledDiagnostics.takeIf { it.isNotEmpty() }
                ?.let { contactStorage.encrypt(objectMapper.writeValueAsString(it)) }
            contentRepository.upsert(SdkFeedbackContentPo(
                sourceId = source.id,
                description = masked.maskedText,
                functionModule = ingest.functionModule,
                problemType = ingest.issueType,
                feedbackTags = payload.path("tags").takeIf { it.isArray }?.let(objectMapper::writeValueAsString),
                reproducibility = normalizeReproducibility(business.path("reproducibility").asText(null)),
                mappingRuleVersion = ingest.schemaVersion,
                normalizationVersion = "sdk-normalize-v1",
                maskingStatus = masked.maskingStatus,
                dataQualityScore = java.math.BigDecimal("1.000000"),
                qualityRuleVersion = "quality-score-v1"
            ))
            channelSdkRepository.upsert(SdkChannelPo(
                sourceId = source.id,
                clientFeedbackId = ingest.clientFeedbackId,
                appId = ingest.appId,
                packageName = ingest.packageName,
                osVersion = ingest.osVersion,
                networkType = normalizeNetwork(ingest.networkType),
                deviceModel = business.path("device_model").asText(null),
                screenOrientation = business.path("screen_orientation").asText(null)?.uppercase()?.take(10),
                appSessionId = business.path("app_session_id").asText(null)?.take(50),
                triggerType = normalizeTrigger(ingest.triggerType),
                inputMethods = payload.path("input_methods").takeIf { it.isArray }
                    ?.map { it.asText().uppercase() }
                    ?.filter { it in setOf("TEXT", "VOICE") }
                    ?.distinct()?.joinToString(",")?.take(50) ?: "TEXT",
                batteryLevel = business.path("battery_level").asText(null)?.toBigDecimalOrNull(),
                storageAvailableMb = business.path("storage_available_mb").asText(null)?.toIntOrNull(),
                appInternalLogs = null,
                crashStack = diagnosticFields["crash_stack"]?.maskedText,
                accountContext = null,
                courseId = diagnosticFields["course_id"]?.maskedText?.take(100),
                questionId = diagnosticFields["question_id"]?.maskedText?.take(100),
                orderId = diagnosticFields["order_id"]?.maskedText?.take(100),
                pageRoute = diagnosticFields["page_route"]?.maskedText?.take(500),
                businessId = diagnosticFields["business_id"]?.maskedText?.take(100),
                apiRequestSummary = diagnosticFields["api_request_summary"]?.maskedText,
                stateMachine = diagnosticFields["state_machine"]?.maskedText?.let(objectMapper::writeValueAsString),
                customEvents = diagnosticFields["custom_events"]?.maskedText?.let(objectMapper::writeValueAsString),
                privateFileManifest = null,
                diagnosticCiphertext=encryptedDiagnostics?.ciphertext,diagnosticNonce=encryptedDiagnostics?.nonce,
                diagnosticTag=encryptedDiagnostics?.tag,diagnosticKeyId=encryptedDiagnostics?.let{contactStorage.keyId},
                diagnosticAlgorithm=encryptedDiagnostics?.let{"AES-256-GCM"},
                diagnosticTypes=controlledDiagnostics.keys.joinToString(",").takeIf(String::isNotBlank)
            ))
            val identityFacts = loadIdentityFacts(source.id)
            persistBodyIdentities(source.id, masked, business, identityFacts)
            materializeContactStaging(ingest.id, source.id, identityFacts)

            ingest.feedbackSourceId = source.id
            attachmentRepository.linkToSource(ingest.id, source.id)
            ingest.processedAt = LocalDateTime.now()
            ingest.bodyProcessingStatus = "PASS"
            if (contactResult.isSuccess) {
                ingest.ingestStatus = "COMPLETED"
                ingest.dispositionStatus = "RECOVERED"
                ingest.nextRetryAt = null
            } else scheduleContactRetry(ingest, contactResult.exceptionOrNull()!!)
            ingestRepository.saveState(ingest)
            markDirty(source.id, FeedbackListProjectionDirtyReason.CHANNEL)
        } catch (error: Exception) {
            if (formalFactsStarted) {
                throw error
            }
            recordProcessingFailure(ingest, error, bodyFailed = true, contactFailed = false)
        } finally {
            dataKey?.fill(0)
            bodyPlaintext?.fill(0)
        }
    }

/** 处理授权联系方式密文；未授权、缺失或解密失败均不得生成正式联系方式。 */
    private fun processContactPayload(ingest: SdkProcessingEnvelopePo, dataKey: ByteArray) {
        if (ingest.contactCiphertext == null) {
            if (ingest.contactProcessingStatus !in setOf("STORED", "DESTROYED"))
                ingest.contactProcessingStatus = "NOT_PROVIDED"
            return
        }
        ingest.contactProcessingStatus = "PROCESSING"
        val contactKey = envelopeCrypto.derivePurposeKey(dataKey, "contact")
        val plaintext = envelopeCrypto.decrypt(
            ingest.contactCiphertext ?: maskingFailed("SDK_CONTACT_CIPHERTEXT_MISSING"),
            ingest.contactTag ?: maskingFailed("SDK_CONTACT_TAG_MISSING"),
            ingest.contactNonce ?: maskingFailed("SDK_CONTACT_NONCE_MISSING"), contactKey
        )
        try {
            parseContacts(plaintext.toString(Charsets.UTF_8)).forEach { (type, raw) ->
                val normalized = fingerprints.normalize(raw, type)
                val fingerprint = fingerprints.fingerprint(normalized, type)
                val encrypted = if (ingest.contactAllowed) contactStorage.encrypt(normalized) else null
                authorizedContacts.upsert(SdkAuthorizedContactPo(
                    ingestId = ingest.id,
                    sourceId = ingest.feedbackSourceId,
                    contactType = type,
                    fingerprint = fingerprint,
                    fingerprintKeyId = fingerprints.keyId,
                    authorized = ingest.contactAllowed,
                    contactCiphertext = encrypted?.ciphertext,
                    contactNonce = encrypted?.nonce,
                    contactTag = encrypted?.tag,
                    contactKeyId = encrypted?.let { contactStorage.keyId },
                    contactAlgorithm = encrypted?.let { "AES-256-GCM" },
                    authorizationBasis = encrypted?.let { "SDK_CONSENT" },
                    authorizedAt = encrypted?.let { LocalDateTime.now() },
                    expiresAt = LocalDateTime.now().plusDays(90)
                ))
            }
            ingest.contactProcessingStatus = if (ingest.contactAllowed) "STORED" else "DESTROYED"
            // 完成指纹计算与落库之后，传输用的联系方式密文就不再需要了。
            ingest.contactNonce = null; ingest.contactCiphertext = null; ingest.contactTag = null
        } finally { plaintext.fill(0); contactKey.fill(0) }
        ingestRepository.saveState(ingest)
    }

/** 把已规范化联系方式写入受控暂存事实，并遵守授权与保留期。 */
    private fun materializeContactStaging(
        ingestId: Long,
        sourceId: Long,
        existingIdentities: MutableMap<Triple<Long, String, String>, SdkIdentityPo>
    ): Boolean {
        val rows = authorizedContacts.findRetainedByIngestId(ingestId)
        val newIdentities = rows.mapNotNull { row ->
            val key = Triple(sourceId, row.contactType, "PERSON")
            if (existingIdentities[key] == null) {
                SdkIdentityPo(
                    sourceId = sourceId,
                    identityType = row.contactType,
                    identityScope = "PERSON",
                    fingerprint = row.fingerprint,
                    fingerprintKeyId = row.fingerprintKeyId,
                    contactCiphertext = row.contactCiphertext,
                    contactNonce = row.contactNonce,
                    contactTag = row.contactTag,
                    contactKeyId = row.contactKeyId,
                    contactAlgorithm = row.contactAlgorithm,
                    contactAuthorizationBasis = row.authorizationBasis,
                    consentedAt = row.authorizedAt,
                    expiresAt = row.expiresAt
                ).also { existingIdentities[key] = it }
            } else null
        }
        identityRepository.upsertBatch(newIdentities)
        return authorizedContacts.linkToSource(ingestId, sourceId) > 0 || newIdentities.isNotEmpty()
    }

/** 解析并规范化联系方式列表；过滤空值、去重并限制可接受类型。 */
    private fun parseContacts(value: String): List<Pair<String, String>> = value.split(';', ',', '\n')
        .map(String::trim).filter(String::isNotBlank).map { (if ('@' in it) "EMAIL" else "PHONE") to it }

/** 记录 SDK 处理失败的稳定状态和安全错误码，不保存异常原文或密文。 */
    private fun recordProcessingFailure(
        ingest: SdkProcessingEnvelopePo,
        error: Exception,
        bodyFailed: Boolean,
        contactFailed: Boolean
    ) {
        val root = generateSequence(error as Throwable?) { it.cause }.last()
        ingest.retryCount += 1
        ingest.processedAt = LocalDateTime.now()
        ingest.lastErrorCode = root.javaClass.simpleName.take(50)
        ingest.lastErrorDetail = "secure-processing:${root.javaClass.simpleName}".take(500)
        val terminal = root is AEADBadTagException || ingest.retryCount >= 3 ||
            java.time.Duration.between(ingest.receivedAt, LocalDateTime.now()).toHours() >= 24
        ingest.ingestStatus = if (terminal) "FAILED" else "RETRY_PENDING"
        ingest.dispositionStatus = "OPEN"
        if (bodyFailed) ingest.bodyProcessingStatus = "FAILED"
        if (contactFailed) ingest.contactProcessingStatus = "FAILED"
        ingest.nextRetryAt = if (terminal) null else LocalDateTime.now().plusSeconds(1L shl ingest.retryCount.coerceAtMost(10))
        ingestRepository.saveState(ingest)
        if (terminal) {
            publishFailureAlertSafely(ingest)
        }
    }

/** 按有限次数和退避上限安排联系方式重试，永久失败转入人工审核。 */
    private fun scheduleContactRetry(ingest: SdkProcessingEnvelopePo, error: Throwable) {
        ingest.retryCount += 1
        ingest.lastErrorCode = error.javaClass.simpleName.take(50)
        ingest.lastErrorDetail = "contact-processing:${error.javaClass.simpleName}".take(500)
        val terminal = ingest.retryCount >= 3 || java.time.Duration.between(ingest.receivedAt, LocalDateTime.now()).toHours() >= 24
        ingest.ingestStatus = if (terminal) "FAILED" else "RETRY_PENDING"
        ingest.dispositionStatus = "OPEN"
        ingest.nextRetryAt = if (terminal) null else LocalDateTime.now().plusSeconds(1L shl ingest.retryCount.coerceAtMost(10))
        if (terminal) publishFailureAlertSafely(ingest)
    }

    /** 安全发布 SDK 处理失败告警；告警异常不得回滚已经持久化的失败状态。 */
    private fun publishFailureAlertSafely(ingest: SdkProcessingEnvelopePo) {
        runCatching { failureAlerts?.publish(ingest, ingest.lastErrorCode ?: "UNKNOWN") }
            .onFailure { error ->
                log.error(
                    "Failed to publish ingest failure alert: ingestId={}, errorType={}",
                    ingest.id,
                    error.javaClass.simpleName
                )
            }
    }

/** 按来源业务键定位或创建来源事实；唯一键竞争后重新读取权威记录。 */
    private fun existingOrCreateSource(
        ingest: SdkProcessingEnvelopePo,
        business: com.fasterxml.jackson.databind.JsonNode
    ): SdkFeedbackSourcePo {
        val existing = ingest.feedbackSourceId?.let(sourceRepository::find)
            ?: sourceRepository.findByRecordId(ingest.clientFeedbackId)
        val eventTime = ingest.sourceEventTime ?: ingest.receivedAt
        if (existing != null) {
            if (eventTime.isAfter(existing.sourceUpdateTime)) {
                existing.sourceEventTime = eventTime
                existing.faultOccurredAt = eventTime
                existing.faultTimeRaw = eventTime.toString()
                existing.faultTimePrecision = "SECOND"
                existing.faultTimeSource = "SDK_CLIENT"
                existing.faultTimeResolution = "RESOLVED"
                existing.sourceUpdateTime = eventTime
                existing.updatedAt = LocalDateTime.now()
                sourceRepository.updateNewerEvent(existing)
            }
            return existing
        }
        val row = SdkFeedbackSourcePo(
                publicId = uuidBytes(UUID.randomUUID()),
                sourceRecordId = ingest.clientFeedbackId,
                appVersion = ingest.appVersion,
                packageName = ingest.packageName,
                sourceEventTime = eventTime,
                faultOccurredAt = eventTime,
                faultTimeRaw = eventTime.toString(),
                faultTimePrecision = "SECOND",
                faultTimeSource = "SDK_CLIENT",
                faultTimeResolution = "RESOLVED",
                sourceUpdateTime = eventTime,
                provinceCode = business.path("province_code").asText(null),
                cityCode = business.path("city_code").asText(null),
                districtCode = business.path("district_code").asText(null)
            )
        return try {
            sourceRepository.insert(row)
        } catch (error: DataIntegrityViolationException) {
            sourceRepository.findByRecordId(ingest.clientFeedbackId) ?: throw error
        }
    }

/** 批量写入正文解析出的身份；先加载既有事实并按复合键去重。 */
    private fun persistBodyIdentities(
        sourceId: Long,
        masked: MaskingResult,
        business: com.fasterxml.jackson.databind.JsonNode,
        existingIdentities: MutableMap<Triple<Long, String, String>, SdkIdentityPo>
    ) {
        val identities = masked.findings.filter { it.type in setOf("PHONE", "EMAIL") }
            .map { it.type to it.originalValue } + listOfNotNull(
                business.path("device_sn").asText(null)?.takeIf(String::isNotBlank)?.let { "DEVICE_SN" to it },
                business.path("user_id").asText(null)?.takeIf(String::isNotBlank)?.let { "USER_ID" to it }
            )
        val updates = identities.distinctBy { it.first }.mapNotNull { (type, value) ->
            val scope = if (type == "DEVICE_SN") "DEVICE" else "PERSON"
            val key = Triple(sourceId, type, scope)
            val existing = existingIdentities[key]
            if (existing == null || existing.contactCiphertext == null) {
                val normalized=fingerprints.normalize(value,type);val encrypted=contactStorage.encrypt(normalized)
                (existing ?: SdkIdentityPo(
                sourceId = sourceId,
                identityType = type,
                identityScope = scope,
                fingerprint = fingerprints.fingerprint(normalized, type),
                fingerprintKeyId = fingerprints.keyId
                )).copy(contactCiphertext=encrypted.ciphertext,contactNonce=encrypted.nonce,contactTag=encrypted.tag,
                    contactKeyId=contactStorage.keyId,contactAlgorithm="AES-256-GCM",contactAuthorizationBasis="CHANNEL_GOVERNANCE",
                    consentedAt=LocalDateTime.now(),expiresAt=LocalDateTime.of(2099,12,31,23,59,59))
                    .also { existingIdentities[key] = it }
            } else null
        }
        identityRepository.upsertBatch(updates)
    }

/** 一次批量加载来源身份并按来源、类型、作用域索引，避免 N+1 查询。 */
    private fun loadIdentityFacts(sourceId: Long): MutableMap<Triple<Long, String, String>, SdkIdentityPo> =
        identityRepository.findBySourceIds(listOf(sourceId)).associateByTo(linkedMapOf()) {
            Triple(it.sourceId, it.identityType, it.identityScope)
        }

/** 为实际变化的来源写入列表投影脏标记；失败必须阻止当前正式状态提交。 */
    private fun markDirty(sourceId: Long, reason: FeedbackListProjectionDirtyReason) {
        dirtyMarker.mark(FeedbackListProjectionDirtyCommand(listOf(sourceId), reason))
    }

/** 把网络类型归一到稳定允许集合，未知值使用 UNKNOWN。 */
    private fun normalizeNetwork(value: String?): String = value?.uppercase()?.takeIf { it in setOf("WIFI", "4G", "5G", "ETHERNET") } ?: "UNKNOWN"
/** 把可复现性文本归一到稳定 code，无法识别时返回空值等待后续判断。 */
    private fun normalizeReproducibility(value: String?): String? = when (value?.trim()?.uppercase()) {
        null, "" -> null
        "ALWAYS", "必现", "每次", "总是" -> "ALWAYS"
        "FREQUENT", "频繁", "经常" -> "FREQUENT"
        "OCCASIONAL", "偶发", "有时" -> "OCCASIONAL"
        "ONCE", "一次", "仅一次" -> "ONCE"
        "UNKNOWN", "未知", "无法判断" -> "UNKNOWN"
        else -> "UNKNOWN"
    }
/** 把 SDK 触发来源归一到稳定允许集合，未知输入使用 OTHER。 */
    private fun normalizeTrigger(value: String?): String = when (value?.uppercase()) {
        "FLOAT_WINDOW", "FLOAT_BUTTON" -> "FLOAT_BUTTON"
        "NOTIFICATION" -> "NOTIFICATION"
        "LAUNCHER", "USER_SELECTION" -> "LAUNCHER"
        "DEEPLINK" -> "DEEPLINK"
        "QUICK_SETTINGS" -> "QUICK_SETTINGS"
        "PAGE_ENTRY" -> "PAGE_ENTRY"
        else -> "API_CALL"
    }
/** 把 UUID 转换为数据库兼容的十六字节回执表示。 */
    private fun uuidBytes(uuid: UUID): ByteArray = java.nio.ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
}
