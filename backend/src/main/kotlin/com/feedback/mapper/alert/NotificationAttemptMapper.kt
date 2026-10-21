package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface NotificationAttemptMapper{fun next(@Param("id")id:Long):Int;fun insert(@Param("row")row:NotificationAttemptPo):Int}

