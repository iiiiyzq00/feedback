package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface NotificationDispatchMapper{fun insert(@Param("row")row:NotificationEnqueuePo):Int;fun digestExists():Int;fun insertDigest(@Param("content")content:String):Int;fun sourceDelivered(@Param("instance")instance:Long,@Param("channel")channel:String):Int}

