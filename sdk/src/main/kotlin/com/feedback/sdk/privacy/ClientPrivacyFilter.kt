package com.feedback.sdk.privacy

/** 尽力而为的第一道过滤。服务端仍是权威的脱敏闸门。 */
object ClientPrivacyFilter {
    private val rules = listOf(
        Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)") to "[PHONE]",
        Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}") to "[EMAIL]",
        Regex("(?<!\\d)\\d{17}[0-9Xx](?!\\d)") to "[ID_CARD]",
        Regex("(?<!\\d)\\d{16,19}(?!\\d)") to "[NUMBER_REDACTED]",
        Regex("(?i)(authorization|token|password|secret|cookie)(\\s*[:=]\\s*)([^\\s,;]+)") to "$1$2[REDACTED]",
        Regex("(?<!\\d)(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}(?!\\d)") to "[IP]"
    )
    fun filter(value:String):String = rules.fold(value){safe,(pattern,replacement)->pattern.replace(safe,replacement)}
    fun filter(values:Map<String,String>):Map<String,String> = values.mapValues { filter(it.value) }
}
