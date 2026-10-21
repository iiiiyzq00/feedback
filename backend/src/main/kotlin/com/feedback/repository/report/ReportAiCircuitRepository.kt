package com.feedback.repository.report
import com.feedback.mapper.report.*
import com.feedback.model.po.report.*
import com.feedback.model.query.report.*
import org.springframework.stereotype.Repository
@Repository class ReportAiCircuitRepository(private val configs:ReportAiCircuitConfigMapper,private val audits:ReportAiCircuitAuditMapper,private val incidents:ReportAiCircuitIncidentMapper){
 fun ensure(){configs.ensure()};fun lock()=configs.lock();fun probeDue()=configs.probeDue()==true;fun halfOpen(){if(configs.halfOpen()!=1) reportRepositoryFailed("REPORT_AI_CIRCUIT_HALF_OPEN_FAILED")};fun close(){if(configs.close()!=1) reportRepositoryFailed("REPORT_AI_CIRCUIT_CLOSE_FAILED")}
 fun failure(failures:Int,code:String,state:String,open:Boolean){if(configs.failure(failures,code,state,open)!=1) reportRepositoryFailed("REPORT_AI_CIRCUIT_FAILURE_UPDATE_FAILED")}
 fun audit(operation:String,detail:String){
  if(audits.insert(operation,detail)!=1) reportRepositoryFailed("REPORT_AI_CIRCUIT_AUDIT_FAILED")
  if(operation=="REPORT_AI_CIRCUIT_OPEN"&&incidents.findOpenId()==null&&incidents.open()!=1) reportRepositoryFailed("REPORT_AI_INCIDENT_OPEN_FAILED")
  if(operation=="REPORT_AI_RECOVERED")incidents.resolve()
 }
}
