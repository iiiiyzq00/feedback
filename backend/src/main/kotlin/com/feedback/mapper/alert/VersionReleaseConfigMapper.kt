package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface VersionReleaseConfigMapper {
 fun list():List<VersionReleasePo>
 fun activeBefore(@Param("end")end:LocalDateTime):List<VersionReleasePo>
 fun update(@Param("packageName")packageName:String,@Param("appVersion")appVersion:String,@Param("controlVersion")controlVersion:String,@Param("releasedAt")releasedAt:LocalDateTime,@Param("status")status:String):Int
 fun insert(@Param("packageName")packageName:String,@Param("appVersion")appVersion:String,@Param("controlVersion")controlVersion:String,@Param("releasedAt")releasedAt:LocalDateTime,@Param("status")status:String,@Param("actor")actor:Long):Int
}

