package com.feedback.service.cluster

import com.feedback.config.RuntimeConfigService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.beans.factory.annotation.Value
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.math.BigDecimal

data class ClusterReviewAssignment(val priority:String,val dueAt:LocalDateTime,val reason:String)
data class ClusterReviewPolicyContract(
    val version:String,
    val severityRelationship:String,
    val priorities:Map<String,String>,
    val competitionMargin:BigDecimal,
    val hotspotMinChannels:Int,
    val hotspotMinFeedback:Int,
    val slaEnabled:Boolean,
    val sla:Map<String,String>
)

@Service
class ClusterReviewGovernance(
    private val config:RuntimeConfigService,
    private val mapper:ObjectMapper,
    @Value("\${app.review-governance.sla-enabled:false}") private val slaEnabled:Boolean=false
){
    fun policy():ClusterReviewPolicyContract{
        val margin=config.decimal("cluster.competition-margin",BigDecimal("0.030000")).coerceIn(BigDecimal.ZERO,BigDecimal.ONE)
        val minChannels=config.int("cluster.review-hotspot-min-channels",2).coerceIn(2,20)
        val minFeedback=config.int("cluster.review-hotspot-min-feedback",5).coerceIn(2,100000)
        val p1Hours=config.int("cluster.review-sla-p1-hours",4).coerceIn(1,72)
        val p2Days=config.int("cluster.review-sla-p2-business-days",1).coerceIn(1,30)
        val p3Days=config.int("cluster.review-sla-p3-business-days",3).coerceIn(1,30)
        return ClusterReviewPolicyContract(
            version="cluster-review-priority-v1",
            severityRelationship="S表示问题影响严重度，P表示人工审核处理顺序；S可单向影响P，P不得反向修改S",
            priorities=linkedMapOf(
                "P1" to "S0/S1高风险，紧急审核",
                "P2" to "S2重要问题、已解决问题疑似复发，或满足反馈量、渠道数和候选分差的竞争热点",
                "P3" to "其他一般边界不确定项"
            ),
            competitionMargin=margin,hotspotMinChannels=minChannels,hotspotMinFeedback=minFeedback,
            slaEnabled=slaEnabled,
            sla=if(slaEnabled) linkedMapOf("P1" to "${p1Hours}小时","P2" to "${p2Days}工作日","P3" to "${p3Days}工作日")
                else linkedMapOf("P1" to "开发阶段不计时","P2" to "开发阶段不计时","P3" to "开发阶段不计时")
        )
    }

    fun assign(profile:ProblemProfile,candidates:List<CandidateEvidence>,now:LocalDateTime=LocalDateTime.now()):ClusterReviewAssignment{
        val minChannels=config.int("cluster.review-hotspot-min-channels",2).coerceIn(2,20)
        val minFeedback=config.int("cluster.review-hotspot-min-feedback",5).coerceIn(2,100000)
        val competitionMargin=config.decimal("cluster.competition-margin",BigDecimal("0.030000"))
            .coerceIn(BigDecimal.ZERO,BigDecimal.ONE)
        val ranked=candidates.sortedWith(compareByDescending<CandidateEvidence> { it.rankingScore }
            .thenByDescending { it.semanticScore })
        val stronglyCompeting=ranked.zipWithNext().firstOrNull()?.let { (first,second) ->
            first.semanticScore.subtract(second.semanticScore).abs() <= competitionMargin
        } == true
        val crossChannelHotspot=candidates.any{ evidence ->
            evidence.cluster.totalRecordCount >= minFeedback && runCatching{
                mapper.readTree(evidence.cluster.channelDistribution ?: "{}").fields().asSequence()
                    .count { it.value.asInt(0) > 0 } >= minChannels
            }.getOrDefault(false)
        }
        val recurrence=candidates.any(CandidateEvidence::historicalRecurrence)
        val highSeverity=profile.severityLevel?.uppercase() in setOf("S0","S1")
        val importantDegradation=profile.severityLevel?.uppercase()=="S2"
        val qualifiedHotspot=crossChannelHotspot && stronglyCompeting
        val priority=when{
            highSeverity->"P1"
            importantDegradation || recurrence || qualifiedHotspot->"P2"
            else->"P3"
        }
        val due=when(priority){
            "P1"->now.plusHours(config.int("cluster.review-sla-p1-hours",4).coerceIn(1,72).toLong())
            "P2"->addBusinessDays(now,config.int("cluster.review-sla-p2-business-days",1).coerceIn(1,30))
            else->addBusinessDays(now,config.int("cluster.review-sla-p3-business-days",3).coerceIn(1,30))
        }
        val reason=when(priority){
            "P1"->"S0/S1高风险问题，需紧急确认归簇边界"
            "P2"->listOfNotNull(
                "S2重要问题".takeIf { importantDegradation },
                "已解决问题疑似复发".takeIf { recurrence },
                "跨渠道热点且前两候选分差不高于${competitionMargin.stripTrailingZeros().toPlainString()}".takeIf { qualifiedHotspot }
            ).joinToString("、")+"，需优先确认归簇边界"
            else->"一般归簇边界不确定项，按常规队列处理"
        }
        return ClusterReviewAssignment(priority,due,reason)
    }

    private fun addBusinessDays(start:LocalDateTime,days:Int):LocalDateTime{
        val holidays=config.dictionary("cluster.review-holidays").keys().mapNotNull{runCatching{LocalDate.parse(it)}.getOrNull()}.toSet()
        var result=start;var remaining=days
        while(remaining>0){result=result.plusDays(1);if(result.dayOfWeek !in WEEKEND && result.toLocalDate() !in holidays)remaining--}
        return result
    }
    companion object{private val WEEKEND=setOf(DayOfWeek.SATURDAY,DayOfWeek.SUNDAY)}
}
