package com.feedback.mapper.alert

import com.feedback.model.query.alert.*
import com.feedback.model.po.alert.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface OncallScheduleLegacyMapper{fun insert(@Param("row")row:OncallLegacyInsertPo):Int;fun lockTeam(@Param("id")id:Long):String?;fun cancel(@Param("id")id:Long,@Param("version")version:Long):Int}

