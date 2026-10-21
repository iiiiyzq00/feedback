package com.feedback.service.imports

import com.feedback.model.po.ai.*
import com.feedback.model.ai.*
import com.feedback.model.po.audit.*
import com.feedback.model.audit.*
import com.feedback.model.channel.*
import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import com.feedback.model.po.config.*
import com.feedback.model.config.*
import com.feedback.model.po.feedback.*
import com.feedback.model.feedback.*
import com.feedback.model.po.imports.*
import com.feedback.model.imports.*
import com.feedback.model.po.jira.*
import com.feedback.model.jira.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import com.feedback.model.po.sdk.*
import com.feedback.model.sdk.*
import com.feedback.repository.*
import com.feedback.repository.sdk.ChannelSdkRepository
import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.service.masking.MaskingPipeline
import com.feedback.service.masking.ContactStorageCrypto
import com.feedback.service.normalization.EnumMappingRegistry
import com.feedback.service.normalization.EnumMappingSnapshot
import com.feedback.service.normalization.FieldConversionHistoryService
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.transaction.annotation.Transactional
import com.feedback.repository.imports.ImportRollbackRepository
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.data.domain.Sort
import org.springframework.data.domain.PageRequest
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.CacheEvict
import org.springframework.beans.factory.annotation.Value
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*
import com.feedback.config.RuntimeConfigService
import com.feedback.service.alert.IdempotencyConflictService
import com.feedback.service.jira.JiraDescriptionDimensions
import com.feedback.service.jira.JiraAssigneeOwnerInitializer
import com.feedback.service.jira.JiraAttachmentPullQueue

private fun interface ImportChannelAdapter { fun persist() }

private data class ImportRowProcessingResult(
    val status: String,
    val jiraOwnerSourceId: Long?
)

@Service
class ImportBatchService(
    private val parser: ImportFileParser,
    private val rawCrypto: ImportRawCrypto,
    private val masking: MaskingPipeline,
    private val fingerprints: IdentityFingerprintService,
    private val batches: ImportBatchRepository,
    private val rows: ImportRowRepository,
    private val sources: FeedbackSourceRepository,
    private val contents: FeedbackContentRepository,
    private val identities: FeedbackIdentityRepository,
    private val sdk: ChannelSdkRepository,
    private val jira: ChannelJiraRepository,
    private val quality: ChannelQualityRepository,
    private val redblack: ChannelRedblackRepository,
    private val enumMappings: EnumMappingRegistry,
    private val objectMapper: ObjectMapper,
    private val templateContracts: ImportTemplateService,
    private val runtimeConfig: RuntimeConfigService? = null,
    private val contactStorage: ContactStorageCrypto? = null,
    private val attachmentTasks: ImportAttachmentTaskService? = null,
    private val conversionHistory: FieldConversionHistoryService? = null,
    private val qualityMetrics: ImportQualityService? = null,
    private val conflictEvents: IdempotencyConflictService? = null,
    private val rollbackPersistence: ImportRollbackRepository,
    private val jiraOwnerInitializer: JiraAssigneeOwnerInitializer? = null,
    private val jiraAttachmentPullQueue: JiraAttachmentPullQueue? = null,
    @param:Value("\${app.jira400.time-zone:Asia/Shanghai}") private val jiraTimeZone: String = "Asia/Shanghai"
) {
    @Transactional
    fun processManualPrivacyReview(
        rowId: Long, decision: String, reason: String, reviewer: Long,
        revisedTitle: String?, revisedDescription: String?
    ): Map<String, Any?> {
        ensureImport(decision in setOf("FALSE_POSITIVE_RELEASE", "REMASK_AND_RELEASE", "KEEP_QUARANTINED")) { "MASKING_REVIEW_DECISION_INVALID" }
        ensureImport(reason.trim().length in 5..500) { "MASKING_REVIEW_REASON_INVALID" }
        val row = rows.findById(rowId).orElseThrow { com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.NOT_FOUND,"MASKING_REVIEW_TASK_NOT_FOUND") }
        ensureImport(row.recordType == "ROW" && row.rowStatus == "REVIEW_NEEDED") { "MASKING_REVIEW_TASK_STATE_CHANGED" }
        val batch = batches.findById(row.batchId).orElseThrow { com.feedback.common.exception.ClientException(com.feedback.common.ErrorCode.NOT_FOUND,"MASKING_REVIEW_BATCH_NOT_FOUND") }
        val rawBytes = rawCrypto.decrypt(importRequired(row.rawRowContentEnc), importRequired(row.rawTag), importRequired(row.rawNonce))
        val raw: MutableMap<String, String> = try {
            objectMapper.readValue(rawBytes, object : TypeReference<MutableMap<String, String>>() {})
        } finally { rawBytes.fill(0) }
        val mapping: Map<String, String> = objectMapper.readValue(importRequired(batch.mappingConfigJson), object : TypeReference<Map<String, String>>() {})
        val titleKey = mapping["title"]
        val descriptionKey = importRequired(mapping["description"]) { "MASKING_REVIEW_DESCRIPTION_MAPPING_MISSING" }
        if (decision == "REMASK_AND_RELEASE") {
            val revised=importRequired(revisedDescription?.takeIf(String::isNotBlank)) { "MASKING_REVIEW_REVISED_DESCRIPTION_REQUIRED" }
            ensureImport(revised.length <= 20000 && (revisedTitle?.length ?: 0) <= 1000) { "MASKING_REVIEW_REVISED_CONTENT_TOO_LONG" }
            raw[descriptionKey] = revised
            if (titleKey != null) raw[titleKey] = revisedTitle.orEmpty()
            val replacement = objectMapper.writeValueAsBytes(raw)
            val encrypted = try { rawCrypto.encrypt(replacement) } finally { replacement.fill(0) }
            row.rawRowContentEnc = encrypted.ciphertext; row.rawNonce = encrypted.nonce; row.rawTag = encrypted.tag
        }
        val title = titleKey?.let(raw::get).orEmpty()
        val description = raw[descriptionKey].orEmpty()
        val titleCheck = masking.mask(title, "manual_review_title_$rowId")
        val descriptionCheck = masking.mask(description, "manual_review_description_$rowId")
        val findings = (titleCheck.findingTypes + descriptionCheck.findingTypes).distinct()
        val recheckPass = titleCheck.maskingStatus == "PASS" && descriptionCheck.maskingStatus == "PASS" && findings.isEmpty()
        if (decision == "REMASK_AND_RELEASE") ensureImport(recheckPass) { "MASKING_REVIEW_RECHECK_NOT_PASS" }
        row.privacyReviewDecision = decision
        row.privacyReviewReason = reason.trim()
        row.privacyReviewedBy = reviewer
        row.privacyReviewedAt = LocalDateTime.now()
        row.privacyReviewRuleVersion = masking.activeVersion()
        row.privacyReviewInputSha256 = sha256(objectMapper.writeValueAsBytes(listOf(title, description)))
        row.privacyReviewFindings = objectMapper.writeValueAsString(findings)
        if (decision == "KEEP_QUARANTINED") {
            row.qualityErrorSummary = "人工判定保持隔离"
            rows.save(row)
            return mapOf("taskId" to "${if (batch.ingestMethod == "API") "API" else "FILE"}:$rowId", "decision" to decision,
                "released" to false, "ruleVersion" to masking.activeVersion(),
                "recheckStatus" to if (recheckPass) "PASS" else "REVIEW_NEEDED", "findingTypes" to findings)
        }
        row.rowStatus = "PENDING"
        rows.save(row)
        val result = processRow(batch, row, mapping, enumMappings.snapshot(batch.sourceChannel), decision == "FALSE_POSITIVE_RELEASE")
        initializeJiraOwners(batch.sourceChannel, listOfNotNull(result.jiraOwnerSourceId))
        ensureImport(result.status in setOf("IMPORTED", "UPDATED", "SKIPPED_DUPLICATE")) {
            "MASKING_REVIEW_RELEASE_FAILED:${result.status}"
        }
        return mapOf("taskId" to "${if (batch.ingestMethod == "API") "API" else "FILE"}:$rowId", "decision" to decision,
            "released" to true, "ruleVersion" to masking.activeVersion(),
            "recheckStatus" to if (recheckPass) "PASS" else "HUMAN_OVERRIDE", "findingTypes" to findings,
            "feedbackSourceId" to row.feedbackSourceId)
    }
    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun upload(file: MultipartFile, channelInput: String, sheetName: String?): ImportUploadResult = upload(file,channelInput,sheetName,null,null)

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun stageJiraApiBatch(
        mappedRows: List<Map<String, String>>,
        mappingTemplateId: Long,
        mappingVersion: String,
        mappingSnapshot: Map<String, String>,
        triggerType: String,
        windowFrom: LocalDateTime,
        windowTo: LocalDateTime,
        sourceInstanceHash: ByteArray,
        actor: Long? = null
    ): ImportBatchView {
        ensureImport(triggerType in setOf("MANUAL", "SCHEDULED", "BACKFILL", "FORMAL_REVIEW")) { "JIRA_TRIGGER_TYPE_INVALID" }
        ensureImport(!windowFrom.isAfter(windowTo)) { "JIRA_SYNC_WINDOW_INVALID" }
        ensureImport(sourceInstanceHash.size == 32) { "JIRA_INSTANCE_HASH_INVALID" }
        ensureImport(mappedRows.size <= MAX_ROWS) { "JIRA_SYNC_ROW_LIMIT_EXCEEDED" }
        val required = setOf("source_record_id", "description", "source_update_time")
        ensureImport(mappingSnapshot.keys.containsAll(required)) { "JIRA_API_REQUIRED_MAPPING_MISSING" }
        mappedRows.forEach { row -> ensureImport(required.all { target -> !row[mappingSnapshot.getValue(target)].isNullOrBlank() }) { "JIRA_API_REQUIRED_FIELD_MISSING" } }
        val now = LocalDateTime.now()
        val runId = UUID.randomUUID()
        val batchId = "${now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}_JIRA_API_${runId.toString().take(8)}"
        val headers = mappedRows.flatMap { it.keys }.toSortedSet().toList()
        val normalizedHash = hashCanonicalRows(mappedRows)
        val enumSnapshot = enumMappings.snapshot("JIRA")
        val batch = batches.save(ImportBatch(
            publicId = uuidBytes(runId), batchId = batchId, sourceChannel = "JIRA",
            ingestMethod = "API", triggerType = triggerType, sourceInstanceHash = sourceInstanceHash,
            syncWindowFrom = windowFrom, syncWindowTo = windowTo,
            originalFilename = "jira-api-sync", contentSha256 = sha256(uuidBytes(runId) + normalizedHash),
            normalizedContentHash = normalizedHash, fileSizeBytes = null,
            mappingTemplateId = mappingTemplateId, mappingConfigVersion = mappingVersion,
            mappingConfigJson = objectMapper.writeValueAsString(mappingSnapshot),
            enumMappingVersion = enumSnapshot.version, enumMappingJson = objectMapper.writeValueAsString(enumSnapshot),
            nonemptyHeadersJson = objectMapper.writeValueAsString(headers), totalRows = mappedRows.size,
            batchStatus = "PROCESSING", startedAt = now, createdBy = actor
        ))
        rows.save(ImportRow(
            batchId = batch.id, recordType = "SHEET", sheetName = "Jira API",
            headerHash = sha256(headers.joinToString("\u001f").toByteArray()),
            sheetRowCount = mappedRows.size, sheetColCount = headers.size
        ))
        mappedRows.forEachIndexed { index, rowMap ->
            val raw = objectMapper.writeValueAsBytes(rowMap)
            val encrypted = try { rawCrypto.encrypt(raw) } finally { raw.fill(0) }
            rows.save(ImportRow(
                batchId = batch.id, recordType = "ROW", sheetName = "Jira API", rowNumber = index + 1,
                sliceNo = index / ROWS_PER_SLICE, rowFingerprint = hashCanonicalRow(rowMap), rowStatus = "PENDING",
                rawRowContentEnc = encrypted.ciphertext, rawNonce = encrypted.nonce, rawTag = encrypted.tag,
                rawKeyId = rawCrypto.keyId, rawAlgorithm = "AES-256-GCM", rawRetentionUntil = now.plusDays(7)
            ))
            if ((index + 1) % 500 == 0) rows.flush()
        }
        rows.flush()
        return batch.toView()
    }

    @Transactional
    fun markFormalReviewUnavailable(keys: List<String>): Int {
        val normalized = keys.map { it.trim().uppercase() }.filter(String::isNotBlank).distinct()
        if (normalized.isEmpty()) return 0
        return rows.markJiraFormalReviewUnavailable(normalized)
    }

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun upload(file:MultipartFile,channelInput:String,sheetName:String?,dataDateFrom:LocalDate?,dataDateTo:LocalDate?):ImportUploadResult {
        ensureImport(dataDateFrom==null||dataDateTo==null||!dataDateFrom.isAfter(dataDateTo)){"IMPORT_DATA_DATE_RANGE_INVALID"}
        val channel = normalizeChannel(channelInput)
        ensureImport(file.size in 1..MAX_FILE_SIZE) { "文件大小必须在 1B..20MB" }
        val filename = file.originalFilename?.substringAfterLast('/')?.substringAfterLast('\\')
            ?.take(255) ?: "import.xlsx"
        val bytes = file.bytes
        try {
        val parsed = parser.parse(filename, bytes)
        val selected = sheetName?.let { name -> parsed.sheets.find { it.name == name } ?: importAbort("工作表不存在: $name") }
            ?: parsed.sheets.firstOrNull() ?: importAbort("文件没有可导入工作表")
        ensureImport(selected.header.isNotEmpty() && selected.header.size <= MAX_COLUMNS) { "表头为空或超过 $MAX_COLUMNS 列" }
        ensureImport(selected.header.none(String::isBlank)) { "IMPORT_HEADER_BLANK" }
        val importHeaders = disambiguateHeaders(selected.header)
        ensureImport(selected.dataRows.size <= MAX_ROWS) { "数据行超过 $MAX_ROWS" }
        val boundary = templateContracts.boundary(channel, selected.header)
        ensureImport(boundary.valid) { boundary.reason ?: "CHANNEL_HEADER_MISMATCH" }
        val template = templateContracts.active(channel)
        val templateView = templateContracts.view(template)
        val enumSnapshot = enumMappings.snapshot(channel)

        val contentHash = sha256(bytes)
        batches.findFirstBySourceChannelAndContentSha256(channel, contentHash)?.let {
            return resultFor(it, parsed, selected, "EXACT_DUPLICATE")
        }
        val normalizedHash = sha256(selected.rows.joinToString("\n") { row -> row.joinToString("\u001f") { it.trim() } }.toByteArray())
        batches.findFirstBySourceChannelAndNormalizedContentHash(channel, normalizedHash)?.let {
            return resultFor(it, parsed, selected, "BUSINESS_DUPLICATE")
        }

        val now = LocalDateTime.now()
        val nonemptyHeaders = importHeaders.filterIndexed { column, _ ->
            selected.dataRows.any { row -> row.getOrElse(column) { "" }.isNotBlank() }
        }
        val approvedMapping = templateView.mapping
        val requiredTargets = if (channel == "QUALITY") setOf("description") else setOf("source_record_id", "description")
        val headerDrift = importHeaders.toSet() != templateView.headers.toSet()
        val unmappedNonempty = nonemptyHeaders.toSet() - approvedMapping.values.toSet()
        val missingRequired = requiredTargets.filter { approvedMapping[it] !in importHeaders }
        val autoApply = !headerDrift && unmappedNonempty.isEmpty() && missingRequired.isEmpty()
        val previous = batches.findFirstBySourceChannelAndOriginalFilenameOrderByCreatedAtDesc(channel, filename)
        val batch = batches.save(ImportBatch(
            publicId = uuidBytes(UUID.randomUUID()),
            batchId = "${now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}_${channel}_${UUID.randomUUID().toString().take(8)}",
            sourceChannel = channel,
            originalFilename = filename,
            contentSha256 = contentHash,
            normalizedContentHash = normalizedHash,
            fileSizeBytes = bytes.size.toLong(),
            totalRows = selected.dataRows.size,
            mappingTemplateId = template.id,
            mappingConfigVersion = template.version,
            mappingConfigJson = if (autoApply) objectMapper.writeValueAsString(approvedMapping) else null,
            enumMappingVersion = enumSnapshot.version,
            enumMappingJson = objectMapper.writeValueAsString(enumSnapshot),
            nonemptyHeadersJson = objectMapper.writeValueAsString(nonemptyHeaders),
            batchStatus = if (autoApply) "PROCESSING" else "AWAITING_MAPPING",
            startedAt = if (autoApply) now else null,
            dataDateFrom=dataDateFrom,dataDateTo=dataDateTo,
            previousBatchId = previous?.id
        ))
        rows.save(ImportRow(
            batchId = batch.id, recordType = "SHEET", sheetName = selected.name,
            headerHash = sha256(importHeaders.joinToString("\u001f").toByteArray()),
            sheetRowCount = selected.dataRows.size, sheetColCount = selected.header.size
        ))
        selected.dataRows.forEachIndexed { index, cells ->
            val rowMap = importHeaders.mapIndexed { column, header -> header to cells.getOrElse(column) { "" } }.toMap()
            val raw = objectMapper.writeValueAsBytes(rowMap)
            val encrypted = rawCrypto.encrypt(raw)
            raw.fill(0)
            rows.save(ImportRow(
                batchId = batch.id, recordType = "ROW", sheetName = selected.name,
                rowNumber = index + 2, sliceNo = index / ROWS_PER_SLICE,
                rowFingerprint = sha256(cells.joinToString("\u001f") { it.trim() }.toByteArray()),
                rowStatus = "PENDING",
                rawRowContentEnc = encrypted.ciphertext, rawNonce = encrypted.nonce, rawTag = encrypted.tag,
                rawKeyId = rawCrypto.keyId, rawAlgorithm = "AES-256-GCM",
                rawRetentionUntil = now.plusDays(7)
            ))
            if((index+1)%500==0) rows.flush()
        }
        rows.flush()
        return resultFor(batch, parsed, selected, "NEW")
        } finally {
            bytes.fill(0)
        }
    }

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun confirm(batchId: String, mapping: Map<String, String>, actor: Long? = null): ImportBatchView {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        ensureImport(batch.batchStatus in setOf("AWAITING_MAPPING", "PARTIAL_SUCCESS", "FAILED")) { "批次状态不允许确认" }
        validateMapping(batch.sourceChannel, mapping, batchHeaders(batch.id), batch.nonemptyHeadersJson)
        if(batch.batchStatus in setOf("PARTIAL_SUCCESS","FAILED")) rows.resetRetryableRows(batch.id)
        val boundTemplate = templateContracts.byId(importRequired(batch.mappingTemplateId) { "IMPORT_TEMPLATE_NOT_BOUND" })
        ensureImport(boundTemplate.sourceChannel == batch.sourceChannel) { "IMPORT_TEMPLATE_CHANNEL_MISMATCH" }
        val previousMapping = batch.mappingConfigJson?.let {
            objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {})
        }
        val mappingChanged = previousMapping == null || previousMapping != mapping
        val template = if (batch.batchStatus == "AWAITING_MAPPING" || mappingChanged)
            templateContracts.publishNext(batch.sourceChannel, batchHeaders(batch.id).toList(), mapping, actor)
        else boundTemplate
        val enumSnapshot = if (batch.batchStatus == "AWAITING_MAPPING" || mappingChanged)
            enumMappings.snapshot(batch.sourceChannel)
        else enumSnapshot(batch)
        val queued = batches.save(batch.copy(
            batchStatus = "PROCESSING",
            mappingConfigJson = objectMapper.writeValueAsString(mapping),
            mappingConfigVersion = template.version,
            mappingTemplateId = template.id,
            enumMappingVersion = enumSnapshot.version,
            enumMappingJson = objectMapper.writeValueAsString(enumSnapshot),
            startedAt = batch.startedAt ?: LocalDateTime.now(),
            completedAt = null,
            leaseHolder = null,
            leaseExpiresAt = null,
            nextRetryAt = null,
            lastErrorCode = null,
            lastErrorDetail = null,
            updatedAt = LocalDateTime.now()
        ))
        return queued.toView()
    }

    @Transactional
    fun processBatch(batchId: String): ImportBatchView {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        ensureImport(batch.batchStatus == "PROCESSING") { "批次不在处理状态" }
        val mapping: Map<String, String> = objectMapper.readValue(
            importRequired(batch.mappingConfigJson) { "批次缺少持久化映射" },
            object : TypeReference<Map<String, String>>() {}
        )
        val enumSnapshot = enumSnapshot(batch)
        // 5 万条的批次不能待在同一个持久化上下文里。每个事务只处理一个有界
        // 切片；Worker 在切片之间释放并重新领取租约。
        val ownerInitializationSourceIds = rows.findTop500ByBatchIdAndRowStatusOrderById(batch.id, "PENDING")
            .mapNotNull { row ->
                processRow(batch, row, mapping, enumSnapshot).jiraOwnerSourceId
            }
        initializeJiraOwners(batch.sourceChannel, ownerInitializationSourceIds)
        val success=rows.countByBatchIdAndRecordTypeAndRowStatusIn(batch.id,"ROW",listOf("IMPORTED","UPDATED")).toInt()
        val inserted=rows.countByBatchIdAndRecordTypeAndRowStatus(batch.id,"ROW","IMPORTED").toInt()
        val updated=rows.countByBatchIdAndRecordTypeAndRowStatus(batch.id,"ROW","UPDATED").toInt()
        val failed=rows.countByBatchIdAndRecordTypeAndRowStatusIn(batch.id,"ROW",listOf("INVALID","FAILED")).toInt()
        val skipped=rows.countByBatchIdAndRecordTypeAndRowStatusIn(batch.id,"ROW",listOf("SKIPPED_DUPLICATE","SKIPPED_STALE")).toInt()
        val reviewed=rows.countByBatchIdAndRecordTypeAndRowStatus(batch.id,"ROW","REVIEW_NEEDED").toInt()
        val pending=rows.countByBatchIdAndRecordTypeAndRowStatus(batch.id,"ROW","PENDING").toInt()
        if(pending>0){
            return batches.save(batch.copy(successRows=success,insertedRows=inserted,updatedRows=updated,failedRows=failed,skippedRows=skipped,reviewedRows=reviewed,
                leaseHolder=null,leaseExpiresAt=null,updatedAt=LocalDateTime.now())).toView()
        }
        val status = if (failed + reviewed == 0) "COMPLETED" else "PARTIAL_SUCCESS"
        val reconciliation = if (success + failed + skipped + reviewed == batch.totalRows) "MATCHED" else "MISMATCH"
        val saved = batches.save(batch.copy(
            successRows = success, insertedRows=inserted, updatedRows=updated, failedRows = failed, skippedRows = skipped, reviewedRows = reviewed,
            batchStatus = if (reconciliation == "MATCHED") status else "FAILED",
            reconciliationStatus = reconciliation,
            startedAt = batch.startedAt ?: LocalDateTime.now(), completedAt = LocalDateTime.now(),
            mappingConfigVersion = batch.mappingConfigVersion, leaseHolder = null, leaseExpiresAt = null,
            nextRetryAt = null, lastErrorCode = null, lastErrorDetail = null, updatedAt = LocalDateTime.now()
        ))
        qualityMetrics?.snapshot(saved.id)
        return saved.toView()
    }

    fun list(): List<ImportBatchView> = batches.findTop10ByOrderByCreatedAtDesc().map { it.toView() }
    fun channelOf(batchId:String):String=importRequired(batches.findByBatchId(batchId)){"批次不存在"}.sourceChannel

    @Cacheable(cacheNames = ["importBatches"], key = "#page + ':' + #size + ':' + (#channel ?: '') + ':' + (#status ?: '') + ':' + #includeGoverned",
        unless = "#result.records.?[status == 'PROCESSING'].size() > 0")
    fun list(page: Int, size: Int, channel: String?, status: String?, includeGoverned: Boolean = false): ImportBatchPage {
        ensureImport(page >= 1 && size in 1..100) { "IMPORT_PAGE_INVALID" }
        val normalizedChannel=channel?.takeIf(String::isNotBlank)?.uppercase()?.also{ensureImport(it in CHANNELS){"IMPORT_CHANNEL_INVALID"}}
        val normalizedStatus=status?.takeIf(String::isNotBlank)?.uppercase()
        val pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val result = when {
            includeGoverned && normalizedChannel != null && normalizedStatus != null -> batches.findAllBySourceChannelAndBatchStatus(normalizedChannel, normalizedStatus, pageable)
            includeGoverned && normalizedChannel != null -> batches.findAllBySourceChannel(normalizedChannel, pageable)
            includeGoverned && normalizedStatus != null -> batches.findAllByBatchStatus(normalizedStatus, pageable)
            includeGoverned -> batches.findAll(pageable)
            normalizedChannel != null && normalizedStatus != null -> batches.findAllBySourceChannelAndBatchStatusAndGovernanceStatus(normalizedChannel, normalizedStatus, "ACTIVE", pageable)
            normalizedChannel != null -> batches.findAllBySourceChannelAndGovernanceStatus(normalizedChannel, "ACTIVE", pageable)
            normalizedStatus != null -> batches.findAllByBatchStatusAndGovernanceStatus(normalizedStatus, "ACTIVE", pageable)
            else -> batches.findAllByGovernanceStatus("ACTIVE", pageable)
        }
        return ImportBatchPage(result.content.map { it.toView() }, result.totalElements, page, size)
    }

    @Cacheable(cacheNames = ["importBatchDetails"], key = "#batchId", unless = "#result.batch.status == 'PROCESSING'")
    fun detail(batchId: String): ImportBatchDetail {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        val errors = rows.findByBatchId(batch.id).filter { it.rowStatus in setOf("INVALID", "FAILED", "REVIEW_NEEDED") }
            .map { ImportRowError(it.rowNumber, it.rowStatus, it.qualityErrorCode, it.qualityErrorSummary, it.qualitySuggestion) }
        val rollbackHours=(runtimeConfig?.int("import.rollback-hours",24)?:24).toLong()
        val deadline=batch.createdAt.plusHours(rollbackHours)
        val processed=batch.successRows+batch.failedRows+batch.skippedRows+batch.reviewedRows
        return ImportBatchDetail(batch.toView(), errors, qualityMetrics?.get(batch.id).orEmpty(),
            ImportBatchActions(batch.ingestMethod=="FILE"&&batch.batchStatus=="COMPLETED"&&deadline.isAfter(LocalDateTime.now()),deadline,
                batch.batchStatus in setOf("FAILED","PARTIAL_SUCCESS")||batch.failedRows+batch.reviewedRows>0),
            if(batch.totalRows==0)0 else (processed*100/batch.totalRows),batch.lastErrorCode,batch.lastErrorDetail)
    }

    fun mappingPreview(batchId: String): ImportUploadResult {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        ensureImport(batch.ingestMethod == "FILE") { "API_BATCH_MAPPING_NOT_SUPPORTED" }
        ensureImport(batch.batchStatus in setOf("AWAITING_MAPPING","PARTIAL_SUCCESS","FAILED")) { "当前批次不允许调整映射" }
        val dataRows = rows.findByBatchId(batch.id).filter { it.recordType == "ROW" && it.rawRowContentEnc != null }.take(100)
        ensureImport(dataRows.isNotEmpty()) { "IMPORT_DATA_ROW_REQUIRED" }
        val rawRows = dataRows.map { row ->
            val bytes = rawCrypto.decrypt(importRequired(row.rawRowContentEnc), importRequired(row.rawTag), importRequired(row.rawNonce))
            try { objectMapper.readValue(bytes, object : TypeReference<Map<String, String>>() {}) }
            finally { bytes.fill(0) }
        }
        val headers = rawRows.first().keys.toList()
        val recommended = batch.mappingConfigJson?.let {
            objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {})
        } ?: recommendMapping(headers, batch.sourceChannel)
        val preview = rawRows.map { row -> headers.map { header -> masking.mask(row[header].orEmpty(), "mapping_preview", recordAudit = false).maskedText.take(500) } }
        return ImportUploadResult(batch.batchId,batch.batchStatus,"NEW",batch.totalRows,listOf("已上传数据"),headers,preview,
            recommended,batch.mappingTemplateId,batch.mappingConfigVersion,templateContracts.boundary(batch.sourceChannel,headers),
            batch.contentSha256.toHex(),batch.normalizedContentHash.toHex(),listOf(ImportSheetView("已上传数据",batch.totalRows,headers.size)),
            recommended.mapValues { 1.0 },batch.sourceChannel=="SDK","REVIEW_REQUIRED","需要人工调整并发布新版本",false)
    }

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun rollback(batchId: String): ImportBatchView {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        ensureImport(batch.ingestMethod == "FILE") { "JIRA_API_BATCH_ROLLBACK_REQUIRES_BACKFILL" }
        ensureImport(batch.batchStatus != "ROLLED_BACK") { "批次已回滚" }
        val rollbackHours=(runtimeConfig?.int("import.rollback-hours",24)?:24).toLong()
        ensureImport(batch.createdAt.isAfter(LocalDateTime.now().minusHours(rollbackHours))) { "批次已超过允许回滚窗口" }
        ensureImport(batch.batchStatus == "COMPLETED") { "仅已完成批次允许回滚" }
        rollbackPersistence.rollback(batch.id)
        return batches.save(batch.copy(batchStatus = "ROLLED_BACK", completedAt = LocalDateTime.now(), updatedAt = LocalDateTime.now())).toView()
    }

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun retry(batchId: String, mapping: Map<String, String>? = null): ImportBatchView {
        val batch = importRequired(batches.findByBatchId(batchId)) { "批次不存在" }
        if (batch.ingestMethod == "API") {
            ensureImport(mapping == null) { "API_BATCH_MAPPING_NOT_SUPPORTED" }
            ensureImport(batch.batchStatus in setOf("FAILED", "PARTIAL_SUCCESS")) { "批次状态不允许重试" }
            ensureImport(batch.createdAt.plusDays(7).isAfter(LocalDateTime.now())) { "IMPORT_RAW_RETENTION_EXPIRED" }
            importRequired(batch.mappingConfigJson) { "批次缺少持久化字段映射" }
            rows.resetRetryableRows(batch.id)
            return batches.save(batch.copy(
                batchStatus = "PROCESSING", completedAt = null, leaseHolder = null, leaseExpiresAt = null,
                nextRetryAt = null, lastErrorCode = null, lastErrorDetail = null, updatedAt = LocalDateTime.now()
            )).toView()
        }
        val effectiveMapping = mapping ?: batch.mappingConfigJson?.let {
            objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {})
        } ?: importAbort("批次缺少持久化字段映射")
        return confirm(batchId, effectiveMapping)
    }

    @Transactional
    @CacheEvict(cacheNames = ["importBatches", "importBatchDetails"], allEntries = true)
    fun retryTechnicalFailures(batchId:String):ImportBatchView {
        val batch=importRequired(batches.findByBatchId(batchId)){"批次不存在"}
        ensureImport(batch.batchStatus in setOf("FAILED","PARTIAL_SUCCESS")){"批次状态不允许技术重试"}
        ensureImport(batch.createdAt.plusDays(7).isAfter(LocalDateTime.now())){"IMPORT_RAW_RETENTION_EXPIRED"}
        importRequired(batch.mappingConfigJson){"批次缺少持久化映射"}
        rows.resetRowsByStatuses(batch.id,setOf("FAILED"))
        return batches.save(batch.copy(batchStatus="PROCESSING",completedAt=null,leaseHolder=null,leaseExpiresAt=null,
            nextRetryAt=null,lastErrorCode=null,lastErrorDetail=null,updatedAt=LocalDateTime.now())).toView()
    }

    private fun processRow(
        batch: ImportBatch,
        row: ImportRow,
        mapping: Map<String, String>,
        enumSnapshot: EnumMappingSnapshot,
        falsePositiveOverride: Boolean = false
    ): ImportRowProcessingResult {
        val status = processRowStatus(batch, row, mapping, enumSnapshot, falsePositiveOverride)
        return ImportRowProcessingResult(
            status = status,
            jiraOwnerSourceId = row.feedbackSourceId.takeIf {
                batch.sourceChannel == "JIRA" && status in setOf("IMPORTED", "UPDATED")
            }
        )
    }

    private fun processRowStatus(batch: ImportBatch, row: ImportRow, mapping: Map<String, String>, enumSnapshot: EnumMappingSnapshot, falsePositiveOverride: Boolean = false): String {
        return try {
            val rawBytes = rawCrypto.decrypt(importRequired(row.rawRowContentEnc), importRequired(row.rawTag), importRequired(row.rawNonce))
            val raw: Map<String, String> = objectMapper.readValue(rawBytes, object : TypeReference<Map<String, String>>() {})
            rawBytes.fill(0)
            val value: (String) -> String? = { target -> mapping[target]?.let(raw::get)?.trim()?.takeIf(String::isNotBlank) }
            val recordId = sourceRecordId(batch.sourceChannel, value)
            row.businessKeyHash = sha256("${batch.sourceChannel}:$recordId".toByteArray())
            val previousQuarantined = rows.findLatestActiveReviewByBusinessKey(
                batch.sourceChannel, importRequired(row.businessKeyHash), row.id
            )
            if (previousQuarantined?.rowFingerprint?.contentEquals(row.rowFingerprint ?: ByteArray(0)) == true)
                return skipRow(row, null, "SKIPPED_DUPLICATE")
            val incomingUpdateRaw = value("source_update_time")
            val incomingUpdateTime = incomingUpdateRaw?.let {
                importRequired(parseDateTime(it)) { "来源更新时间格式非法" }
            }
            val sourceTimeRaw=value("source_event_time")
            val sourceTime=sourceTimeRaw?.let { importRequired(parseDateTime(it)){"来源事件时间格式非法"} }
            val existing = sources.findBySourceChannelAndSourceRecordId(batch.sourceChannel, recordId)
            if (existing != null) {
                val previousRow = rows.findFirstByFeedbackSourceIdOrderByCreatedAtDesc(existing.id)
                val existingContentSafe = contents.findById(existing.id).orElse(null)?.let {
                    it.maskingStatus == "PASS" && !it.isDeleted
                } == true
                if (previousRow?.rowFingerprint?.contentEquals(row.rowFingerprint ?: ByteArray(0)) == true &&
                    existingContentSafe && !requiresJiraIdentityRefresh(
                        batch.sourceChannel, existing.id, listOfNotNull(value("title"), value("description")).joinToString("\n")
                    ))
                    return skipRow(row, existing.id, "SKIPPED_DUPLICATE")
                if (incomingUpdateTime != null && incomingUpdateTime.isBefore(existing.sourceUpdateTime))
                    return skipRow(row, existing.id, "SKIPPED_STALE")
                if (incomingUpdateTime == null) {
                    conflictEvents?.record(batch.sourceChannel,recordId,batch.id,row.id,existing.id,previousRow?.rowFingerprint,importRequired(row.rowFingerprint))
                    row.feedbackSourceId=existing.id;row.rowStatus="FAILED";row.qualityStage="IDEMPOTENCY";row.qualityErrorCode="IDEMPOTENCY_CONFLICT";row.qualityErrorSummary="相同业务键出现不同内容且缺少单调更新时间";row.qualitySuggestion="在幂等冲突中心选择保留现有记录或接受新记录";rows.save(row);return "FAILED"
                }
            }
            val description = importRequired(value("description")) { "缺少问题描述" }
            val descriptionMask = masking.mask(description, "import_${batch.id}_${row.rowNumber}")
            val titleMask = masking.mask(value("title").orEmpty(), "import_title_${batch.id}_${row.rowNumber}")
            val sdkContext = if (batch.sourceChannel == "SDK") SDK_SIMULATED_FIELDS.mapNotNull { field ->
                value(field)?.let { rawValue ->
                    val result = masking.mask(rawValue, "import_sdk_${field}_${batch.id}_${row.rowNumber}")
                    Triple(field, result.maskedText, result.maskingStatus)
                }
            } else emptyList()
            val maskingStatus = if (
                descriptionMask.maskingStatus == "PASS" && titleMask.maskingStatus == "PASS" &&
                sdkContext.all { it.third == "PASS" }
            ) "PASS" else "REVIEW_NEEDED"
            if (maskingStatus != "PASS" && !falsePositiveOverride) {
                row.rowStatus = "REVIEW_NEEDED"
                row.qualityStage = "MASKING_GATE"
                row.qualityErrorCode = "MASKING_TECHNICAL_FAILURE"
                row.qualityErrorSummary = "脱敏处理未生成安全正文"
                row.qualitySuggestion = "修复脱敏规则或运行环境后自动重试"
                rows.save(row)
                return row.rowStatus!!
            }
            // 被重试的行不能保留导致重试的那条失败分类。
            // 下面任何当前的归一化发现，都会写入一条全新的、真实的分类。
            row.qualityStage = null
            row.qualityErrorCode = null
            row.qualityErrorSummary = null
            row.qualitySuggestion = null
            val normalized = enumMappings.normalize(batch.sourceChannel, { target -> value(target) }, enumSnapshot)
            if (normalized.errorCodes.isNotEmpty()) {
                row.qualityStage = "NORMALIZATION"
                row.qualityErrorCode = normalized.errorCodes.first().take(50)
                row.qualityErrorSummary = "存在未映射枚举，已保留安全转换事实"
                row.qualitySuggestion = "补充受控枚举映射后重试或人工确认"
            }
            val appVersion=value("app_version") ?: if(batch.sourceChannel=="JIRA")
                JiraDescriptionDimensions.extractSystemVersion(descriptionMask.maskedText) else null
            val faultTime = when(batch.sourceChannel) {
                "SDK" -> com.feedback.service.normalization.FaultOccurrenceTimeResolver.sdk(sourceTime)
                "REDBLACK" -> com.feedback.service.normalization.FaultOccurrenceTimeResolver.notApplicable()
                "JIRA" -> com.feedback.service.normalization.FaultOccurrenceTimeResolver.fromDescription("JIRA",descriptionMask.maskedText,sourceTime)
                "QUALITY" -> com.feedback.service.normalization.FaultOccurrenceTimeResolver.proxy(
                    sourceTime,"QUALITY_RETURN_APPLY_PROXY","DAY")
                else -> com.feedback.service.normalization.FaultOccurrenceTimeResolver.fromDescription(batch.sourceChannel,descriptionMask.maskedText,sourceTime)
            }
            val source = if (existing == null) sources.save(FeedbackSource(
                publicId = uuidBytes(UUID.randomUUID()), sourceChannel = batch.sourceChannel,
                sourceRecordId = recordId, appVersion = appVersion, packageName = value("package_name"), sourceEventTime = sourceTime,
                faultOccurredAt=faultTime.occurredAt,faultTimeRaw=faultTime.raw,faultTimePrecision=faultTime.precision,
                faultTimeSource=faultTime.source,faultTimeResolution=faultTime.resolution,
                sourceUpdateTime = incomingUpdateTime ?: sourceTime ?: batch.createdAt, importBatchId = batch.id,
                provinceCode = value("province_code") ?: value("quality_province"),
                cityCode = value("city_code") ?: value("quality_city"),
                districtCode = value("district_code") ?: value("quality_county") ?: value("district")
            )) else sources.save(existing.copy(
                appVersion = appVersion ?: existing.appVersion,
                packageName = value("package_name") ?: existing.packageName,
                sourceEventTime = sourceTime,
                faultOccurredAt=faultTime.occurredAt,faultTimeRaw=faultTime.raw,faultTimePrecision=faultTime.precision,
                faultTimeSource=faultTime.source,faultTimeResolution=faultTime.resolution,
                sourceUpdateTime = incomingUpdateTime ?: existing.sourceUpdateTime,
                importBatchId = batch.id,
                provinceCode = value("province_code") ?: value("quality_province") ?: existing.provinceCode,
                cityCode = value("city_code") ?: value("quality_city") ?: existing.cityCode,
                districtCode = value("district_code") ?: value("quality_county") ?: value("district") ?: existing.districtCode,
                updatedAt = LocalDateTime.now()
            ))
            contents.save(FeedbackContent(
                sourceId = source.id, title = titleMask.maskedText.takeIf(String::isNotBlank),
                description = descriptionMask.maskedText.takeIf(String::isNotBlank),
                functionModule = normalized.functionModule, problemType = normalized.problemType,
                severityLevel = normalized.severityLevel, issueCategory = normalized.issueCategory,
                userSentiment = normalized.userSentiment, reproducibility = normalizeReproducibility(value("reproducibility")), processingStatus = normalized.processingStatus,
                mappingError = normalized.errorCodes.takeIf(List<String>::isNotEmpty)?.joinToString(","),
                mappingRuleVersion = batch.mappingConfigVersion ?: MAPPING_VERSION, normalizationVersion = enumSnapshot.version,
                maskingStatus = "PASS",
                dataQualityScore = qualityMetrics?.score(FeedbackQualityInput(
                    recordId, description, normalized.errorCodes, maskingStatus, sourceTime
                )) ?: if (normalized.errorCodes.isNotEmpty()) BigDecimal("0.800000") else BigDecimal("1.000000"),
                qualityRuleVersion = ImportQualityService.VERSION
            ))
            conversionHistory?.record(source.id, row.id, normalized.conversions, batch.mappingConfigVersion ?: MAPPING_VERSION, enumSnapshot.version)
            val diagnosticIdentities = when (batch.sourceChannel) {
                "JIRA" -> JiraDescriptionDimensions.extractControlledIdentities(
                    listOfNotNull(value("title"), description, value("comment_summary"), value("environment")).joinToString("\n")
                )
                "QUALITY" -> listOfNotNull(value("device_sn")?.let { "DEVICE_SN" to it })
                "REDBLACK" -> listOfNotNull(value("uid")?.let { "USER_ID" to it })
                else -> emptyList()
            }
            persistImportIdentities(source.id, descriptionMask.findings + titleMask.findings, diagnosticIdentities)
            persistChannel(
                batch.sourceChannel, source.id, value, descriptionMask.maskedText,
                sdkContext.associate { it.first to it.second }, batch.sourceInstanceHash
            )
            if (batch.sourceChannel == "JIRA") {
                jiraAttachmentPullQueue?.enqueue(
                    source.id,
                    recordId,
                    incomingUpdateTime ?: source.sourceUpdateTime ?: sourceTime ?: batch.createdAt
                )
            }
            value("attachment_urls")?.let { urls ->
                importRequired(attachmentTasks) { "IMPORT_ATTACHMENT_SERVICE_NOT_CONFIGURED" }
                    .create(row.id, source.id, urls, value("attachment_sha256"), value("attachment_size"))
            }
            row.feedbackSourceId = source.id
            row.privacyReviewOutputSha256 = sha256(objectMapper.writeValueAsBytes(
                listOf(titleMask.maskedText, descriptionMask.maskedText).filter(String::isNotBlank)))
            row.rowStatus = if (existing == null) "IMPORTED" else "UPDATED"
            if (row.qualityStage == null) row.qualityStage = "MASKING_GATE"
            deleteRaw(row)
            rows.save(row)
            row.rowStatus!!
        } catch (error: Exception) {
            row.rowStatus = "INVALID"
            row.qualityStage = "VALIDATION"
            row.qualityErrorCode = when {
                error is java.time.format.DateTimeParseException -> "INVALID_TIME"
                error.message == "缺少问题描述" -> "REQUIRED_FIELD_MISSING"
                else -> "VALIDATION_ERROR"
            }
            row.qualityErrorSummary = com.feedback.common.SafeErrorDetail.summary(error,"IMPORT_ROW")
            row.qualitySuggestion = "检查必填字段、日期格式和字段映射"
            rows.save(row)
            "INVALID"
        }
    }

    /**
     * Jira 行先完成 JPA source/channel 持久化，再以最多 200 个 source ID 的固定块进入 MyBatis 初始化。
     * 初始化异常位于行级 try/catch 之外，必须向外传播并回滚整个导入切片，不能降级成单行 INVALID。
     */
    private fun initializeJiraOwners(channel: String, sourceIds: List<Long>) {
        val initializer = jiraOwnerInitializer ?: return
        if (channel != "JIRA" || sourceIds.isEmpty()) return
        val ids = sourceIds.distinct().sorted()
        ids.chunked(JiraAssigneeOwnerInitializer.MAX_BATCH_SIZE).forEach(initializer::initializeBatch)
    }

    private fun persistChannel(
        channel: String,
        sourceId: Long,
        value: (String) -> String?,
        maskedDescription: String,
        sdkContext: Map<String, String> = emptyMap(),
        sourceInstanceHash: ByteArray? = null
    ) {
        val adapters = mapOf(
            "SDK" to ImportChannelAdapter { sdk.save(ChannelSdk(
                sourceId = sourceId,
                clientFeedbackId = importRequired(value("source_record_id")),
                appId = importRequired(value("app_id")) { "缺少 app_id" },
                packageName = value("package_name"),
                osVersion = value("os_version"),
                networkType = value("network_type")?.uppercase()?.take(10),
                deviceModel = value("device_model"),
                screenOrientation = value("screen_orientation")?.uppercase()?.take(10),
                appSessionId = value("app_session_id")?.take(50),
                triggerType = value("trigger_type")?.uppercase()?.take(20),
                inputMethods = value("input_methods")?.uppercase()?.take(50),
                batteryLevel = value("battery_level")?.toBigDecimalOrNull(),
                storageAvailableMb = value("storage_available_mb")?.toIntOrNull(),
                integrationMode = "STANDALONE_HOST",
                contextSource = "SIMULATED_IMPORT",
                appInternalLogs = sdkContext["app_internal_logs"],
                crashStack = sdkContext["crash_stack"],
                accountContext = sdkContext["account_context"]?.let(objectMapper::writeValueAsString),
                courseId = sdkContext["course_id"]?.take(100),
                questionId = sdkContext["question_id"]?.take(100),
                orderId = sdkContext["order_id"]?.take(100),
                pageRoute = sdkContext["page_route"]?.take(500),
                businessId = sdkContext["business_id"]?.take(100),
                apiRequestSummary = sdkContext["api_request_summary"],
                stateMachine = sdkContext["state_machine"]?.let(objectMapper::writeValueAsString),
                customEvents = sdkContext["custom_events"]?.let(objectMapper::writeValueAsString),
                privateFileManifest = sdkContext["private_file_manifest"]?.let(objectMapper::writeValueAsString)
            )) },
            "JIRA" to ImportChannelAdapter { jira.save(ChannelJira(
                sourceId = sourceId,
                jiraBaseUrlHash = sourceInstanceHash ?: sha256("default-jira-import".toByteArray()),
                jiraKey = importRequired(value("source_record_id")),
                summary = safeText(value("title")),
                issueTypeRaw = value("problem_type") ?: value("jira_issue_type"),
                issueId = value("jira_issue_id"),
                jiraStatusRaw = value("status"),
                priorityRaw = value("priority"),
                projectKey = value("project_key"),
                projectName = safeText(value("project_name")),
                projectType = value("project_type"),
                // Jira 人员字段是只读 Jira API 返回的受控工作流身份。
                // 保留这些显示名可读；自由文本在下面仍然走脱敏。
                projectLead = jiraPersonDisplayName(value("project_lead")),
                projectDescription = safeText(value("project_description")),
                projectUrl = sanitizeUrl(value("project_url")),
                resolution = value("resolution"),
                assignee = jiraPersonDisplayName(value("assignee")),
                reporter = jiraPersonDisplayName(value("reporter")),
                creator = jiraPersonDisplayName(value("creator")),
                jiraCreatedAt = parseDateTime(value("source_event_time")),
                jiraUpdatedAt = parseDateTime(value("source_update_time")),
                lastViewedAt = parseDateTime(value("last_viewed_at")),
                resolvedAt = parseDateTime(value("resolved_at")),
                affectedVersion = value("affected_version"),
                fixVersion = value("fix_version"),
                moduleRaw = value("function_module"),
                dueDate = parseDate(value("due_date")),
                votes = value("votes")?.toIntOrNull(),
                label1 = value("label_1"),
                label2 = value("label_2"),
                label3 = value("label_3"),
                descriptionText = safeText(value("description")),
                commentSummary = safeText(value("comment_summary")),
                environment = safeText(value("environment")),
                watchers = safeText(value("watchers")),
                worklogTime = value("worklog_time"),
                originalEstimateRaw = value("original_estimate_raw"),
                remainingEstimateRaw = value("remaining_estimate_raw"),
                timeSpentRaw = value("time_spent_raw"),
                workRatio = value("work_ratio"),
                sigmaOriginalEstimate = value("sigma_original_estimate"),
                sigmaRemainingEstimate = value("sigma_remaining_estimate"),
                sigmaTimeSpent = value("sigma_time_spent"),
                securityLevel = value("security_level"),
                inwardDuplicateLink = value("inward_duplicate_link"),
                outwardDuplicateLink = value("outward_duplicate_link")
            )) },
            "QUALITY" to ImportChannelAdapter { quality.save(ChannelQuality(
                sourceId = sourceId,
                returnOrderNo = safeText(value("return_order_no")),
                productName = safeText(value("product_name")),
                productModel = safeText(value("product_model")),
                dispatchDate = parseDate(value("dispatch_date")),
                activationDate = parseDate(value("activation_date")),
                repairRegion = safeText(value("repair_region")),
                province = safeText(value("quality_province")),
                city = safeText(value("quality_city")),
                county = safeText(value("quality_county")),
                // 精确地址在派生出行政区划代码之后被刻意销毁。
                customerAddress = null,
                district = safeText(value("district")),
                applicationTypeRaw = value("application_type"),
                exchangeRequest = parseBoolean(value("exchange_request")),
                returnReason = safeText(value("return_reason")),
                faultConfirmed = safeText(value("fault_confirmed")),
                faultRemark = safeText(value("description")),
                merchantReturnApplyDate = parseDateTime(value("source_event_time")),
                actualShippingDate = parseDate(value("shipping_date"))
            )) },
            "REDBLACK" to ImportChannelAdapter { redblack.save(ChannelRedblack(
                sourceId = sourceId,
                originalId = value("original_id")?.toLongOrNull(),
                originalIdRaw = value("original_id"),
                uidRaw = null,
                uidHash = value("uid")?.let { fingerprints.fingerprint(it, "ACCOUNT") },
                headline = safeText(value("title")),
                contentText = safeText(value("description")),
                author = safeText(value("author")),
                url = sanitizeUrl(value("source_url") ?: value("url")),
                sourcePlatform = value("source_platform"),
                pubTypeName = value("pub_type"),
                sectionRaw = value("section"),
                folderName = value("folder_name"),
                originMessage = safeText(value("origin_message")),
                keyword = value("keyword"),
                emotionTypeRaw = value("emotion"),
                evaluationObject = safeText(value("evaluation_object")),
                evaluationDimension = safeText(value("evaluation_dimension")),
                impactLevelRaw = value("impact_level"),
                dataPeriod = value("data_period"),
                sourceDepartmentMasked = safeText(value("source_department")),
                sourceRoleMasked = safeText(value("source_role")),
                pushTime = parseDateTime(value("push_time")),
                pubTime = parseDateTime(value("source_event_time")),
                createTime = parseDateTime(value("redblack_create_time")),
                updateTime = parseDateTime(value("source_update_time")),
                isDeletedSrc = parseBoolean(value("is_deleted")),
                coverImageUrl = sanitizeUrl(value("cover_image_url")),
                imageUrls = value("image_urls"),
                videoUrls = value("video_urls")
            )) }
        )
        importRequired(adapters[channel]) { "IMPORT_CHANNEL_ADAPTER_NOT_FOUND:$channel" }.persist()
    }

    private fun normalizeReproducibility(value: String?): String? = when (value?.trim()?.uppercase()) {
        null, "" -> null
        "ALWAYS", "必现", "每次", "总是" -> "ALWAYS"
        "FREQUENT", "频繁", "经常" -> "FREQUENT"
        "OCCASIONAL", "偶发", "有时" -> "OCCASIONAL"
        "ONCE", "一次", "仅一次" -> "ONCE"
        "UNKNOWN", "未知", "无法判断" -> "UNKNOWN"
        else -> "UNKNOWN"
    }

    private fun persistImportIdentities(
        sourceId: Long,
        findings: List<com.feedback.service.masking.MaskingFinding>,
        diagnosticValues: List<Pair<String, String>> = emptyList()
    ) {
        val values = findings.filter { it.type in setOf("PHONE", "EMAIL") }.map { it.type to it.originalValue } + diagnosticValues
        values.filter { it.second.isNotBlank() }.distinctBy { it.first }.forEach { (type, originalValue) ->
            val scope = if (type == "DEVICE_SN") "DEVICE" else "PERSON"
            val normalized = fingerprints.normalize(originalValue, type)
            val fingerprint = fingerprints.fingerprint(normalized, type)
            val existingIdentity=identities.findBySourceIdAndIdentityTypeAndIdentityScope(sourceId,type,scope)
            if (existingIdentity==null||!existingIdentity.fingerprint.contentEquals(fingerprint)) {
                val contactCrypto=importRequired(contactStorage) { "CONTACT_STORAGE_NOT_CONFIGURED" }
                val encrypted = contactCrypto.encrypt(normalized)
                identities.save(FeedbackIdentity(
                    id=existingIdentity?.id?:0,sourceId = sourceId, identityType = type, identityScope = scope,
                    fingerprint = fingerprint, fingerprintKeyId = fingerprints.keyId,
                    contactCiphertext = encrypted.ciphertext, contactNonce = encrypted.nonce, contactTag = encrypted.tag,
                    contactKeyId = contactCrypto.keyId, contactAlgorithm = "AES-256-GCM",
                    contactAuthorizationBasis = "CHANNEL_GOVERNANCE", consentedAt = LocalDateTime.now(),
                    expiresAt = LocalDateTime.of(2099, 12, 31, 23, 59, 59),createdAt=existingIdentity?.createdAt?:LocalDateTime.now()
                ))
            }
        }
    }

    private fun deleteRaw(row: ImportRow) {
        row.rawRowContentEnc = null; row.rawNonce = null; row.rawTag = null; row.rawKeyId = null; row.rawAlgorithm = null
        row.rawDeletedAt = LocalDateTime.now()
    }

    private fun skipRow(row: ImportRow, sourceId: Long?, status: String): String {
        row.feedbackSourceId = sourceId
        row.rowStatus = status
        row.qualityStage = "IDEMPOTENCY"
        deleteRaw(row)
        rows.save(row)
        return status
    }

    private fun validateMapping(channel: String, mapping: Map<String, String>, headers: Set<String>, nonemptyHeadersJson: String?) {
        val required = if (channel == "QUALITY") setOf("description") else setOf("source_record_id", "description")
        ensureImport(mapping.keys.containsAll(required)) { "缺少必需映射: ${required - mapping.keys}" }
        ensureImport(mapping.values.none(String::isBlank)) { "映射表头不能为空" }
        ensureImport(channel in CHANNELS)
        val unsupported = mapping.keys - templateContracts.allowedTargets(channel)
        ensureImport(unsupported.isEmpty()) { "CHANNEL_MAPPING_TARGET_FORBIDDEN:$unsupported" }
        val missingHeaders = mapping.values.toSet() - headers
        ensureImport(missingHeaders.isEmpty()) { "MAPPING_SOURCE_HEADER_NOT_FOUND:$missingHeaders" }
        val nonemptyHeaders: Set<String> = nonemptyHeadersJson?.let {
            objectMapper.readValue(it, object : TypeReference<Set<String>>() {})
        }.orEmpty()
        val unmapped = nonemptyHeaders - mapping.values.toSet()
        ensureImport(unmapped.isEmpty()) { "NONEMPTY_SOURCE_COLUMN_UNMAPPED:$unmapped" }
    }

    private fun sourceRecordId(channel: String, value: (String) -> String?): String =
        if (channel == "QUALITY") {
            value("source_record_id")?.take(100) ?: qualityCompositeRecordId(value)
        } else {
            importRequired(value("source_record_id")) { "缺少来源记录ID" }.take(100)
        }

    private fun qualityCompositeRecordId(value: (String) -> String?): String {
        val returnOrderNo = value("return_order_no")
        val deviceSn = value("device_sn")
        if (returnOrderNo != null && deviceSn != null) {
            val normalizedOrder = returnOrderNo.trim().replace(Regex("\\s+"), " ").lowercase()
            val deviceFingerprint = fingerprints.fingerprint(deviceSn, "DEVICE_SN").toHex()
            val normalized = "$normalizedOrder\u001f$deviceFingerprint"
            return "QUALITY-${sha256Hex(normalized.toByteArray()).take(32)}"
        }
        val fields = listOf("customer_address", "fault_confirmed", "source_event_time")
        val parts = fields.map { field ->
            importRequired(value(field)) { "质量平台缺少业务键字段:$field" }.trim()
        }
        val normalized = parts.joinToString("\u001f") { it.replace(Regex("\\s+"), " ").lowercase() }
        return "QUALITY-${sha256Hex(normalized.toByteArray()).take(32)}"
    }

    private fun resultFor(batch: ImportBatch, parsed: ParsedImportFile, sheet: ParsedSheet, duplicate: String) = ImportUploadResult(
        batch.batchId, batch.batchStatus, duplicate, batch.totalRows, parsed.sheets.map { it.name }, sheet.header,
        sheet.dataRows.take(100).map { row -> row.map { masking.mask(it, "preview", recordAudit = false).maskedText.take(500) } },
        batch.mappingConfigJson?.let { objectMapper.readValue(it, object : TypeReference<Map<String, String>>() {}) }
            ?: recommendMapping(sheet.header, batch.sourceChannel), batch.mappingTemplateId, batch.mappingConfigVersion,
        templateContracts.boundary(batch.sourceChannel, sheet.header),batch.contentSha256.toHex(),batch.normalizedContentHash.toHex(),
        parsed.sheets.map{ImportSheetView(it.name,it.dataRows.size,it.header.size)},recommendMapping(sheet.header,batch.sourceChannel).mapValues{1.0},
        batch.sourceChannel=="SDK",
        if (batch.batchStatus == "PROCESSING" && batch.mappingConfigJson != null) "AUTO_VERSIONED" else "REVIEW_REQUIRED",
        if (batch.batchStatus == "PROCESSING" && batch.mappingConfigJson != null) "已精确匹配已发布模板" else "表头与当前模板版本不一致，需要提交变更",
        batch.batchStatus == "PROCESSING"
    )

    private fun recommendMapping(headers: List<String>, channel: String): Map<String, String> = ALIASES
        .filterKeys { it in templateContracts.allowedTargets(channel) }
        .mapNotNull { (target, aliases) ->
            headers.firstOrNull { header -> aliases.any { it.equals(header.trim(), true) } }?.let { target to it }
        }.toMap()

    private fun batchHeaders(batchId: Long): Set<String> {
        val firstRow = rows.findByBatchId(batchId).firstOrNull { it.recordType == "ROW" && it.rawRowContentEnc != null }
            ?: importInvalid("IMPORT_DATA_ROW_REQUIRED")
        val rawBytes = rawCrypto.decrypt(importRequired(firstRow.rawRowContentEnc), importRequired(firstRow.rawTag), importRequired(firstRow.rawNonce))
        return try {
            objectMapper.readValue(rawBytes, object : TypeReference<Map<String, String>>() {}).keys
        } finally { rawBytes.fill(0) }
    }

    private fun normalizeChannel(value: String): String = value.trim().uppercase().also { ensureImport(it in CHANNELS) { "渠道仅支持 SDK/JIRA/QUALITY/REDBLACK" } }
    private fun parseBoolean(value: String?): Boolean = value?.lowercase() in setOf("1", "true", "yes", "是", "有")
    private fun parseDate(value: String?): LocalDate? = value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    private fun parseDateTime(value: String?): LocalDateTime? = value?.let {
        listOf<(String) -> LocalDateTime>(
            { v -> OffsetDateTime.parse(v).atZoneSameInstant(ZoneId.of(jiraTimeZone)).toLocalDateTime() },
            { v -> LocalDateTime.parse(v) },
            { v -> LocalDate.parse(v.take(10)).atStartOfDay() }
        ).firstNotNullOfOrNull { parser -> runCatching { parser(it) }.getOrNull() }
    }
    private fun sanitizeUrl(value: String?): String? = value?.substringBefore('?')?.take(500)
    private fun safeText(value: String?): String? = value?.takeIf(String::isNotBlank)?.let { masking.mask(it, recordAudit = false).maskedText }
    private fun jiraPersonDisplayName(value: String?): String? = value?.trim()?.take(100)?.takeIf(String::isNotBlank)
    private fun requiresJiraIdentityRefresh(channel: String, sourceId: Long, sourceText: String): Boolean {
        if (channel != "JIRA") return false
        val record = jira.findById(sourceId).orElse(null) ?: return false
        if (listOf(record.assignee, record.reporter, record.creator, record.projectLead).any { it == "[NAME_REMOVED]" }) return true
        return JiraDescriptionDimensions.extractControlledIdentities(sourceText).any { (type, _) ->
            identities.findBySourceIdAndIdentityTypeAndIdentityScope(
                sourceId, type, if (type == "DEVICE_SN") "DEVICE" else "PERSON"
            ) == null
        }
    }
    private fun disambiguateHeaders(headers: List<String>): List<String> {
        val counts = mutableMapOf<String, Int>()
        return headers.map { raw ->
            val base = raw.trim()
            val next = (counts[base] ?: 0) + 1
            counts[base] = next
            if (next == 1) base else "$base#$next"
        }
    }
    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hashCanonicalRows(values:List<Map<String,String>>):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        values.forEach { digest.update(hashCanonicalRow(it));digest.update(0) }
        return digest.digest()
    }
    private fun enumSnapshot(batch: ImportBatch): EnumMappingSnapshot = batch.enumMappingJson?.let {
        objectMapper.readValue(it, EnumMappingSnapshot::class.java).also { snapshot ->
            ensureImport(snapshot.version == batch.enumMappingVersion) { "IMPORT_ENUM_SNAPSHOT_VERSION_MISMATCH" }
        }
    } ?: enumMappings.snapshot(batch.sourceChannel)
    private fun hashCanonicalRow(value:Map<String,String>):ByteArray {
        val digest=MessageDigest.getInstance("SHA-256")
        value.toSortedMap().forEach { (key,rawValue) ->
            val keyBytes=key.toByteArray();val valueBytes=rawValue.trim().toByteArray()
            try { digest.update(keyBytes);digest.update(0);digest.update(valueBytes);digest.update(31) }
            finally { keyBytes.fill(0);valueBytes.fill(0) }
        }
        return digest.digest()
    }
    private fun sha256Hex(bytes: ByteArray): String = sha256(bytes).joinToString("") { "%02x".format(it) }
    private fun ByteArray.toHex():String=joinToString(""){"%02x".format(it)}
    private fun uuidBytes(uuid: UUID): ByteArray = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    private fun ImportBatch.toView() = ImportBatchView(
        batchId = batchId, channel = sourceChannel, status = batchStatus, totalRows = totalRows,
        successRows = successRows, failedRows = failedRows, skippedRows = skippedRows, reviewedRows = reviewedRows,
        createdAt = createdAt, completedAt = completedAt, dataDateFrom = dataDateFrom, dataDateTo = dataDateTo,
        retryCount = retryCount, reconciliationStatus = reconciliationStatus,
        canRollback = ingestMethod=="FILE"&&batchStatus=="COMPLETED"&&createdAt.plusHours((runtimeConfig?.int("import.rollback-hours",24)?:24).toLong()).isAfter(LocalDateTime.now()),
        canRetry = (batchStatus in setOf("FAILED","PARTIAL_SUCCESS")||failedRows>0)&&createdAt.plusDays(7).isAfter(LocalDateTime.now()),
        rollbackDeadline = createdAt.plusHours((runtimeConfig?.int("import.rollback-hours",24)?:24).toLong()),
        rawRetentionUntil = createdAt.plusDays(7), insertedRows = insertedRows, updatedRows = updatedRows,
        sourceDataUpdatedAt = sources.findByImportBatchId(id).maxOfOrNull { it.sourceUpdateTime },
        ingestMethod = ingestMethod, triggerType = triggerType,
        mappingVersion = mappingConfigVersion, enumMappingVersion = enumMappingVersion,
        governanceStatus = governanceStatus, supersededByBatchId = supersededByBatchId,
        governanceReason = governanceReason, governedAt = governedAt
    )

    companion object {
        private const val MAX_FILE_SIZE = 20L * 1024 * 1024
        private const val MAX_ROWS = 50_000
        private const val MAX_COLUMNS = 200
        private const val MAPPING_VERSION = FieldConversionHistoryService.MAPPING_VERSION
        private const val ROWS_PER_SLICE = 1_000
        private val CHANNELS = setOf("SDK", "JIRA", "QUALITY", "REDBLACK")
        private val SDK_SIMULATED_FIELDS = setOf(
            "app_internal_logs", "crash_stack", "account_context", "course_id", "question_id", "order_id",
            "page_route", "business_id", "api_request_summary", "state_machine", "custom_events",
            "private_file_manifest"
        )
        private val ALIASES = mapOf(
            "source_record_id" to setOf("id", "编号", "issue key", "jira key", "问题编号", "问题关键字", "原始id"),
            "title" to setOf("title", "标题", "summary", "问题标题", "主题", "headline", "故障现象确认"),
            "description" to setOf("description", "描述", "问题描述", "内容", "content", "故障现象备注"),
            "function_module" to setOf("module", "模块", "功能模块"),
            "app_version" to setOf("version", "版本", "应用版本"),
            "source_event_time" to setOf("created", "创建", "创建时间", "发生时间", "发布时间", "商返申请日期", "pub_time"),
            "source_update_time" to setOf("updated", "更新", "更新时间", "修改时间", "update_time"),
            "app_id" to setOf("app id", "app_id", "应用标识"),
            "package_name" to setOf("package", "package_name", "应用包名"),
            "os_version" to setOf("os version", "os_version", "系统版本"),
            "network_type" to setOf("network type", "network_type", "网络类型"),
            "device_model" to setOf("device model", "device_model", "设备型号"),
            "screen_orientation" to setOf("screen orientation", "screen_orientation", "屏幕方向"),
            "app_session_id" to setOf("app session id", "app_session_id", "应用会话id"),
            "battery_level" to setOf("battery level", "battery_level", "电量百分比"),
            "storage_available_mb" to setOf("storage available mb", "storage_available_mb", "可用存储mb"),
            "trigger_type" to setOf("trigger type", "trigger_type", "触发方式"),
            "input_methods" to setOf("input methods", "input_methods", "输入方式"),
            "app_internal_logs" to setOf("app internal logs", "app_internal_logs", "应用内部日志"),
            "crash_stack" to setOf("crash stack", "crash_stack", "崩溃堆栈"),
            "account_context" to setOf("account context", "account_context", "账号上下文"),
            "course_id" to setOf("course id", "course_id", "课程id"),
            "question_id" to setOf("question id", "question_id", "题目id"),
            "order_id" to setOf("order id", "order_id", "订单id"),
            "page_route" to setOf("page route", "page_route", "页面路由"),
            "business_id" to setOf("business id", "business_id", "业务id"),
            "api_request_summary" to setOf("api request summary", "api_request_summary", "接口请求摘要"),
            "state_machine" to setOf("state machine", "state_machine", "状态机"),
            "custom_events" to setOf("custom events", "custom_events", "自定义埋点"),
            "private_file_manifest" to setOf("private file manifest", "private_file_manifest", "私有文件清单"),
            "severity_level" to setOf("severity", "严重程度", "等级"),
            "problem_type" to setOf("issue type", "jira issue type", "Jira问题类型", "来源问题类型", "问题类型"),
            "jira_issue_type" to setOf("问题类型", "issue type", "jira issue type"),
            "jira_issue_id" to setOf("问题ID", "issue id"),
            "standard_problem_type" to setOf("problem type", "标准问题类型", "问题类型编码"),
            "issue_category" to setOf("issue category", "问题大类", "问题分类"),
            "processing_status" to setOf("processing status", "处理状态"),
            "user_sentiment" to setOf("sentiment", "用户情感", "标准情感"),
            "reproducibility" to setOf("reproducibility", "复现情况", "出现频率", "是否必现", "可重现性"),
            "province_code" to setOf("province code", "province_code", "省级行政区划代码"),
            "city_code" to setOf("city code", "city_code", "市级行政区划代码"),
            "district_code" to setOf("district code", "district_code", "区县行政区划代码"),
            "status" to setOf("status", "状态"), "priority" to setOf("priority", "优先级"),
            "project_name" to setOf("项目名称", "project name"),
            "project_type" to setOf("项目类型", "project type"),
            "project_lead" to setOf("项目主管", "project lead"),
            "project_description" to setOf("项目描述", "project description"),
            "project_url" to setOf("项目URL", "project url"),
            "resolution" to setOf("解决结果", "resolution"),
            "creator" to setOf("创建人", "creator"),
            "last_viewed_at" to setOf("最近查看的", "last viewed"),
            "resolved_at" to setOf("解决", "resolved"),
            "affected_version" to setOf("影响版本", "affected version"),
            "fix_version" to setOf("解决版本", "fix version"),
            "due_date" to setOf("到期日", "due date"),
            "votes" to setOf("投票", "votes"),
            "label_1" to setOf("标签", "label", "labels"),
            "label_2" to setOf("标签#2", "label#2", "labels#2"),
            "label_3" to setOf("标签#3", "label#3", "labels#3"),
            "environment" to setOf("环境", "environment"),
            "watchers" to setOf("关注人", "watchers"),
            "worklog_time" to setOf("记录工作时间", "worklog time"),
            "original_estimate_raw" to setOf("原预估时间", "original estimate"),
            "remaining_estimate_raw" to setOf("剩余时间", "remaining estimate"),
            "time_spent_raw" to setOf("耗费时间", "time spent"),
            "work_ratio" to setOf("工作比率", "work ratio"),
            "sigma_original_estimate" to setOf("Σ 原预估时间", "sigma original estimate"),
            "sigma_remaining_estimate" to setOf("Σ 预估剩余时间", "sigma remaining estimate"),
            "sigma_time_spent" to setOf("Σ 耗费时间", "sigma time spent"),
            "security_level" to setOf("安全级别", "security level"),
            "inward_duplicate_link" to setOf("事务的内向链接(Duplicate)", "事务的内向链接 (Duplicate)", "inward duplicate"),
            "outward_duplicate_link" to setOf("向外链接的问题(Duplicate)", "outward duplicate"),
            "comment_summary" to setOf("评论摘要", "comment summary", "comments summary"),
            "application_type" to setOf("application type", "申请类型"),
            "assignee" to setOf("assignee", "经办人", "负责人"), "reporter" to setOf("reporter", "报告人"),
            "author" to setOf("author", "作者"), "url" to setOf("url", "链接"),
            "project_key" to setOf("project key", "project_key", "项目key", "项目关键字"),
            "repair_region" to setOf("repair region", "repair_region", "维修区域", "报修区域"),
            "quality_province" to setOf("省份"),
            "quality_city" to setOf("城市"),
            "quality_county" to setOf("区县"),
            "customer_address" to setOf("客户地址"),
            "district" to setOf("district", "区域"), "exchange_request" to setOf("exchange request", "换货申请", "换机需求"),
            "return_reason" to setOf("return reason", "退货原因", "退换机原因"),
            "fault_confirmed" to setOf("故障现象确认"),
            "shipping_date" to setOf("shipping date", "发货日期", "实际出货日期"),
            "return_order_no" to setOf("商返单号", "return order no"),
            "product_name" to setOf("产品名称(主数据)", "产品名称", "product name"),
            "product_model" to setOf("产品型号", "product model"),
            "device_sn" to setOf("机身号码", "设备序列号", "device sn"),
            "dispatch_date" to setOf("派单日期", "dispatch date"),
            "activation_date" to setOf("激活日期（主数据）", "激活日期(主数据)", "激活日期", "activation date"),
            "original_id" to setOf("original id", "原始id", "doc_id"),
            "uid" to setOf("uid"),
            "source_platform" to setOf("source platform", "来源平台", "source"),
            "pub_type" to setOf("pub type", "发布类型", "pub_type_name"),
            "section" to setOf("section", "板块"), "folder_name" to setOf("folder_name"),
            "origin_message" to setOf("origin_message", "原始消息"),
            "keyword" to setOf("keyword", "关键词"),
            "emotion" to setOf("emotion", "emotion type", "emotion_type", "情感类型", "红黑榜类型"),
            "push_time" to setOf("push_time"),
            "redblack_create_time" to setOf("create_time"),
            "evaluation_object" to setOf("评价对象", "evaluation object"),
            "evaluation_dimension" to setOf("评价维度", "evaluation dimension"),
            "impact_level" to setOf("影响等级", "impact level"),
            "data_period" to setOf("数据周期", "所属周期", "data period"),
            "source_department" to setOf("来源部门", "source department"),
            "source_role" to setOf("来源角色", "source role"),
            "cover_image_url" to setOf("cover_image_url"),
            "image_urls" to setOf("image_urls"),
            "video_urls" to setOf("video_urls"),
            "is_deleted" to setOf("is_deleted", "is deleted", "是否删除"),
            "source_url" to setOf("source url", "业务链接", "来源链接"),
            "attachment_urls" to setOf("attachment urls", "附件链接", "图片视频链接"),
            "attachment_sha256" to setOf("attachment sha256", "附件sha256"),
            "attachment_size" to setOf("attachment size", "附件大小")
        )
    }
}

data class ImportUploadResult(val batchId: String, val status: String, val duplicateResult: String, val totalRows: Int,
    val availableSheets: List<String>, val headers: List<String>, val preview: List<List<String>>, val recommendedMapping: Map<String, String>,
    val templateId: Long? = null, val templateVersion: String? = null, val channelBoundary: ChannelBoundaryResult? = null,
    val contentSha256:String?=null,val normalizedContentSha256:String?=null,val sheets:List<ImportSheetView> = emptyList(),
    val mappingConfidence:Map<String,Double> = emptyMap(),val simulatedSdk:Boolean=false,
    val mappingMode:String="REVIEW_REQUIRED",val mappingDecisionReason:String?=null,val autoQueued:Boolean=false)
data class ImportSheetView(val name:String,val dataRows:Int,val columns:Int)
data class ImportBatchView(val batchId: String, val channel: String, val status: String, val totalRows: Int,
    val successRows: Int, val failedRows: Int, val skippedRows: Int, val reviewedRows: Int,
    val createdAt: LocalDateTime, val completedAt: LocalDateTime?,val dataDateFrom:LocalDate?=null,val dataDateTo:LocalDate?=null,
    val retryCount:Int=0,val reconciliationStatus:String="PENDING",val canRollback:Boolean=false,val canRetry:Boolean=false,
    val rollbackDeadline:LocalDateTime?=null,val rawRetentionUntil:LocalDateTime?=null,
    val insertedRows:Int=0,val updatedRows:Int=0,val sourceDataUpdatedAt:LocalDateTime?=null,
    val ingestMethod:String="FILE",val triggerType:String?=null,
    val mappingVersion:String?=null,val enumMappingVersion:String?=null,
    val governanceStatus:String="ACTIVE",val supersededByBatchId:Long?=null,
    val governanceReason:String?=null,val governedAt:LocalDateTime?=null)
data class ImportRowError(val rowNumber: Int?, val status: String?, val errorCode: String?, val summary: String?, val suggestion: String?)
data class ImportBatchActions(val canRollback:Boolean,val rollbackDeadline:LocalDateTime,val canRetry:Boolean)
data class ImportBatchDetail(val batch: ImportBatchView, val errors: List<ImportRowError>, val qualityMetrics: Map<String, Any?>,
    val actions:ImportBatchActions?=null,val progressPercent:Int=0,val lastErrorCode:String?=null,val lastErrorDetail:String?=null)
data class ImportBatchPage(val records:List<ImportBatchView>,val total:Long,val page:Int,val size:Int)
