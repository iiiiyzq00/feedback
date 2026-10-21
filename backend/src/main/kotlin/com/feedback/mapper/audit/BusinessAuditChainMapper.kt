package com.feedback.mapper.audit
import com.feedback.model.po.audit.BusinessAuditChainPo
import com.feedback.model.po.audit.BusinessAuditInsertPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
@Mapper interface BusinessAuditChainMapper { fun latestHash():String?; fun insert(@Param("row") row:BusinessAuditInsertPo):Int; fun findLatest(@Param("limit") limit:Int):List<BusinessAuditChainPo> }
