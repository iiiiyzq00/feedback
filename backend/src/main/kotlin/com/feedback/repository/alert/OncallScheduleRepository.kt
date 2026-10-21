package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
data class OncallMemberView(val row:OncallMemberPo,val userName:String?)
data class OncallScheduleView(val row:OncallSchedulePo,val userName:String?,val escalationUserName:String?)
@Repository class OncallScheduleRepository(private val configs:OncallConfigMapper,private val users:OncallUserMapper,
 private val roles:OncallRoleMapper,private val membersLegacy:OncallTeamMemberLegacyMapper,
 private val schedulesLegacy:OncallScheduleLegacyMapper){
 fun teams()=configs.teams()
 fun members(team:String?):List<OncallMemberView>{val rows=configs.members(team);val names=activeNames(rows.map{it.userId});return rows.mapNotNull{r->if(r.userId !in names)null else OncallMemberView(r,names[r.userId])}.sortedWith(compareBy<OncallMemberView>{it.row.team}.thenByDescending{it.row.primary}.thenBy{it.userName})}
 fun eligibleUsers():List<OncallUserPo>{val ids=roles.eligibleUserIds();return if(ids.isEmpty())emptyList() else users.findActive(ids).distinctBy{it.id}.sortedBy{it.displayName}.take(200)}
 fun activeUser(id:Long)=users.countActive(id)>0
 fun isMember(user:Long,team:String)=configs.isMember(user,team)>0
 fun saveMember(team:String,user:Long,role:String,primary:Boolean){if(primary)membersLegacy.clearPrimary(team);ensureAlertRepositoryState(membersLegacy.upsert(team,user,role,primary)>=1)}
 fun hasFutureSchedule(memberId:Long):Boolean{val member=configs.memberByLegacyId(memberId)?:return false;return configs.countFutureSchedules(member.team,member.userId)>0}
 fun removeMember(id:Long)=membersLegacy.remove(id)==1
 fun calendar(team:String,start:LocalDateTime,end:LocalDateTime):List<OncallScheduleView>{val rows=configs.calendar(team,start,end);val ids=rows.flatMap{listOfNotNull(it.userId,it.escalationUserId)};val names=allNames(ids);return rows.mapNotNull{r->if(r.userId !in names)null else OncallScheduleView(r,names[r.userId],r.escalationUserId?.let(names::get))}}
 fun conflicts(team:String,type:String,start:LocalDateTime,end:LocalDateTime)=configs.countConflicts(team,type,start,end)>0
 fun create(row:OncallLegacyInsertPo):Long{ensureAlertRepositoryState(schedulesLegacy.insert(row)==1&&row.id>0);return row.id}
 fun lockTeam(id:Long)=schedulesLegacy.lockTeam(id)
 fun cancel(id:Long,version:Long)=schedulesLegacy.cancel(id,version)==1
 private fun activeNames(ids:Collection<Long>):Map<Long,String?>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else users.findActive(unique).associate{it.id to it.displayName}}
 private fun allNames(ids:Collection<Long>):Map<Long,String?>{val unique=ids.distinct();return if(unique.isEmpty())emptyMap() else users.findAll(unique).associate{it.id to it.displayName}}
}
