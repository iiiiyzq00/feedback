package com.feedback.repository.alert
import com.feedback.mapper.alert.*
import com.feedback.model.po.alert.*
import com.feedback.model.query.alert.*
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class IncorrectDedupView(val row:IncorrectDedupPo,val confirmedByName:String?)
@Repository class AlertQualityFactRepository(private val configs:AlertQualityConfigMapper,
 private val occurrences:AlertQualityOccurrenceMapper,private val attachments:AlertQualityAttachmentMapper,
 private val importRows:AlertQualityImportRowMapper,private val sources:AlertQualitySourceMapper,
 private val users:AlertQualityUserMapper){
 fun latestMetric(at:LocalDateTime,path:String)=configs.latestMetric(at,path)?:100.0
 fun openIncorrectDedup(at:LocalDateTime)=occurrences.countOpen(at)
 fun attachmentOutcomes(start:LocalDateTime,end:LocalDateTime):AttachmentOutcomePo{
  val a=attachments.outcomes(start,end);val i=importRows.outcomes(start,end)
  return AttachmentOutcomePo(a.total+i.total,a.failed+i.failed)
 }
 fun sourcesExist(ids:List<Long>)=sources.countIds(ids)==ids.distinct().size
 fun insert(row:IncorrectDedupInsertPo):Long{ensureAlertRepositoryState(occurrences.insertDedup(row)==1&&row.id>0);return row.id}
 fun repair(id:Long,note:String,actor:Long)=occurrences.repair(id,note,actor)==1
 fun events():List<IncorrectDedupView>{val rows=occurrences.findDedup();val ids=rows.mapNotNull{it.confirmedBy}.distinct();val names=if(ids.isEmpty())emptyMap() else users.findNames(ids).associate{it.id to it.displayName};return rows.mapNotNull{row->val actor=row.confirmedBy;if(actor==null||actor !in names)null else IncorrectDedupView(row,names[actor])}}
}
