package com.feedback.service.imports

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.repository.imports.ImportQualityRepository
import com.feedback.model.po.imports.ImportQualityUpdatePo
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

data class FeedbackQualityInput(
    val sourceRecordId: String?,
    val description: String?,
    val mappingErrors: List<String>,
    val maskingStatus: String,
    val sourceEventTime: LocalDateTime?
)

@Service
class ImportQualityService(private val repository: ImportQualityRepository, private val mapper: ObjectMapper,
    private val thresholdAlerts:DataQualityThresholdAlertPublisher?=null,
    private val thresholdPolicy:DataQualityThresholdPolicy?=null) {
    fun score(input: FeedbackQualityInput): BigDecimal {
        val completeness = listOf(input.sourceRecordId, input.description).count { !it.isNullOrBlank() } / 2.0
        val mapping = (1.0 - input.mappingErrors.size.coerceAtMost(6) / 6.0)
        val masking = if (input.maskingStatus == "PASS") 1.0 else 0.0
        val temporal = if (input.sourceEventTime != null && input.sourceEventTime.year >= 2000 &&
            input.sourceEventTime.isBefore(LocalDateTime.now().plusHours(24))) 1.0 else 0.0
        return decimal(completeness * 0.40 + mapping * 0.25 + masking * 0.25 + temporal * 0.10)
    }

    fun snapshot(batchInternalId: Long): Map<String, Any?> {
        val facts = repository.facts(batchInternalId)
        val batch = facts.batch
        val counts = facts.rows
        val total = batch.totalRows
        fun count(name: String) = when(name) {
            "completed" -> counts.completed; "importedSuccess" -> counts.importedSuccess
            "reviewed" -> counts.reviewed; "invalidRows" -> counts.invalidRows
            "duplicateRows" -> counts.duplicateRows; "staleRows" -> counts.staleRows
            "requiredErrors" -> counts.requiredErrors; "timeErrors" -> counts.timeErrors
            "enumErrors" -> counts.enumErrors; else -> importAbort("IMPORT_QUALITY_COUNT_UNKNOWN:$name")
        }
        val denominator = total.coerceAtLeast(1).toDouble()
        val invalid = count("invalidRows")
        val reviewed = count("reviewed")
        val completeness = (total - count("requiredErrors")).coerceAtLeast(0) / denominator
        val enumValid = (total - count("enumErrors")).coerceAtLeast(0) / denominator
        val timeValid = (total - count("timeErrors")).coerceAtLeast(0) / denominator
        val recordCount=facts.recordCount
        fun factRate(value:Int)=if(recordCount==0)0.0 else value.toDouble()/recordCount
        val appMapping=factRate(facts.appMapped)
        val moduleMapping=factRate(facts.moduleMapped)
        val versionMapping=factRate(facts.versionMapped)
        val mappingSuccess=(appMapping+moduleMapping+versionMapping)/3.0
        val maskingPass = (total - invalid - reviewed).coerceAtLeast(0) / denominator
        val duplicateRate = count("duplicateRows") / denominator
        val invalidRate = invalid / denominator
        val importSuccess=count("importedSuccess")/denominator
        val crossDuplicateRate=if(recordCount==0)0.0 else facts.crossDuplicates.toDouble()/recordCount
        val attachmentTotal = facts.attachment.attachmentTotal
        val attachmentRate = if (attachmentTotal == 0) null else
            facts.attachment.attachmentAvailable / attachmentTotal.toDouble()
        val score = decimal(completeness*.20+timeValid*.10+enumValid*.10+mappingSuccess*.20+maskingPass*.20+importSuccess*.15+(1-crossDuplicateRate)*.05)
        val limits=thresholdPolicy?.current()?:DataQualityThresholds()
        val thresholdAlert=completeness<limits.requiredCompletenessMin||duplicateRate>limits.primaryKeyDuplicateMax||
            timeValid<limits.timeValidMin||enumValid<limits.enumValidMin||mappingSuccess<limits.mappingMin||
            maskingPass<limits.maskingPassMin||importSuccess<limits.importSuccessMin||
            crossDuplicateRate>limits.crossChannelDuplicateMax||reviewed>limits.quarantineMax
        val detail = linkedMapOf<String, Any>(
            "totalRows" to total, "completedRows" to count("completed"), "reviewedRows" to reviewed,
            "invalidRows" to invalid, "staleRows" to count("staleRows"),"enumErrorRows" to count("enumErrors"),
            "metricVersion" to VERSION, "formula" to "required .20 + time .10 + enum .10 + mapping .20 + masking .20 + import .15 + cross-channel .05"
        )
        repository.save(ImportQualityUpdatePo(batchInternalId,VERSION,total,count("completed"),decimal(completeness),
            decimal(mappingSuccess),decimal(maskingPass),decimal(duplicateRate),decimal(invalidRate),decimal(timeValid),
            decimal(enumValid),decimal(appMapping),decimal(moduleMapping),decimal(versionMapping),decimal(importSuccess),
            decimal(crossDuplicateRate),invalid,reviewed,attachmentRate?.let(::decimal),score,
            mapper.writeValueAsString(detail),LocalDateTime.now()))
        if(thresholdAlert&&total>0)thresholdAlerts?.publish(batch.sourceChannel,batchInternalId,batchInternalId,score.toDouble())
        return detail + mapOf(
            "completenessRate" to decimal(completeness), "mappingSuccessRate" to decimal(mappingSuccess),
            "maskingPassRate" to decimal(maskingPass), "duplicateRate" to decimal(duplicateRate),
            "invalidRate" to decimal(invalidRate), "attachmentAvailableRate" to attachmentRate?.let(::decimal),
            "timeValidRate" to decimal(timeValid),"enumValidRate" to decimal(enumValid),
            "appMappingRate" to decimal(appMapping),"moduleMappingRate" to decimal(moduleMapping),"versionMappingRate" to decimal(versionMapping),
            "importSuccessRate" to decimal(importSuccess),"crossChannelSuspectedDuplicateRate" to decimal(crossDuplicateRate),
            "anomalyCount" to invalid,"quarantineCount" to reviewed,"thresholdStatus" to if(thresholdAlert)"ALERT" else "PASS","qualityScore" to score
        )
    }

    fun get(batchInternalId: Long): Map<String, Any?> = repository.stored(batchInternalId)?.let { row -> mapOf(
        "metricVersion" to row.metricVersion,"totalRows" to row.totalRows,"completedRows" to row.completedRows,
        "completenessRate" to row.completenessRate,"mappingSuccessRate" to row.mappingSuccessRate,
        "maskingPassRate" to row.maskingPassRate,"duplicateRate" to row.duplicateRate,"invalidRate" to row.invalidRate,
        "timeValidRate" to row.timeValidRate,"enumValidRate" to row.enumValidRate,"appMappingRate" to row.appMappingRate,
        "moduleMappingRate" to row.moduleMappingRate,"versionMappingRate" to row.versionMappingRate,
        "importSuccessRate" to row.importSuccessRate,"crossChannelSuspectedDuplicateRate" to row.crossChannelSuspectedDuplicateRate,
        "anomalyCount" to row.anomalyCount,"quarantineCount" to row.quarantineCount,
        "attachmentAvailableRate" to row.attachmentAvailableRate,"qualityScore" to row.qualityScore,"calculatedAt" to row.calculatedAt
    ) } ?: emptyMap()

    private fun decimal(value: Double) = BigDecimal.valueOf(value.coerceIn(0.0, 1.0)).setScale(6, RoundingMode.HALF_UP)

    companion object { const val VERSION = "quality-score-v1" }
}
