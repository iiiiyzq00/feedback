package com.feedback.service.normalization

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.po.audit.SystemAuditInsertPo
import com.feedback.repository.auth.SystemAuditMutationRepository
import org.springframework.stereotype.Service

@Service
class FieldConversionHistoryService(
    private val audits: SystemAuditMutationRepository,
    private val json: ObjectMapper
) {
    fun record(sourceId: Long, importRowId: Long, conversions: List<FieldConversion>, mappingVersion: String, normalizationVersion: String) {
        conversions.filter { it.fieldName in ALLOWED_FIELDS }.forEach { conversion ->
            audits.append(SystemAuditInsertPo(
                null, "FIELD_CONVERSION", "BUSINESS", "SUCCESS", "FEEDBACK", sourceId.toString(),
                json.writeValueAsString(linkedMapOf(
                    "importRowId" to importRowId, "fieldName" to conversion.fieldName,
                    "oldValue" to conversion.oldValue.take(500), "newValue" to conversion.newValue?.take(500),
                    "changeType" to conversion.changeType, "changedByType" to "SYSTEM",
                    "mappingVersion" to mappingVersion, "normalizationVersion" to normalizationVersion,
                    "changeReason" to conversion.reason.take(500)
                ))
            ))
        }
    }

    companion object {
        const val MAPPING_VERSION = "channel-map-v1.2"
        private val ALLOWED_FIELDS = setOf(
            "function_module", "problem_type", "severity_level", "issue_category", "user_sentiment", "processing_status"
        )
    }
}
