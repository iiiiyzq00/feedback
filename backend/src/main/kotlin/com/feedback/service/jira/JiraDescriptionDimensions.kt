package com.feedback.service.jira

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

object JiraDescriptionDimensions {
    private val version = Regex("(?i)V\\d+(?:\\.\\d+){2,3}")
    private val systemVersionLabel = Regex("【系统版本(?:、软件版本、下载渠道)?】\\s*[:：]?", RegexOption.IGNORE_CASE)

    fun extractSystemVersion(description: String?): String? {
        val text = description?.takeIf(String::isNotBlank) ?: return null
        val label = systemVersionLabel.find(text) ?: return null
        return version.find(text.substring(label.range.last + 1).take(300))?.value?.uppercase()
    }

    fun extractControlledIdentities(description: String?): List<Pair<String,String>> {
        val text=description?.takeIf(String::isNotBlank)?:return emptyList()
        return listOfNotNull(
            labeledValue(text, USER_ID_LABEL)?.let { "USER_ID" to it },
            labeledValue(text, DEVICE_SN_LABEL)?.let { "DEVICE_SN" to it },
            combinedModelSerial(text)?.let { "DEVICE_SN" to it },
            (customerPhone(text) ?: PHONE.find(text)?.value)?.let { "PHONE" to it },
            EMAIL.find(text)?.value?.let { "EMAIL" to it }
        ).distinctBy { it.first to it.second }
    }

    fun parseJiraDateTime(value: String, timeZone: String): LocalDateTime? {
        val normalized = value.trim().let { raw ->
            if (Regex(".*[+-]\\d{4}$").matches(raw)) raw.dropLast(2) + ":" + raw.takeLast(2) else raw
        }
        return runCatching { OffsetDateTime.parse(normalized).atZoneSameInstant(ZoneId.of(timeZone)).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(normalized) }.getOrNull()
    }

    private fun labeledValue(text:String,label:Regex):String? {
        val match=label.find(text)?:return null
        return match.groupValues[1].trim().trimEnd(',', '，', ';', '；').take(200).takeIf(String::isNotBlank)
    }

    private val USER_ID_LABEL=Regex("(?im)^(?:【)?(?:用户|账号|反馈人)(?:ID|Id|id)(?:】)?\\s*[:：]\\s*([^\\s,，;；]{1,200})")
    private val DEVICE_SN_LABEL=Regex("(?im)^(?:【)?(?:设备|机器|机身)?(?:SN|Sn|sn|序列号|机身号码)(?:】)?\\s*[:：]\\s*([A-Za-z0-9._:-]{4,200})")
    private val MODEL_SERIAL_LINE=Regex("(?im)^.*【型号与序列号】\\s*[:：]\\s*([^\\r\\n]{1,300})")
    private val CUSTOMER_LINE=Regex("(?im)^.*【客户信息】\\s*[:：]\\s*([^\\r\\n]{1,500})")
    private val PHONE=Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)")
    private val EMAIL=Regex("(?i)(?<![A-Z0-9._%+-])[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}(?![A-Z0-9._%+-])")
    private fun customerPhone(text:String)=CUSTOMER_LINE.find(text)?.groupValues?.get(1)?.let{PHONE.find(it)?.value}
    private fun combinedModelSerial(text:String):String? {
        val value=MODEL_SERIAL_LINE.find(text)?.groupValues?.get(1)?:return null
        return Regex("[A-Za-z0-9][A-Za-z0-9._:-]{9,199}").findAll(value).map{it.value}.lastOrNull()
    }
}
