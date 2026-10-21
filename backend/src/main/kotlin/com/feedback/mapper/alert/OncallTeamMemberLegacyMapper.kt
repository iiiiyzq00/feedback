package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface OncallTeamMemberLegacyMapper{fun clearPrimary(@Param("team")team:String):Int;fun upsert(@Param("team")team:String,@Param("user")user:Long,@Param("role")role:String,@Param("primary")primary:Boolean):Int;fun remove(@Param("id")id:Long):Int}

