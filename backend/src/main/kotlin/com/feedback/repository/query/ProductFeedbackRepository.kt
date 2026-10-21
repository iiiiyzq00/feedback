package com.feedback.repository.query

import com.feedback.mapper.query.*
import com.feedback.model.po.query.*
import org.springframework.stereotype.Repository

data class ProductFeedbackAccessFact(val channel:String,val module:String?,val province:String?,val city:String?)

@Repository
class ProductFeedbackRepository(
    private val requests:ProductFeedbackRequestMapper,private val events:ProductFeedbackEventMapper,
    private val users:ProductFeedbackUserMapper,private val sources:ProductFeedbackSourceMapper,
    private val contents:ProductFeedbackContentMapper,private val audits:ProductFeedbackAuditMapper
) {
    fun create(row:ProductFeedbackCreatePo):Long { check(requests.insert(row)==1&&row.id>0);return row.id }
    fun count(status:String?,type:String?,creator:Long?)=requests.count(status,type,creator)
    fun page(status:String?,type:String?,creator:Long?,limit:Int,offset:Int)=requests.findPage(status,type,creator,limit,offset)
    fun find(id:Long)=requests.findById(id)
    fun lock(id:Long)=requests.lockById(id)
    fun update(row:ProductFeedbackUpdatePo)=requests.update(row)==1
    fun activeUser(id:Long)=users.countActive(id)==1
    fun names(ids:Collection<Long>):Map<Long,String> = if(ids.isEmpty()) emptyMap() else
        users.findNames(ids.distinct()).associate { it.id to it.displayName }
    fun events(id:Long)=events.findByRequestId(id)
    fun event(row:ProductFeedbackEventInsertPo){check(events.insert(row)==1)}
    fun access(id:Long):ProductFeedbackAccessFact? {
        val source=sources.findById(id)?:return null
        val content=contents.findSafeById(id)?:return null
        return ProductFeedbackAccessFact(source.sourceChannel,content.functionModule,source.provinceCode,source.cityCode)
    }
    fun audit(user:Long,operation:String,id:Long,detailJson:String){check(audits.insert(user,operation,id.toString(),detailJson)==1)}
}
