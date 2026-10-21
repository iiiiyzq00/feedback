package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface VersionReleaseSourceMapper { fun page(@Param("afterId")afterId:Long,@Param("packages")packages:List<String>,@Param("versions")versions:List<String>,@Param("start")start:LocalDateTime,@Param("end")end:LocalDateTime,@Param("limit")limit:Int):List<VersionSourcePo> }

