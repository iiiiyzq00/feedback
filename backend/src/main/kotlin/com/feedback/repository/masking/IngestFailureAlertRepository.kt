package com.feedback.repository.masking

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ServiceException
import com.feedback.mapper.masking.*
import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.springframework.stereotype.Repository

@Repository
class IngestFailureAlertRepository(
    private val rules:IngestFailureRuleMapper,
    private val instances:IngestFailureInstanceMapper,
    private val occurrences:IngestFailureOccurrenceMapper,
    private val incidents:IngestFailureIncidentMapper,
    private val links:IngestFailureIncidentLinkMapper
) {
    fun activeRule()=rules.findActive()
    fun insertInstance(row:IngestAlertInstancePo)=row.also{if(instances.insert(it)!=1||it.id<=0) failed("INGEST_ALERT_INSTANCE_INSERT_FAILED")}
    fun insertOccurrence(row:IngestAlertOccurrencePo)=row.also{if(occurrences.insert(it)!=1||it.id<=0) failed("INGEST_ALERT_OCCURRENCE_INSERT_FAILED")}
    fun insertIncident(row:IngestIncidentPo)=row.also{if(incidents.insert(it)!=1||it.id<=0) failed("INGEST_INCIDENT_INSERT_FAILED")}
    fun linkIncident(incidentId:Long,instanceId:Long){if(links.insert(incidentId,instanceId)!=1) failed("INGEST_INCIDENT_LINK_INSERT_FAILED")}
    private fun failed(reasonCode:String):Nothing=throw ServiceException(ErrorCode.INTERNAL_ERROR,reasonCode)
}
