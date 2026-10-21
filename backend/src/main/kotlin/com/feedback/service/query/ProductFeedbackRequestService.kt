package com.feedback.service.query

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.model.po.query.ProductFeedbackCreatePo
import com.feedback.model.po.query.ProductFeedbackEventInsertPo
import com.feedback.model.po.query.ProductFeedbackRequestPo
import com.feedback.model.po.query.ProductFeedbackUpdatePo
import com.feedback.repository.query.ProductFeedbackRepository
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.RoleCode
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class ProductFeedbackInput(
    val requestType:String,val title:String,val description:String,val sourceChannel:String?=null,
    val feedbackSourceId:Long?=null,val pagePath:String,val pageContext:Map<String,Any?>?=null,
    val attachments:List<Map<String,Any?>> = emptyList()
)
data class ProductFeedbackStatusInput(
    val status:String,val handlerUserId:Long?=null,val priorityCode:String?=null,
    val plannedVersion:String?=null,val reason:String,val resolutionNote:String?=null
)
data class ProductFeedbackCommentInput(val comment:String)

@Service
class ProductFeedbackRequestService(
    private val repository:ProductFeedbackRepository,
    private val mapper:ObjectMapper,
    private val permissions:DataPermissionService
) {
    private val types=setOf("FIELD_MISSING","DISPLAY","WORKFLOW","PERMISSION","DATA_ISSUE","NEW_REQUIREMENT")
    private val statuses=setOf("OPEN","ACCEPTED","IN_PROGRESS","PENDING_ACCEPTANCE","COMPLETED","REJECTED","DEFERRED")
    private val priorities=setOf("P0","P1","P2","P3")

    @Transactional fun create(input:ProductFeedbackInput):Map<String,Any?> {
        val p=principal();val type=input.requestType.uppercase();valid(type in types,"PRODUCT_FEEDBACK_TYPE_INVALID")
        val title=input.title.trim();val description=input.description.trim();val path=input.pagePath.trim()
        valid(title.length in 2..200,"PRODUCT_FEEDBACK_TITLE_INVALID");valid(description.length in 2..2000,"PRODUCT_FEEDBACK_DESCRIPTION_INVALID")
        valid(path.startsWith("/")&&path.length<=500,"PRODUCT_FEEDBACK_PAGE_PATH_INVALID");valid(input.attachments.size<=5,"PRODUCT_FEEDBACK_ATTACHMENTS_LIMIT")
        input.attachments.forEach{val nameLength=it["name"]?.toString()?.length?:0;valid(nameLength in 1..200&&it["size"] is Number,"PRODUCT_FEEDBACK_ATTACHMENT_INVALID")}
        input.feedbackSourceId?.let(::requireFeedbackAccess)
        val requestNo="REQ-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))}-${UUID.randomUUID().toString().take(8).uppercase()}"
        val id=repository.create(ProductFeedbackCreatePo(requestNo=requestNo,requestType=type,title=title,
            description=description,sourceChannel=input.sourceChannel?.uppercase(),feedbackSourceId=input.feedbackSourceId,
            pagePath=path,pageContextJson=mapper.writeValueAsString(input.pageContext?:emptyMap<String,Any?>()),
            attachmentsJson=mapper.writeValueAsString(input.attachments),createdBy=p.userId))
        event(id,"CREATED",null,null,null,"OPEN","需求已提交")
        audit("PRODUCT_FEEDBACK_CREATED",id,mapOf("requestNo" to requestNo,"requestType" to type,"feedbackSourceId" to input.feedbackSourceId))
        return mapOf("id" to id,"requestNo" to requestNo,"status" to "OPEN")
    }

    fun list(status:String?,type:String?,mine:Boolean,page:Int,size:Int):Map<String,Any?> {
        valid(page>=1&&size in 1..100,"PRODUCT_FEEDBACK_PAGE_INVALID");val p=principal()
        val normalizedStatus=status?.uppercase()?.also{valid(it in statuses,"PRODUCT_FEEDBACK_STATUS_INVALID")}
        val normalizedType=type?.uppercase()?.also{valid(it in types,"PRODUCT_FEEDBACK_TYPE_INVALID")}
        val creator=p.userId.takeIf{mine||p.roles.none{it in setOf(RoleCode.ADMIN,RoleCode.OPERATOR)}}
        val total=repository.count(normalizedStatus,normalizedType,creator)
        val rows=repository.page(normalizedStatus,normalizedType,creator,size,(page-1)*size)
        val names=repository.names(rows.flatMap{listOfNotNull(it.createdBy,it.handlerUserId)})
        val records=rows.map{requestMap(it,names)}
        return mapOf("records" to records,"total" to total,"page" to page,"size" to size)
    }

    @Transactional fun update(id:Long,input:ProductFeedbackStatusInput):Map<String,Any?> {
        val p=principal();val row=lockedRequest(id);val manager=p.roles.any{it in setOf(RoleCode.ADMIN,RoleCode.OPERATOR)};val creator=row.createdBy==p.userId
        val handler=row.handlerUserId;val status=input.status.uppercase();valid(status in statuses,"PRODUCT_FEEDBACK_STATUS_INVALID")
        val reason=input.reason.trim();valid(reason.length in 2..1000,"PRODUCT_FEEDBACK_REASON_REQUIRED")
        if(!manager && !(creator&&status in setOf("COMPLETED","IN_PROGRESS")))forbidden("PRODUCT_FEEDBACK_MANAGE_FORBIDDEN")
        if(creator&&!manager){valid(row.requestStatus=="PENDING_ACCEPTANCE","PRODUCT_FEEDBACK_ACCEPTANCE_NOT_PENDING");valid(status in setOf("COMPLETED","IN_PROGRESS"),"PRODUCT_FEEDBACK_ACCEPTANCE_ACTION_INVALID")}
        if(!manager&&handler!=p.userId&&status !in setOf("COMPLETED","IN_PROGRESS"))forbidden("PRODUCT_FEEDBACK_HANDLER_REQUIRED")
        valid(status!="PENDING_ACCEPTANCE"||!input.resolutionNote.isNullOrBlank(),"PRODUCT_FEEDBACK_RESOLUTION_REQUIRED")
        val priority=(input.priorityCode?:row.priorityCode).uppercase();valid(priority in priorities,"PRODUCT_FEEDBACK_PRIORITY_INVALID")
        input.handlerUserId?.let{valid(repository.activeUser(it),"PRODUCT_FEEDBACK_HANDLER_INVALID")}
        val newHandler=input.handlerUserId?:handler?:p.userId.takeIf{manager&&status in setOf("ACCEPTED","IN_PROGRESS","PENDING_ACCEPTANCE")}
        if(status in setOf("ACCEPTED","IN_PROGRESS","PENDING_ACCEPTANCE")&&newHandler==null)invalid("PRODUCT_FEEDBACK_HANDLER_REQUIRED")
        valid(repository.update(ProductFeedbackUpdatePo(id,status,newHandler,priority,
            input.plannedVersion?.trim()?.takeIf(String::isNotEmpty),input.resolutionNote?.trim())),"PRODUCT_FEEDBACK_NOT_FOUND")
        val oldStatus=row.requestStatus;val eventType=when{creator&&status=="COMPLETED"->"ACCEPTANCE_CONFIRMED";creator&&status=="IN_PROGRESS"->"ACCEPTANCE_REJECTED";status=="PENDING_ACCEPTANCE"->"SUBMITTED_FOR_ACCEPTANCE";newHandler!=handler->"ASSIGNED";status=="ACCEPTED"->"ACCEPTED";else->"STATUS_CHANGED"}
        event(id,eventType,handler,newHandler,oldStatus,status,reason)
        audit("PRODUCT_FEEDBACK_STATUS_CHANGED",id,mapOf("from" to oldStatus,"status" to status,"handlerUserId" to newHandler,"reason" to reason));return mapOf("id" to id,"status" to status)
    }

    fun detail(id:Long):Map<String,Any?> { val p=principal();val row=request(id);if(p.roles.none{it in setOf(RoleCode.ADMIN,RoleCode.OPERATOR)}&&row.createdBy!=p.userId)forbidden("PRODUCT_FEEDBACK_FORBIDDEN")
        val eventRows=repository.events(id);val names=repository.names(eventRows.flatMap{listOfNotNull(it.actorUserId,it.newHandlerUserId)})
        val events=eventRows.map{e->linkedMapOf<String,Any?>("id" to e.id,"eventType" to e.eventType,"oldStatus" to e.oldStatus,
            "newStatus" to e.newStatus,"reason" to e.reason,"createdAt" to e.createdAt,"actorName" to names[e.actorUserId],
            "handlerName" to e.newHandlerUserId?.let(names::get))}
        return detailMap(row).apply{this["events"]=events} }

    @Transactional fun comment(id:Long,input:ProductFeedbackCommentInput):Map<String,Any?> { detail(id);val text=input.comment.trim();valid(text.length in 2..1000,"PRODUCT_FEEDBACK_COMMENT_INVALID");event(id,"COMMENTED",null,null,null,null,text);return mapOf("id" to id,"commented" to true) }

    @Suppress("UNCHECKED_CAST")
    fun export():String {
        val rows=list(null,null,false,1,100)["records"] as List<Map<String,Any?>>
        fun csv(value:Any?):String { val escaped=(value?.toString()?:"").replace("\"","\"\"");return "\"$escaped\"" }
        return buildString {
            appendLine("需求编号,类型,标题,渠道,反馈ID,页面,优先级,状态,计划版本,提出人,负责人,提出时间")
            rows.forEach { row -> appendLine(listOf(row["requestNo"],row["requestType"],row["title"],row["sourceChannel"],row["feedbackSourceId"],row["pagePath"],row["priorityCode"],row["status"],row["plannedVersion"],row["creatorName"],row["handlerName"],row["createdAt"]).joinToString(",",transform=::csv)) }
        }
    }
    private fun requireFeedbackAccess(id:Long){val row=repository.access(id)?:notFound("FEEDBACK_NOT_FOUND");if(!permissions.scope().permits(row.channel,row.module,row.province,row.city))forbidden("FEEDBACK_FORBIDDEN")}
    private fun request(id:Long)=repository.find(id)?:notFound("PRODUCT_FEEDBACK_NOT_FOUND")
    private fun lockedRequest(id:Long)=repository.lock(id)?:notFound("PRODUCT_FEEDBACK_NOT_FOUND")
    private fun event(id:Long,type:String,oldHandler:Long?,newHandler:Long?,oldStatus:String?,newStatus:String?,reason:String){repository.event(ProductFeedbackEventInsertPo(id,type,principal().userId,oldHandler,newHandler,oldStatus,newStatus,reason))}
    private fun audit(operation:String,id:Long,detail:Map<String,Any?>){repository.audit(principal().userId,operation,id,mapper.writeValueAsString(detail))}
    private fun requestMap(row:ProductFeedbackRequestPo,names:Map<Long,String>)=linkedMapOf<String,Any?>(
        "id" to row.id,"requestNo" to row.requestNo,"requestType" to row.requestType,"title" to row.title,
        "description" to row.description,"sourceChannel" to row.sourceChannel,"feedbackSourceId" to row.feedbackSourceId,
        "pagePath" to row.pagePath,"pageContext" to row.pageContext,"attachments" to row.attachments,
        "status" to row.requestStatus,"priorityCode" to row.priorityCode,"plannedVersion" to row.plannedVersion,
        "resolutionNote" to row.resolutionNote,"createdBy" to row.createdBy,"creatorName" to names[row.createdBy],
        "handlerUserId" to row.handlerUserId,"handlerName" to row.handlerUserId?.let(names::get),
        "createdAt" to row.createdAt,"updatedAt" to row.updatedAt)
    /** 详情历史上来自 SELECT *，蛇形字段名属于既有 API 合同，不能随列表 DTO 一并改名。 */
    private fun detailMap(row:ProductFeedbackRequestPo)=linkedMapOf<String,Any?>(
        "id" to row.id,"request_no" to row.requestNo,"request_type" to row.requestType,"title" to row.title,
        "description" to row.description,"source_channel" to row.sourceChannel,"feedback_source_id" to row.feedbackSourceId,
        "page_path" to row.pagePath,"page_context" to row.pageContext,"attachment_metadata" to row.attachments,
        "request_status" to row.requestStatus,"priority_code" to row.priorityCode,"planned_version" to row.plannedVersion,
        "resolution_note" to row.resolutionNote,"created_by" to row.createdBy,"handler_user_id" to row.handlerUserId,
        "accepted_at" to row.acceptedAt,"completed_at" to row.completedAt,"created_at" to row.createdAt,"updated_at" to row.updatedAt)
    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal?:throw ClientException(ErrorCode.AUTH_UNAUTHORIZED,"AUTHENTICATION_REQUIRED")
    private fun valid(condition:Boolean,reasonCode:String){if(!condition)invalid(reasonCode)}
    private fun invalid(reasonCode:String):Nothing=throw ClientException(ErrorCode.BAD_REQUEST,reasonCode)
    private fun forbidden(reasonCode:String):Nothing=throw ClientException(ErrorCode.AUTH_FORBIDDEN,reasonCode)
    private fun notFound(reasonCode:String):Nothing=throw ClientException(ErrorCode.NOT_FOUND,reasonCode)
}
