package com.feedback.service.normalization

import java.time.LocalDateTime

data class FaultTimeFact(
    val occurredAt: LocalDateTime?, val raw: String?, val precision: String?,
    val source: String, val resolution: String
)

object FaultOccurrenceTimeResolver {
    private val jiraLabel = Regex("【\\s*出现故障时间\\s*[（(]精确到\\s*分[）)]\\s*】\\s*[:：]\\s*(.+?)(?=\\s*\\d+[、.]?\\s*【|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val genericLabel = Regex("(?:出现)?故障时间(?:\\s*[（(]精确到\\s*分[）)])?\\s*】?\\s*[:：]\\s*(.+?)(?=\\s*\\d+[、.]?\\s*【|$)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val full = Regex("(20\\d{2})[-/年.](\\d{1,2})[-/月.](\\d{1,2})(?!\\d)(?:日)?(?:[ T,，-]*)((?:[01]?\\d|2[0-3]))(?:[:：点时-](\\d{1,2}))?(?:[:：分-](\\d{1,2}))?")
    private val shortYear = Regex("(?<!\\d)(\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})(?!\\d)(?:[- T]((?:[01]?\\d|2[0-3]))(?::(\\d{1,2}))(?::(\\d{1,2}))?)?")
    private val monthDay = Regex("(?<!\\d)(\\d{1,2})[-/月](\\d{1,2})(?!\\d)(?:日)?(?:[ T,，晚上凌晨早上下午]*)((?:[01]?\\d|2[0-3]))?(?:[:：点时](\\d{1,2}))?(?:[:：分](\\d{1,2}))?")
    private val dayAndTime = Regex("(?<!\\d)(\\d{1,2})(?:号|日)(?:[ T,，]*(凌晨|早上|上午|中午|下午|晚上))?[ T,，]*(\\d{1,2})(?:[:：点时](\\d{1,2}))?(?:[:：分](\\d{1,2}))?(?:\\s*(?:到|至|~|～)\\s*(\\d{1,2})(?:[:：点时](\\d{1,2}))?)?")
    private val timeOnly = Regex("(?:(凌晨|早上|上午|中午|下午|晚上))[ T,，]*(\\d{1,2})(?:[:：点时](\\d{1,2}))?(?:[:：分](\\d{1,2}))?(?:\\s*(?:到|至|~|～)\\s*(\\d{1,2})(?:[:：点时](\\d{1,2}))?)?")
    private val numericTimeOnly = Regex("^\\s*((?:[01]?\\d|2[0-3]))[:：](\\d{1,2})(?::(\\d{1,2}))?\\s*(?:左右|前后|许)?\\s*$")
    private val numericDateRange = Regex("(?<![\\d-])\\d{1,2}[-/.]\\d{1,2}(?:\\s+\\d{1,2}[:：.]\\d{1,2})?\\s*[-~～至到+]\\s*\\d{1,2}[-/.]\\d{1,2}(?!\\d)")

    fun fromDescription(channel: String, text: String?, reference: LocalDateTime?): FaultTimeFact {
        val source = "${channel.uppercase()}_DESCRIPTION"
        val match = if(channel.equals("JIRA",true)) jiraLabel.find(text.orEmpty()) ?: genericLabel.find(text.orEmpty())
            else genericLabel.find(text.orEmpty())
        val raw = match?.groupValues?.get(1)?.trim()?.trim('"', '\'', ',', '，')?.take(255)
            ?.takeIf(String::isNotBlank) ?: return FaultTimeFact(null, null, null, source, "MISSING")
        if (raw.matches(Regex("^\\d+[、.]?\\s*【.*",RegexOption.DOT_MATCHES_ALL)))
            return FaultTimeFact(null, null, null, source, "MISSING")
        parse(raw, reference)?.let { return FaultTimeFact(it.first, raw, it.second, source, "RESOLVED") }
        return FaultTimeFact(null, raw, null, source, "UNPARSEABLE")
    }

    fun sdk(at: LocalDateTime?) = if (at == null) FaultTimeFact(null,null,null,"SDK_CLIENT","MISSING")
        else FaultTimeFact(at,at.toString(),"SECOND","SDK_CLIENT","RESOLVED")

    fun proxy(at:LocalDateTime?,source:String,precision:String) = if(at==null)
        FaultTimeFact(null,null,null,source,"MISSING")
    else FaultTimeFact(at,at.toString(),precision,source,"RESOLVED")

    fun notApplicable() = FaultTimeFact(null,null,null,"REDBLACK_NOT_APPLICABLE","NOT_APPLICABLE")

    private fun parse(raw: String, reference: LocalDateTime?): Pair<LocalDateTime,String>? {
        val normalized=normalizeChineseHour(raw)
        full.find(normalized)?.let { m ->
            acceptable(date(m.groupValues[1],m.groupValues[2],m.groupValues[3],m.groups[4]?.value,m.groups[5]?.value,m.groups[6]?.value,raw),reference)
                ?.let { return it }
        }
        shortYear.find(normalized)?.let { m ->
            // 形如 "05-23 19:59-05-23 20:35" 的区间里含有子串
            // "59-05-23"。它是「分钟 + 区间结束日期」，而不是
            // 2059 年。要拒绝时间上不可能的候选，
            // 继续交给下面的月/日解析器。
            acceptable(date("20${m.groupValues[1]}",m.groupValues[2],m.groupValues[3],m.groups[4]?.value,m.groups[5]?.value,m.groups[6]?.value,raw),reference)
                ?.let { return it }
        }
        monthDay.find(normalized)?.let { m ->
            val ref=reference?:return null
            var year=ref.year
            val candidate=date(year.toString(),m.groupValues[1],m.groupValues[2],m.groups[3]?.value,m.groups[4]?.value,m.groups[5]?.value,raw)?:return null
            if(candidate.first.isAfter(ref.plusDays(1))) year--
            return acceptable(date(year.toString(),m.groupValues[1],m.groupValues[2],m.groups[3]?.value,m.groups[4]?.value,m.groups[5]?.value,raw),reference)
        }
        dayAndTime.find(normalized)?.let { m ->
            val ref=reference?:return null
            return acceptable(relativeDate(ref.year,ref.monthValue,m.groupValues[1].toInt(),m.groups[2]?.value,
                m.groupValues[3],m.groups[4]?.value,m.groups[5]?.value,m.groups[6]?.value,raw),reference)
        }
        timeOnly.find(normalized)?.let { m ->
            val ref=reference?:return null
            return nearestTimeNotAfterReference(ref,m.groups[1]?.value,m.groupValues[2],m.groups[3]?.value,m.groups[4]?.value,m.groups[5]?.value,raw)
        }
        numericTimeOnly.matchEntire(normalized)?.let { m ->
            val ref=reference?:return null
            return nearestTimeNotAfterReference(ref,null,m.groupValues[1],m.groupValues[2],m.groups[3]?.value,null,raw)
        }
        return null
    }

    private fun acceptable(candidate:Pair<LocalDateTime,String>?,reference:LocalDateTime?) =
        candidate?.takeUnless { reference != null && it.first.isAfter(reference.plusDays(1)) }

    private fun nearestTimeNotAfterReference(reference:LocalDateTime,period:String?,hour:String,minute:String?,second:String?,endHour:String?,raw:String):Pair<LocalDateTime,String>? {
        val candidate=relativeDate(reference.year,reference.monthValue,reference.dayOfMonth,period,hour,minute,second,endHour,raw)?:return null
        return if(candidate.first.isAfter(reference)) candidate.first.minusDays(1) to candidate.second else candidate
    }

    private fun relativeDate(year:Int,month:Int,day:Int,period:String?,hourRaw:String,minute:String?,second:String?,endHour:String?,raw:String):Pair<LocalDateTime,String>? = try {
        var hour=hourRaw.toInt()
        if(period in setOf("下午","晚上") && hour in 1..11)hour+=12
        if(period=="中午" && hour in 1..10)hour+=12
        if(period=="凌晨" && hour==12)hour=0
        val precision=if(endHour!=null||raw.contains("到")||raw.contains("至")||raw.contains('~')||raw.contains('～'))"RANGE"
            else if(second!=null)"SECOND" else if(minute!=null)"MINUTE" else "HOUR"
        LocalDateTime.of(year,month,day,hour,minute?.toIntOrNull()?:0,second?.toIntOrNull()?:0) to precision
    } catch (_:Exception){null}

    private fun normalizeChineseHour(value:String)=Regex("[零一二三四五六七八九十两]{1,3}(?=[点时])").replace(value) { match ->
        val chars=match.value.replace('两','二')
        val digits=mapOf('零' to 0,'一' to 1,'二' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        when {
            chars=="十" -> 10
            chars.startsWith('十') -> 10+(digits[chars.last()]?:0)
            chars.contains('十') -> (digits[chars.first()]?:0)*10+(digits[chars.last()]?:0)
            else -> chars.mapNotNull(digits::get).joinToString("").toIntOrNull()?:0
        }.toString()
    }

    private fun date(y:String,mo:String,d:String,h:String?,mi:String?,s:String?,raw:String):Pair<LocalDateTime,String>? = try {
        val precision=when { raw.contains('~')||raw.contains('～')||raw.contains("到")||raw.contains("和")||numericDateRange.containsMatchIn(raw) -> "RANGE"; s!=null -> "SECOND"; mi!=null -> "MINUTE"; h!=null -> "HOUR"; else -> "DAY" }
        LocalDateTime.of(y.toInt(),mo.toInt(),d.toInt(),h?.toIntOrNull()?:0,mi?.toIntOrNull()?:0,s?.toIntOrNull()?:0) to precision
    } catch (_:Exception) { null }
}
