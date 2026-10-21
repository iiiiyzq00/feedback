package com.feedback.mapper.audit
import com.feedback.model.po.audit.SystemAuditInsertPo
import org.apache.ibatis.annotations.Mapper
@Mapper interface SystemAuditMutationMapper { fun insert(audit:SystemAuditInsertPo):Int }
