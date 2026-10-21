package com.feedback.repository

import com.feedback.model.audit.SystemAuditLog
import com.feedback.mapper.core.CoreSystemAuditMapper
import com.feedback.model.po.core.CoreSystemAuditPo
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Repository

@Repository
class SystemAuditLogRepository(private val mapper: CoreSystemAuditMapper, private val json: ObjectMapper) {
    fun save(row: SystemAuditLog): SystemAuditLog {
        val po = CoreSystemAuditPo(row.id, row.userId, row.operation, row.targetType, row.targetId,
            json.writeValueAsString(row.operationDetail), row.ipAddress, row.createdAt)
        check(mapper.insert(po) == 1) { "SYSTEM_AUDIT_INSERT_FAILED" }
        row.id = po.id
        return row
    }
    fun findAll(): List<SystemAuditLog> = mapper.findAll().map { row ->
        SystemAuditLog(row.id, row.userId, row.operation, row.targetType, row.targetId,
            json.readTree(row.operationDetailJson), row.ipAddress, row.createdAt)
    }
}
