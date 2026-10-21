package com.feedback.repository.alert

import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class GovernanceContractRequirementView(val contractCode:String,val status:String?,val expiresAt:LocalDateTime?,val ready:Boolean)
@Repository class AlertProductionGovernanceRepository(private val configs:AlertProductionConfigMapper,private val rules:AlertProductionRuleMapper,private val acceptance:AlertProductionAcceptanceMapper,private val users:AlertProductionUserMapper,private val audits:AlertProductionAuditMapper){
 fun expire()=configs.expireContracts()+configs.expireProviders();fun contracts()=configs.contracts()
 fun saveContract(code:String,schema:String,owner:String,source:String,fresh:Int,quality:Double){ensureAlertRepositoryState(configs.saveContract(code,schema,owner,source,fresh,quality)>=1)}
 fun validateContract(code:String,sha:String,summary:String,actor:Long,expires:LocalDateTime)=configs.validateContract(code,sha,summary,actor,expires)==1
 fun approveContract(code:String,actor:Long)=configs.approveContract(code,actor)==1;fun suspendContract(code:String)=configs.suspendContract(code)==1
 fun providers()=configs.providers()
 fun validateProvider(channel:String,provider:String,environment:String,cap:String,quota:Long,sha:String,reference:String,actor:Long,expires:LocalDateTime){if(configs.updateProvider(channel,provider,environment,cap,quota,sha,reference,actor,expires)==0)ensureAlertRepositoryState(configs.insertProvider(channel,provider,environment,cap,quota,sha,reference,actor,expires)==1)}
 fun approveProvider(id:Long,actor:Long)=configs.approveProvider(id,actor)==1;fun suspendProvider(id:Long)=configs.suspendProvider(id)==1
 fun requirements(code:String):List<GovernanceContractRequirementView>{val required=configs.requirements(code).map{it.contractCode};if(required.isEmpty())return emptyList();val rows=configs.contractStates(required.distinct());ensureAlertRepositoryState(rows.groupBy{it.contractCode}.values.none{it.size>1}){"ALERT_CONTRACT_DUPLICATED"};val states=rows.associateBy{it.contractCode};return required.map{key->val state=states[key];GovernanceContractRequirementView(key,state?.status,state?.expiresAt,state?.ready==true)}}
 fun implement(code:String,sha:String,actor:Long)=configs.implementRule(code,sha,actor)==1
 fun rule(id:Long)=rules.find(id);fun lockRule(id:Long)=rules.lock(id);fun catalog(code:String)=configs.catalog(code)
 fun missingProviders(channels:Set<String>):List<String>{val external=channels.filter{it!="IN_APP"};if(external.isEmpty())return emptyList();val ready=configs.readyProviders(external).map{it.channel}.toSet();return external.filter{it !in ready}}
 fun latestAcceptance(ruleId:Long)=acceptance.latest(ruleId)
 fun eligibleOncall(team:String):Int{val ids=(configs.oncallUsers(team)+configs.primaryUsers(team)).map{it.userId}.distinct();return if(ids.isEmpty())0 else users.active(ids).map{it.userId}.distinct().size}
 fun retireActive(code:String)=rules.retireActive(code);fun activate(id:Long,enabled:Boolean)=rules.activate(id,enabled)==1;fun disable(id:Long)=rules.disable(id)
 fun audit(actor:Long,operation:String,id:String,detail:String){ensureAlertRepositoryState(audits.insert(actor,operation,id,detail)==1)}
 fun autoDisabled(id:Long,detail:String){ensureAlertRepositoryState(audits.insertSystem(id.toString(),detail)==1){"ALERT_AUTO_DISABLE_AUDIT_WRITE_FAILED:$id"}}
}
