package com.feedback.mapper.audit
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
@Mapper interface BusinessAuditLockMapper { fun acquire():Int?; fun release():Int?; fun canonicalize(@Param("detailJson") detailJson:String):String }
