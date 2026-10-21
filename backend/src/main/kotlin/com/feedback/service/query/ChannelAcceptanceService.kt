package com.feedback.service.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.feedback.repository.query.ChannelAcceptanceRepository
import com.feedback.model.auth.RoleCode

data class ChannelViewConfigInput(val fields:List<Map<String,Any?>>,val reason:String)
data class ChannelAcceptanceInput(val status:String,val comment:String)

@Service
class ChannelAcceptanceService(private val repository: ChannelAcceptanceRepository,private val mapper:ObjectMapper) {
    private val channels=setOf("ALL","SDK","STATION_FEEDBACK","JIRA","QUALITY","RED_BLACK")
    private val required=setOf("sourceEventTime","problem","processingStatus","distributionStatus","ownerName","lastHandledAt","resolved")
    private fun channel(value:String)=value.uppercase().also{valid(it in channels,"CHANNEL_INVALID")}
    private fun manager(){if(principal().roles.none{it in setOf(RoleCode.ADMIN,RoleCode.OPERATOR)})forbidden("CHANNEL_GOVERNANCE_FORBIDDEN")}

    fun overview():Map<String,Any?> {
        val latest=repository.latest()
        val counts=repository.sourceCounts()
        return mapOf("channels" to channels.sorted().map{mapOf("sourceChannel" to it,"acceptance" to latest[it],"data" to if(it=="ALL") null else counts[it],"slaEnabled" to false)})
    }

    @Transactional fun accept(sourceChannel:String,input:ChannelAcceptanceInput):Map<String,Any?> { manager();val c=channel(sourceChannel);val status=input.status.uppercase();valid(status in setOf("PASSED","CONDITIONAL","REJECTED"),"CHANNEL_ACCEPTANCE_STATUS_INVALID");val comment=input.comment.trim();valid(comment.length in 2..1000,"CHANNEL_ACCEPTANCE_COMMENT_REQUIRED")
        repository.accept(c,status,comment,principal().userId);return mapOf("sourceChannel" to c,"status" to status) }

    fun config(sourceChannel:String):Map<String,Any?> { val c=channel(sourceChannel);val row=repository.published(c);val published=row?.let{linkedMapOf<String,Any?>("id" to it.id,"configVersion" to it.configVersion,"fields" to normalizeFields(it.fieldsJson),"reason" to it.reason,"createdAt" to it.createdAt,"publishedAt" to it.publishedAt)};return published?:mapOf("sourceChannel" to c,"configVersion" to 0,"fields" to defaults(c)) }

    @Transactional fun draft(sourceChannel:String,input:ChannelViewConfigInput):Map<String,Any?> { manager();val c=channel(sourceChannel);validate(input);val version=repository.nextVersion(c);repository.draft(c,version,mapper.writeValueAsString(input.fields),input.reason.trim(),principal().userId);return mapOf("sourceChannel" to c,"configVersion" to version,"status" to "DRAFT") }

    @Transactional fun publish(sourceChannel:String,version:Long):Map<String,Any?> { manager();val c=channel(sourceChannel);repository.publish(c,version,principal().userId);return mapOf("sourceChannel" to c,"configVersion" to version,"status" to "PUBLISHED") }

    @Transactional fun rollback(sourceChannel:String,version:Long,reason:String):Map<String,Any?> { manager();val c=channel(sourceChannel);val note=reason.trim();valid(note.length in 2..500,"CHANNEL_VIEW_REASON_REQUIRED");val next=repository.nextVersion(c);repository.rollback(c,version,next,"回滚至v$version：$note",principal().userId);return mapOf("sourceChannel" to c,"configVersion" to next,"rolledBackFrom" to version,"status" to "PUBLISHED") }

    fun history(sourceChannel:String):List<Map<String,Any?>> { manager();return repository.history(channel(sourceChannel)).map{mapOf("configVersion" to it.configVersion,"status" to it.status,"fields" to normalizeFields(it.fieldsJson),"reason" to it.reason,"createdAt" to it.createdAt,"publishedAt" to it.publishedAt)} }
    private fun validate(input:ChannelViewConfigInput){valid(input.reason.trim().length in 2..500,"CHANNEL_VIEW_REASON_REQUIRED");valid(input.fields.size in 7..40,"CHANNEL_VIEW_FIELDS_INVALID");val keys=input.fields.mapNotNull{it["key"]?.toString()};valid(keys.toSet().size==keys.size,"CHANNEL_VIEW_FIELD_DUPLICATE");valid(required.all{it in keys},"CHANNEL_VIEW_REQUIRED_FIELD_MISSING");input.fields.forEach{valid((it["key"]?.toString()?.length?:0) in 1..80&&(it["label"]?.toString()?.length?:0) in 1..80,"CHANNEL_VIEW_FIELD_INVALID");if(it["key"] in required)valid(it["visible"]!=false,"CHANNEL_VIEW_REQUIRED_FIELD_HIDDEN")} }
    private fun defaults(channel:String)=listOf("sourceEventTime" to if(channel=="QUALITY") "商返申请日期" else if(channel=="RED_BLACK") "发布时间" else "故障发生时间","problem" to "问题内容","processingStatus" to "处理状态","distributionStatus" to "分发状态","ownerName" to "负责人","lastHandledAt" to "最近处理时间","resolved" to "是否解决").mapIndexed{i,(k,v)->mapOf("key" to k,"label" to v,"visible" to true,"order" to i)}
    private fun normalizeFields(raw:Any?):List<Map<String,Any?>> { val parsed:List<Map<String,Any?>> = when(raw){is String->runCatching{mapper.readValue(raw,mapper.typeFactory.constructCollectionType(List::class.java,Map::class.java)) as List<Map<String,Any?>>}.getOrDefault(emptyList());is List<*>->raw.filterIsInstance<Map<String,Any?>>();else->emptyList()};val retained=parsed.filter{it["key"] !in setOf("sourceStatus","platformStatus")}.toMutableList();if(retained.none{it["key"]=="processingStatus"})retained+=mapOf("key" to "processingStatus","label" to "处理状态","visible" to true,"order" to retained.size);if(retained.none{it["key"]=="distributionStatus"})retained+=mapOf("key" to "distributionStatus","label" to "分发状态","visible" to true,"order" to retained.size);return retained.mapIndexed{i,item->item+mapOf("order" to i)}}
    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
        ?: throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,"AUTHENTICATION_REQUIRED")
    private fun valid(condition:Boolean,reasonCode:String){if(!condition)throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)}
    private fun forbidden(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
}
