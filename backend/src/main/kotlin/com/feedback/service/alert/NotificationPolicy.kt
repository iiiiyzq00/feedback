package com.feedback.service.alert

import org.springframework.stereotype.Component

data class SeverityNotificationPolicy(val required:Set<String>,val allowed:Set<String>,val mode:String,val schedule:String?=null,val version:String="v1")

@Component
class NotificationPolicy {
    val policies=mapOf(
        "P0" to SeverityNotificationPolicy(CHANNELS,CHANNELS,"IMMEDIATE"),
        "P1" to SeverityNotificationPolicy(setOf("FEISHU_CARD","EMAIL","IN_APP"),setOf("FEISHU_CARD","FEISHU_DM","FEISHU_TASK","SMS","EMAIL","IN_APP"),"IMMEDIATE",version="v2"),
        "P2" to SeverityNotificationPolicy(setOf("FEISHU_CARD","IN_APP"),setOf("FEISHU_CARD","EMAIL","IN_APP"),"IMMEDIATE"),
        "P3" to SeverityNotificationPolicy(setOf("IN_APP"),setOf("IN_APP"),"DAILY_DIGEST","09:00")
    )
    fun resolve(severity:String,configured:List<String>):Pair<List<String>,SeverityNotificationPolicy>{
        val policy=policies[severity]?:alertAbort("NOTIFICATION_SEVERITY_INVALID")
        val normalized=configured.map(::normalize).toSet();ensureAlert(normalized.all{it in policy.allowed}){"NOTIFICATION_CHANNEL_NOT_ALLOWED_FOR_SEVERITY"}
        return (policy.required+normalized).sorted() to policy
    }
    fun view()=policies.map{(severity,p)->mapOf("severity" to severity,"requiredChannels" to p.required.sorted(),"allowedChannels" to p.allowed.sorted(),"deliveryMode" to p.mode,"schedule" to p.schedule,"policyVersion" to p.version)}.sortedBy{it["severity"].toString()}
    fun normalize(raw:String)=when(raw.uppercase()){"INAPP"->"IN_APP";"FEISHU"->"FEISHU_CARD";else->raw.uppercase()}
    companion object{val CHANNELS=setOf("FEISHU_CARD","FEISHU_ALL","FEISHU_DM","FEISHU_TASK","SMS","VOICE_CALL","EMAIL","IN_APP")}
}
