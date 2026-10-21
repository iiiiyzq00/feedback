package com.feedback.service.alert

import com.feedback.model.po.alert.TrendBaselinePo
import com.feedback.model.po.alert.TrendBaselineWritePo
import com.feedback.repository.alert.AlertTrendBaselineRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@Service
class AlertTrendBaselineService(private val repository:AlertTrendBaselineRepository){
    @Transactional fun refresh(){val start=LocalDateTime.now().truncatedTo(ChronoUnit.HOURS).minusHours(1);val end=start.plusHours(1);val facts=repository.feedbackFacts(start,end);val rows=facts.clusters.map{TrendBaselineWritePo("CLUSTER_OCCURRENCE",it.key,it.value,start)}+listOf(TrendBaselineWritePo("NEGATIVE_SENTIMENT","GLOBAL",facts.negative,start),TrendBaselineWritePo("SDK_VOLUME","GLOBAL",repository.sdkCount(start,end),start));repository.upsert(rows)}
    fun clusterAnomalyCount(end:LocalDateTime=LocalDateTime.now()):Double{val current=repository.feedbackFacts(end.minusHours(1),end).clusters.filterValues{it>=10};if(current.isEmpty())return 0.0;val rows=repository.baselines("CLUSTER_OCCURRENCE",current.keys.toList(),end.minusDays(28),end.minusHours(1));val grouped=rows.groupBy{it.dimensionKey};return current.count{(dimension,value)->anomalous(value.toDouble(),end,6.0,grouped[dimension].orEmpty())}.toDouble()}
    fun globalAnomaly(metric:String,current:Double,end:LocalDateTime,multiplier:Double,min:Int)=if(current>=min&&anomalous(current,end,multiplier,repository.baselines(metric,listOf("GLOBAL"),end.minusDays(28),end.minusHours(1))))1.0 else 0.0
    private fun anomalous(current:Double,end:LocalDateTime,multiplier:Double,rows:List<TrendBaselinePo>):Boolean{val byHour=rows.associate{it.bucketStart.truncatedTo(ChronoUnit.HOURS) to it.sampleCount.toDouble()};val values=(1L..672L).map{byHour[end.truncatedTo(ChronoUnit.HOURS).minusHours(it)]?:0.0};val median=median(values);val mad=median(values.map{abs(it-median)});return current>median+multiplier*mad.coerceAtLeast(1.0)}
    private fun median(v:List<Double>):Double{val s=v.sorted();return if(s.size%2==1)s[s.size/2] else (s[s.size/2-1]+s[s.size/2])/2}
}
