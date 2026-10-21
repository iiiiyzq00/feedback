package com.feedback.service.alert

import com.feedback.model.po.alert.VersionReleasePo
import com.feedback.repository.alert.VersionFeedbackFact
import com.feedback.repository.alert.VersionReleaseRepository
import com.feedback.security.WebPrincipal
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import java.time.LocalDateTime

data class VersionReleaseFactInput(val packageName:String,val appVersion:String,val controlVersion:String,val releasedAt:LocalDateTime,val status:String="ACTIVE")

private data class ReleaseStats(
    var currentTotal:Long=0,
    var controlTotal:Long=0,
    val currentByCluster:MutableMap<Long,Long> = mutableMapOf(),
    val controlByCluster:MutableMap<Long,Long> = mutableMapOf(),
    val channelsByCluster:MutableMap<Long,MutableSet<String>> = mutableMapOf(),
    val severeByCluster:MutableMap<Long,Long> = mutableMapOf(),
)

@Service
class VersionReleaseFactService(private val repository:VersionReleaseRepository){
    fun multiChannelRegressionCandidates(end:LocalDateTime=LocalDateTime.now()):Double {
        if(regressionCandidates(end)<=0)return 0.0
        val releases=repository.list().filter{it.status=="ACTIVE"}
        val sourceIds=mutableMapOf<Pair<String,String>,MutableSet<Long>>()
        val channels=mutableMapOf<Pair<String,String>,MutableSet<String>>()
        repository.forEachFact(releases,end,end.minusMinutes(30)){fact->
            if(fact.source.ingestTime>=end.minusMinutes(30)){
                releases.asSequence().filter{it.packageName==fact.source.packageName&&it.appVersion==fact.source.appVersion}.forEach{release->
                    val key=release.packageName to release.appVersion
                    sourceIds.getOrPut(key){mutableSetOf()}.add(fact.source.id)
                    channels.getOrPut(key){mutableSetOf()}.add(fact.source.sourceChannel)
                }
            }
        }
        return sourceIds.count{(key,ids)->ids.size>=20&&(channels[key]?.size?:0)>=2}.toDouble()
    }

    fun list():List<VersionReleasePo> = repository.list()

    fun save(input:VersionReleaseFactInput):Map<String,Any>{
        ensureAlert(input.packageName.matches(Regex("[A-Za-z0-9._-]{2,150}")))
        ensureAlert(input.appVersion.matches(Regex("[A-Za-z0-9._+-]{1,50}"))&&input.controlVersion.matches(Regex("[A-Za-z0-9._+-]{1,50}"))&&input.appVersion!=input.controlVersion)
        ensureAlert(input.status in setOf("DRAFT","ACTIVE","RETIRED"))
        ensureAlert(!input.releasedAt.isAfter(LocalDateTime.now().plusDays(1)))
        val actor=(SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal)?.userId?:alertStateAbort("未认证")
        repository.save(input.packageName,input.appVersion,input.controlVersion,input.releasedAt,input.status,actor)
        return mapOf("packageName" to input.packageName,"appVersion" to input.appVersion,"status" to input.status)
    }

    fun regressionCandidates(end:LocalDateTime=LocalDateTime.now()):Double = evaluate(end,null)

    /** 版本阻断回归：发布观察窗口内，新版本多渠道且 S0/S1 占比达标的簇，才允许升级到 P0。 */
    fun blockingRegressionCandidates(end:LocalDateTime=LocalDateTime.now(),releaseWindowMinutes:Long=120):Double = evaluate(end,releaseWindowMinutes)

    /**
     * Source 主表有界分页；Content 与 MAIN ClusterLink 仅按当前页批量读取。所有比率均按发布 ID 组装，
     * 保留同包同版本在不同发布事实下各自独立的对照窗口，避免跨发布合并统计。
     */
    private fun evaluate(end:LocalDateTime,releaseWindowMinutes:Long?):Double{
        val releases=repository.activeBefore(end)
        if(releases.isEmpty())return 0.0
        val stats=releases.associate{it.id to ReleaseStats()}
        repository.forEachFact(releases,end){fact->releases.asSequence().filter{it.packageName==fact.source.packageName}.forEach{release->
            val state=stats.getValue(release.id)
            val withinReleaseWindow=releaseWindowMinutes==null||!fact.source.ingestTime.isAfter(release.releasedAt.plusMinutes(releaseWindowMinutes))
            val current=fact.source.appVersion==release.appVersion&&fact.source.ingestTime>=end.minusMinutes(30)&&withinReleaseWindow
            if(current){
                state.currentTotal++
                fact.clusterIds.forEach{cluster->
                    state.currentByCluster[cluster]=(state.currentByCluster[cluster]?:0)+1
                    state.channelsByCluster.getOrPut(cluster){mutableSetOf()}.add(fact.source.sourceChannel)
                    if(fact.content.severityLevel in setOf("S0","S1"))state.severeByCluster[cluster]=(state.severeByCluster[cluster]?:0)+1
                }
            }
            val control=fact.source.appVersion==release.controlVersion&&fact.source.ingestTime>=release.releasedAt.minusDays(7)&&fact.source.ingestTime<release.releasedAt
            if(control){
                state.controlTotal++
                fact.clusterIds.forEach{cluster->state.controlByCluster[cluster]=(state.controlByCluster[cluster]?:0)+1}
            }
        }}
        var candidates=0
        releases.forEach{release->
            val state=stats.getValue(release.id)
            if(state.currentTotal==0L||state.controlTotal==0L)return@forEach
            state.currentByCluster.forEach{(cluster,currentCount)->
                val currentRate=currentCount.toDouble()/state.currentTotal
                val controlRate=(state.controlByCluster[cluster]?:0).toDouble()/state.controlTotal
                val blockingPass=releaseWindowMinutes==null||(
                    (state.channelsByCluster[cluster]?.size?:0)>=2&&
                    (state.severeByCluster[cluster]?:0).toDouble()/currentCount>=0.3
                )
                if(currentCount>=20&&currentRate>=0.01&&currentRate>=2*controlRate&&blockingPass)candidates++
            }
        }
        return candidates.toDouble()
    }
}
