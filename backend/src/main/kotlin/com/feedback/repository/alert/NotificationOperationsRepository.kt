package com.feedback.repository.alert
import com.feedback.mapper.alert.NotificationOperationsMapper
import org.springframework.stereotype.Repository
@Repository class NotificationOperationsRepository(private val mapper:NotificationOperationsMapper){
 fun count(channel:String?,status:String?,alertId:Long?)=mapper.count(channel,status,alertId)
 fun page(channel:String?,status:String?,alertId:Long?,limit:Int,offset:Int)=mapper.findPage(channel,status,alertId,limit,offset)
 fun overview(hours:Int)=mapper.overview(hours)
 fun queue()=mapper.queue()
}
