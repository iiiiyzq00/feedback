package com.feedback.service.alert

import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.po.alert.OncallLegacyInsertPo
import com.feedback.repository.alert.OncallScheduleRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class OncallInput(val team:String,val userId:Long,val start:LocalDateTime,val end:LocalDateTime,val type:String="PRIMARY",val escalationUserId:Long?=null,val name:String?=null)
data class TeamMemberInput(val team:String,val userId:Long,val role:String="MEMBER",val primary:Boolean=false)

@Service
class OncallScheduleService(private val repository:OncallScheduleRepository){
    fun teams():List<Map<String,Any?>> = repository.teams().map{mapOf("team" to it.team,"members" to it.members,"leaderId" to it.leaderId)}

    fun members(team:String?):List<Map<String,Any?>> = repository.members(team?.takeIf{it.isNotBlank()}).map{v->val m=v.row;mapOf("id" to m.id,"team" to m.team,"userId" to m.userId,"userName" to v.userName,"role" to m.role,"primary" to m.primary,"active" to m.active)}

    fun eligibleUsers():List<Map<String,Any?>> = repository.eligibleUsers().map{mapOf("id" to it.id,"displayName" to it.displayName,"username" to it.username)}

    @Transactional fun saveMember(input:TeamMemberInput):Map<String,Any>{val p=principal();ensureAlert(canEditAll(p)){"只有管理员可以维护团队成员"};ensureAlert(input.team.isNotBlank()&&input.team.length<=50&&input.role.matches(Regex("[A-Z_]{2,30}"))&&userActive(input.userId))
        repository.saveMember(input.team.trim(),input.userId,input.role,input.primary);return mapOf("team" to input.team.trim(),"userId" to input.userId,"active" to true)}

    @Transactional fun removeMember(id:Long):Map<String,Any>{val p=principal();ensureAlert(canEditAll(p)){"只有管理员可以维护团队成员"};ensureAlert(!repository.hasFutureSchedule(id)){"成员存在未来有效排班，请先取消排班"};ensureAlert(repository.removeMember(id)){"团队成员不存在"};return mapOf("removed" to true)}

    fun calendar(team:String,start:LocalDateTime,end:LocalDateTime):Map<String,Any>{
        ensureAlert(team.isNotBlank()&&team.length<=50&&start.isBefore(end)&&!end.isAfter(start.plusDays(93)))
        val rows=repository.calendar(team,start,end).map{v->val s=v.row;linkedMapOf<String,Any?>("id" to s.id,"team" to s.team,"name" to s.name,"type" to s.type,"userId" to s.userId,"userName" to v.userName,"escalationUserId" to s.escalationUserId,"escalationUserName" to v.escalationUserName,"start" to s.start,"end" to s.end,"status" to s.status,"version" to s.version)}
        val gaps=mutableListOf<Map<String,Any>>();var cursor=start
        rows.forEach{val rowStart=(it["start"] as LocalDateTime).coerceAtLeast(start);val rowEnd=(it["end"] as LocalDateTime).coerceAtMost(end);if(rowStart.isAfter(cursor))gaps+=mapOf("start" to cursor,"end" to rowStart,"fallback" to "TEAM_LEADER");if(rowEnd.isAfter(cursor))cursor=rowEnd};if(cursor.isBefore(end))gaps+=mapOf("start" to cursor,"end" to end,"fallback" to "TEAM_LEADER")
        return mapOf("items" to rows,"gaps" to gaps,"fallbackPolicy" to "无排班时通知团队负责人")
    }

    @Transactional fun create(input:OncallInput):Map<String,Any>{
        val p=principal();ensureAlert(input.team.isNotBlank()&&input.team.length<=50&&input.start.isBefore(input.end)&&input.end.isAfter(LocalDateTime.now()));ensureAlert(input.type in setOf("PRIMARY","BACKUP","TEMPORARY"));ensureAlert(canEditAll(p)||isMember(p.userId,input.team)){"只能管理本团队排班"}
        ensureAlert(userActive(input.userId)&&isMember(input.userId,input.team)){"值班人必须是该团队有效成员"};input.escalationUserId?.let{ensureAlert(userActive(it)){"升级联系人无效"}}
        ensureAlert(!repository.conflicts(input.team,input.type,input.start,input.end)){"排班时间冲突"}
        return mapOf("id" to repository.create(OncallLegacyInsertPo(team=input.team,name=input.name?.trim(),type=input.type,userId=input.userId,start=input.start,end=input.end,escalationUserId=input.escalationUserId,actor=p.userId)))
    }

    @Transactional fun cancel(id:Long,version:Long):Map<String,Any>{val p=principal();val team=repository.lockTeam(id)?:alertMissing("排班不存在");ensureAlert(canEditAll(p)||isMember(p.userId,team)){"只能管理本团队排班"};ensureAlert(repository.cancel(id,version)){"排班已被修改"};return mapOf("cancelled" to true)}
    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal?:alertStateAbort("未认证")
    private fun canEditAll(p:WebPrincipal)=PlatformPermissions.ONCALL_EDIT_ALL in p.permissions
    private fun isMember(user:Long,team:String)=repository.isMember(user,team)
    private fun userActive(id:Long)=repository.activeUser(id)
}
