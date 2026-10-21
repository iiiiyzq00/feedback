package com.feedback.service.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.feedback.model.auth.DataAccessScope
import com.feedback.service.auth.DataPermissionService
import com.feedback.security.WebPrincipal
import com.feedback.model.auth.PlatformPermissions
import com.feedback.model.auth.RoleCode
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service

@Service
class ReportAccessPolicy(
    private val permissions: DataPermissionService,
    private val mapper: ObjectMapper
) {
    private val auditorTypes=setOf("QUALITY_LINKAGE","REDBLACK_ANALYSIS")

    fun requireGenerate(type:String) {
        val principal=principal()
        if(PlatformPermissions.GENERATE_REPORTS !in principal.permissions&&RoleCode.ADMIN !in principal.roles) reportForbidden("REPORT_GENERATE_FORBIDDEN")
        if(RoleCode.ADMIN !in principal.roles&&RoleCode.OPERATOR !in principal.roles) reportForbidden("REPORT_TYPE_FORBIDDEN")
        if(type.uppercase() in auditorTypes&&RoleCode.ADMIN !in principal.roles&&RoleCode.OPERATOR !in principal.roles) reportForbidden("REPORT_TYPE_FORBIDDEN")
    }

    fun canView(type:String,scopeJson:Any?):Boolean {
        val principal=runCatching(::principal).getOrNull()?:return false
        if(RoleCode.ADMIN in principal.roles)return true
        if(PlatformPermissions.AI_REPORTS !in principal.permissions)return false
        if(RoleCode.OPERATOR !in principal.roles&&!(RoleCode.AUDITOR in principal.roles&&type in auditorTypes))return false
        val reportScope=runCatching{parseScope(scopeJson)}.getOrNull()?:return false
        return contains(permissions.scope(),reportScope)
    }

    private fun contains(current:DataAccessScope,report:DataAccessScope):Boolean {
        if(!current.channels.containsAll(report.channels))return false
        if(current.modules!=null&&(report.modules==null||!current.modules.containsAll(report.modules)))return false
        if(current.regions!=null&&(report.regions==null||!current.regions.containsAll(report.regions)))return false
        return true
    }

    private fun parseScope(value:Any?):DataAccessScope {
        if(value==null)return DataAccessScope(setOf("__UNSCOPED__"),emptySet(),emptySet())
        val node=mapper.readTree(value.toString())
        fun values(name:String):Set<String>? {
            val item=node.get(name)?:return null
            if(item.isNull)return null
            return item.map{it.asText().uppercase()}.toSet()
        }
        return DataAccessScope(values("channels").orEmpty(),values("modules"),values("regions"))
    }

    private fun principal()=SecurityContextHolder.getContext().authentication?.principal as? WebPrincipal
        ?:reportUnauthorized("REPORT_AUTH_REQUIRED")
}
