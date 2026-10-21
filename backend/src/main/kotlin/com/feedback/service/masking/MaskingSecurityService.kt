package com.feedback.service.masking

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import com.feedback.service.imports.ImportRawCrypto
import com.feedback.service.imports.ImportBatchService
import com.feedback.security.sdk.TransportKeyService
import com.feedback.service.station.StationRawPayloadService
import com.feedback.audit.BusinessAuditEvent
import com.feedback.audit.BusinessAuditService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Base64
import com.feedback.repository.masking.MaskingSecurityRepository

data class MaskingRetryRequest(
    val receiptIds: List<String> = emptyList(),
    val feedbackPublicIds: List<String> = emptyList(),
    val force: Boolean = false
)
data class ContactDecryptRequest(val workOrderRef: String, val purpose: String)
data class RawInspectionRequest(val taskId: String, val purpose: String)
data class RawReviewRequest(val taskId: String, val decision: String, val reason: String,
                            val revisedTitle: String? = null, val revisedDescription: String? = null)
data class SdkExceptionCloseRequest(val receiptIds: List<String>, val reason: String, val note: String)

@Service
class MaskingSecurityService(
    private val repository: MaskingSecurityRepository,
    private val contacts: ContactStorageCrypto,
    private val mapper: ObjectMapper,
    private val importRawCrypto: ImportRawCrypto,
    private val transportKeys: TransportKeyService,
    private val envelopeCrypto: EnvelopeCrypto,
    private val stationRawPayloads: StationRawPayloadService,
    private val imports: ImportBatchService? = null,
    private val masking: MaskingPipeline? = null,
    private val businessAudits: BusinessAuditService? = null
) {
    @Transactional
    fun reviewRaw(request: RawReviewRequest, ip: String?): Map<String, Any?> {
        val principal = principal()
        if (PlatformPermissions.REVIEW_MASKING_RAW !in principal.permissions) maskingForbidden("MASKING_RAW_REVIEW_PERMISSION_REQUIRED")
        val parts = request.taskId.trim().split(":", limit = 2)
        if(parts.size != 2 || parts[0] !in setOf("STATION", "API", "FILE", "TABLE")) maskingInvalid("MASKING_REVIEW_TASK_TYPE_UNSUPPORTED")
        val rowId = parts[1].toLongOrNull() ?: maskingInvalid("MASKING_TASK_ID_INVALID")
        return try {
            val result = if (parts[0] == "STATION") reviewStation(rowId, request, principal.userId) else
                (imports ?: maskingFailed("MASKING_REVIEW_SERVICE_UNAVAILABLE")).processManualPrivacyReview(rowId, request.decision.uppercase(), request.reason,
                    principal.userId, request.revisedTitle, request.revisedDescription)
            audit("MASKING_MANUAL_REVIEW", "MASKING_TASK", mapOf(
                "taskId" to request.taskId, "decision" to request.decision.uppercase(), "reason" to request.reason.trim(),
                "ruleVersion" to result["ruleVersion"], "recheckStatus" to result["recheckStatus"],
                "findingTypes" to result["findingTypes"], "released" to result["released"],
                "authorizationBasis" to "RBAC_PERMISSION", "requiredPermission" to "action:review_masking_raw"
            ), ip)
            result
        } catch (error: Exception) {
            audit("MASKING_MANUAL_REVIEW", "MASKING_TASK", mapOf(
                "taskId" to request.taskId, "decision" to request.decision.uppercase(), "outcome" to "DENIED",
                "errorCode" to (error.message?.substringBefore(':') ?: error.javaClass.simpleName),
                "authorizationBasis" to "RBAC_PERMISSION"
            ), ip, "DENIED")
            throw error
        }
    }
    fun quarantine(page: Int, size: Int, view: String): Map<String, Any> {
        if(page < 1 || size !in 1..100) maskingInvalid("MASKING_QUARANTINE_PAGE_INVALID")
        val normalizedView=view.uppercase();if(normalizedView !in setOf("ACTIVE","HISTORY")) maskingInvalid("SDK_EXCEPTION_VIEW_INVALID")
        val (records,total) = repository.quarantine(normalizedView,size,(page-1L)*size)
        return mapOf("records" to records, "total" to total, "page" to page, "size" to size,"view" to normalizedView,
            "securityBoundary" to "仅返回诊断元数据，不返回密文、明文或安全预览原值")
    }

    @Transactional
    fun retry(request: MaskingRetryRequest): Map<String, Int> {
        if(request.receiptIds.size + request.feedbackPublicIds.size !in 1..100) maskingInvalid("MASKING_RETRY_SIZE_INVALID")
        var queued = 0
        var skipped = 0
        request.receiptIds.distinct().forEach { external ->
            val receipt = decodeReceipt(external)
            val changed = repository.retrySdk(receipt)
            if (changed == 1) queued++ else skipped++
        }
        request.feedbackPublicIds.distinct().forEach { publicId ->
            if(!publicId.matches(Regex("^[0-9a-fA-F-]{32,36}$"))) maskingInvalid("FEEDBACK_PUBLIC_ID_INVALID")
            val changed = repository.retryImport(publicId)
            if (changed > 0) queued += changed else skipped++
        }
        audit("MASKING_RETRY", "MASKING_QUEUE", mapOf("queued" to queued, "skipped" to skipped, "force" to request.force))
        return mapOf("queued" to queued, "skipped" to skipped)
    }

    @Transactional
    fun closeSdkExceptions(request:SdkExceptionCloseRequest):Map<String,Int>{
        val principal = principal()
        if (principal.roles.intersect(setOf(RoleCode.ADMIN, RoleCode.OPERATOR)).isEmpty())
            maskingForbidden("MASKING_EXCEPTION_CLOSE_ROLE_REQUIRED")
        if(request.receiptIds.size !in 1..100) maskingInvalid("SDK_EXCEPTION_CLOSE_SIZE_INVALID")
        val allowed=setOf("KEY_OR_CIPHERTEXT_INCOMPATIBLE","CLIENT_RESUBMIT_REQUESTED","HISTORICAL_TEST_DATA","DUPLICATE","NO_FURTHER_ACTION","OTHER")
        val reason=request.reason.uppercase();if(reason !in allowed) maskingInvalid("SDK_EXCEPTION_CLOSE_REASON_INVALID")
        val note=request.note.trim();if(note.length !in 5..500) maskingInvalid("SDK_EXCEPTION_CLOSE_NOTE_INVALID")
        var closed=0;var skipped=0;val actor=principal.userId
        request.receiptIds.distinct().forEach{external->
            val changed=repository.closeSdk(decodeReceipt(external),reason,note,actor)
            if(changed==1)closed++ else skipped++
        }
        audit("SDK_EXCEPTION_CLOSE","SDK_EXCEPTION",mapOf("closed" to closed,"skipped" to skipped,"reason" to reason,"note" to note))
        return mapOf("closed" to closed,"skipped" to skipped)
    }

    @Transactional
    fun decrypt(sourceId: Long, request: ContactDecryptRequest, ip: String?): Map<String, Any> {
        val principal = principal()
        if (RoleCode.ADMIN !in principal.roles) maskingForbidden("CONTACT_DECRYPT_ADMIN_REQUIRED")
        val workOrder = request.workOrderRef.trim()
        val purpose = request.purpose.trim()
        if(!workOrder.matches(Regex("^[A-Za-z0-9_.:/-]{3,100}$"))) maskingInvalid("WORK_ORDER_REFERENCE_INVALID")
        if(purpose.length !in 10..500) maskingInvalid("CONTACT_ACCESS_PURPOSE_INVALID")
        val rows = repository.contacts(sourceId)
        if (rows.isEmpty()) maskingNotFound("AUTHORIZED_CONTACT_NOT_FOUND")
        val values = rows.map { row -> mapOf(
            "type" to row.identityType,
            "value" to contacts.decrypt(row.contactCiphertext, row.contactTag, row.contactNonce)
        ) }
        val types = values.map { it["type"] }.distinct()
        audit("CONTACT_DECRYPT", "FEEDBACK_CONTACT", mapOf("sourceId" to sourceId,
            "workOrderRef" to workOrder,"purpose" to purpose,"contactTypes" to types),ip)
        return mapOf("sourceId" to sourceId, "contacts" to values, "responseCacheable" to false,
            "usageRestriction" to "仅限所填工单和用途，禁止复制到非授权系统")
    }

    @Transactional
    fun inspectRaw(request: RawInspectionRequest, ip: String?): Map<String, Any?> {
        val principal = principal()
        if (PlatformPermissions.INSPECT_MASKING_RAW !in principal.permissions) maskingForbidden("MASKING_RAW_INSPECTION_PERMISSION_REQUIRED")
        val purpose = request.purpose.trim()
        if(purpose.length !in 10..500) maskingInvalid("RAW_INSPECTION_PURPOSE_INVALID")
        val parts=request.taskId.trim().split(":",limit=2)
        if(parts.size!=2 || parts[0] !in setOf("SDK","STATION","API","FILE","TABLE")) maskingInvalid("MASKING_TASK_ID_INVALID")
        val inspected=when(parts[0]) {
            "SDK" -> mapOf("rawContent" to inspectSdkRaw(parts[1]), "reviewEvidence" to emptyList<Any>())
            "STATION" -> inspectStationRaw(parts[1])
            else -> inspectTableRaw(parts[1])
        }
        audit("MASKING_RAW_INSPECT", "MASKING_TASK", mapOf("taskId" to request.taskId,
            "authorizationBasis" to "RBAC_PERMISSION","requiredPermission" to "action:inspect_masking_raw",
            "purpose" to purpose,"sourceType" to parts[0]),ip)
        return mapOf("taskId" to request.taskId,"sourceType" to parts[0],"rawContent" to inspected["rawContent"],
            "reviewEvidence" to inspected["reviewEvidence"], "ruleVersion" to masking?.activeVersion(),
            "responseCacheable" to false,"authorizationBasis" to "RBAC_PERMISSION",
            "usageRestriction" to "仅限当前人工判定用途；关闭判定窗口后清除，禁止复制到非授权系统")
    }

    private fun inspectSdkRaw(receiptHex:String):Any{
        val row=repository.sdkRaw(decodeReceipt(receiptHex))
            ?:maskingNotFound("MASKING_RAW_EXPIRED_OR_UNAVAILABLE")
        val dataKey=transportKeys.unwrapDataKey(row.encryptedDataKey)
        val plain=try{envelopeCrypto.decrypt(row.payloadCiphertext,row.payloadTag,row.payloadNonce,dataKey)}finally{dataKey.fill(0)}
        return try{mapper.readTree(plain)}finally{plain.fill(0)}
    }

    private fun inspectTableRaw(rowId:String):Map<String,Any>{
        val id=rowId.toLongOrNull()?:maskingInvalid("MASKING_TASK_ID_INVALID")
        val row=repository.tableRaw(id)
            ?:maskingNotFound("MASKING_RAW_EXPIRED_OR_UNAVAILABLE")
        val plain=importRawCrypto.decrypt(row.content,row.tag,row.nonce)
        return try {
            val rawNode=mapper.readTree(plain)
            val mapping=mapper.readTree(row.mappingConfigJson)
            val pipeline=masking ?: return mapOf("rawContent" to rawNode,"reviewEvidence" to emptyList<Any>())
            val evidence=listOf("title","description").mapNotNull { field ->
                val sourceColumn=mapping.path(field).asText().takeIf(String::isNotBlank) ?: return@mapNotNull null
                val original=rawNode.path(sourceColumn).asText("")
                if(original.isBlank()) return@mapNotNull null
                val result=pipeline.mask(original,"controlled_review_${field}_$id",recordAudit=false)
                val ranges=result.findings.flatMap { finding ->
                    val positions=mutableListOf<Map<String,Any>>();var from=0
                    while(from<original.length){val start=original.indexOf(finding.originalValue,from);if(start<0)break
                        positions+=mapOf("type" to finding.type,"start" to start,"end" to (start+finding.originalValue.length));from=start+maxOf(1,finding.originalValue.length)}
                    positions
                }.distinctBy { listOf(it["type"],it["start"],it["end"]) }
                val reasons=buildList {
                    if(result.findingTypes.isNotEmpty())add("RULE_OR_NER_FINDING")
                    if(result.residualHighRiskDigits)add("RESIDUAL_HIGH_RISK_DIGITS")
                    if(!result.nerAvailable)add("NER_UNAVAILABLE")
                }
                mapOf("field" to field,"sourceColumn" to sourceColumn,"status" to result.maskingStatus,
                    "candidate" to result.maskedText,"findingTypes" to result.findingTypes,"reasons" to reasons,
                    "matchedRanges" to ranges,"requiresAttention" to (result.maskingStatus!="PASS" || result.findingTypes.isNotEmpty()))
            }
            mapOf("rawContent" to rawNode,"reviewEvidence" to evidence)
        } finally { plain.fill(0) }
    }

    private fun inspectStationRaw(sourceId: String): Map<String, Any> {
        val id = sourceId.toLongOrNull() ?: maskingInvalid("MASKING_TASK_ID_INVALID")
        if (!repository.stationReviewable(id)) maskingNotFound("MASKING_TASK_NOT_REVIEWABLE")
        val rawNode = stationRawPayloads.inspect(id)
        val pipeline = masking ?: return mapOf("rawContent" to rawNode, "reviewEvidence" to emptyList<Any>())
        val evidence = listOf("title", "content", "description").mapNotNull { field ->
            val original = rawNode.path(field).asText("")
            if (original.isBlank()) return@mapNotNull null
            val result = pipeline.mask(original, "controlled_station_review_${field}_$id", recordAudit = false)
            val ranges = result.findings.flatMap { finding ->
                val positions = mutableListOf<Map<String, Any>>(); var from = 0
                while (from < original.length) {
                    val start = original.indexOf(finding.originalValue, from); if (start < 0) break
                    positions += mapOf("type" to finding.type, "start" to start, "end" to start + finding.originalValue.length)
                    from = start + maxOf(1, finding.originalValue.length)
                }; positions
            }.distinctBy { listOf(it["type"], it["start"], it["end"]) }
            mapOf("field" to field, "sourceColumn" to field, "status" to result.maskingStatus,
                "candidate" to result.maskedText, "findingTypes" to result.findingTypes,
                "reasons" to buildList {
                    if (result.findingTypes.isNotEmpty()) add("RULE_OR_NER_FINDING")
                    if (result.residualHighRiskDigits) add("RESIDUAL_HIGH_RISK_DIGITS")
                    if (!result.nerAvailable) add("NER_UNAVAILABLE")
                }, "matchedRanges" to ranges,
                "requiresAttention" to (result.maskingStatus != "PASS" || result.findingTypes.isNotEmpty()))
        }
        val reviewContent = mapper.createObjectNode().apply {
            put("schemaVersion", rawNode.path("schemaVersion").asText("station-raw-v1"))
            put("sourceMode", rawNode.path("sourceMode").asText("UNKNOWN"))
            rawNode.path("sourceRecordId").asText(null)?.let { put("sourceRecordId", it) }
            put("title", rawNode.path("title").asText(""))
            put("content", rawNode.path("content").asText(""))
            put("description", rawNode.path("description").asText(""))
        }
        return mapOf("rawContent" to reviewContent, "reviewEvidence" to evidence)
    }

    private fun reviewStation(sourceId: Long, request: RawReviewRequest, actor: Long): Map<String, Any?> {
        val decision = request.decision.uppercase()
        if(decision !in setOf("FALSE_POSITIVE_RELEASE", "REMASK_AND_RELEASE", "KEEP_QUARANTINED")) maskingInvalid("MASKING_REVIEW_DECISION_INVALID")
        if(request.reason.trim().length !in 5..500) maskingInvalid("MASKING_REVIEW_REASON_INVALID")
        inspectStationRaw(sourceId.toString())
        if (decision == "KEEP_QUARANTINED") {
            repository.keepStation(sourceId,request.reason.trim())
            return mapOf("taskId" to "STATION:$sourceId", "decision" to decision, "released" to false,
                "ruleVersion" to masking?.activeVersion(), "recheckStatus" to "REVIEW_NEEDED", "findingTypes" to emptyList<String>())
        }
        var title: String? = null; var description: String? = null; val findings = linkedSetOf<String>()
        if (decision == "REMASK_AND_RELEASE") {
            val pipeline = masking ?: maskingFailed("MASKING_REVIEW_SERVICE_UNAVAILABLE")
            val raw = stationRawPayloads.inspect(sourceId)
            val titleResult = pipeline.mask(request.revisedTitle?.takeIf(String::isNotBlank) ?: raw.path("title").asText(""),
                "station_manual_title_$sourceId", recordAudit = false)
            val bodyResult = pipeline.mask(request.revisedDescription?.takeIf(String::isNotBlank) ?: raw.path("content").asText(""),
                "station_manual_content_$sourceId", recordAudit = false)
            findings += titleResult.findingTypes; findings += bodyResult.findingTypes
            if(titleResult.maskingStatus != "PASS" || bodyResult.maskingStatus != "PASS") maskingInvalid("MASKING_REVIEW_RECHECK_NOT_PASS")
            title = titleResult.maskedText; description = bodyResult.maskedText
        }
        repository.releaseStation(sourceId,title,description)
        repository.completeStation(sourceId)
        return mapOf("taskId" to "STATION:$sourceId", "decision" to decision, "released" to true,
            "reviewedBy" to actor, "ruleVersion" to masking?.activeVersion(), "recheckStatus" to "PASS", "findingTypes" to findings.toList())
    }

    private fun decodeReceipt(value: String): ByteArray {
        val clean = value.trim()
        val decoded = when {
            clean.matches(Regex("^[0-9a-fA-F]{32}$")) -> clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            else -> runCatching { Base64.getUrlDecoder().decode(clean) }.getOrElse { maskingInvalid("RECEIPT_ID_INVALID") }
        }
        if(decoded.size != 16) maskingInvalid("RECEIPT_ID_INVALID")
        return decoded
    }

    private fun principal() = SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
        ?: maskingUnauthorized("AUTHENTICATION_REQUIRED")

    private fun audit(operation: String, target: String, detail: Map<String, Any?>, ip: String? = null,
                      outcome:String="SUCCESS") {
        businessAudits?.append(BusinessAuditEvent(principal().userId,operation,"SECURITY",outcome,
            target,detail["taskId"]?.toString()?:target,java.util.UUID.randomUUID().toString(),ip,detail))?.let{return}
        repository.audit(principal().userId,operation,outcome,target,mapper.writeValueAsString(detail),ip?.take(50))
    }
}
