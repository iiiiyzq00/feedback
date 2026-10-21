package com.feedback.mapper.scheduler

import com.feedback.model.po.scheduler.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface MaintenanceLockMapper{fun acquire(@Param("name")name:String):Int?;fun release(@Param("name")name:String):Int?}

