package com.feedback.repository.knowledge

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.knowledge.KnowledgeClosureAuditMapper
import com.feedback.mapper.knowledge.KnowledgeClosureIncidentMapper
import com.feedback.mapper.knowledge.KnowledgeClosureKnowledgeMapper
import com.feedback.model.po.knowledge.KnowledgeUsageAuditInsertPo
import com.feedback.model.po.knowledge.KnowledgeUsageStatisticsPo
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.math.RoundingMode

@Repository
class KnowledgeClosureRepository(
    private val knowledge: KnowledgeClosureKnowledgeMapper,
    private val incidents: KnowledgeClosureIncidentMapper,
    private val audits: KnowledgeClosureAuditMapper
) {
    fun isPublished(id: Long): Boolean = knowledge.countPublished(id) == 1
    fun incidentExists(id: Long): Boolean = incidents.countById(id) == 1
    fun adoptionExists(knowledgeId: Long, incidentId: Long): Boolean =
        audits.countAdoption(knowledgeId.toString(), correlationId(incidentId)) == 1

    fun insertUsage(row: KnowledgeUsageAuditInsertPo): Boolean = audits.insertUsage(row) == 1

    fun recalculate(id: Long) {
        val aggregate = audits.aggregate(id.toString())
        val rated = aggregate.effectiveCount + aggregate.ineffectiveCount
        val score = if (rated == 0L) null else BigDecimal.valueOf(aggregate.effectiveCount)
            .divide(BigDecimal.valueOf(rated), 6, RoundingMode.HALF_UP)
        if (knowledge.updateStatistics(id, aggregate, score) != 1) {
            throw ServiceException(ErrorCode.INTERNAL_ERROR, "KNOWLEDGE_STATISTICS_UPDATE_FAILED")
        }
    }

    fun statistics(id: Long): KnowledgeUsageStatisticsPo =
        knowledge.findStatistics(id) ?: throw ClientException(ErrorCode.NOT_FOUND, "KNOWLEDGE_NOT_FOUND")

    fun correlationId(incidentId: Long) = "INCIDENT:$incidentId"
}
