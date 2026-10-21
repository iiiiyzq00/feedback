package com.feedback.service.report

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Service
import java.math.BigDecimal

data class ReportNumberValidation(val text:String?,val status:String,val errors:List<Map<String,Any?>>)

@Service
class ReportNumberValidator {
    private val number=Regex("(?<![A-Za-z0-9_])[-+]?\\d[\\d,]*(?:\\.\\d+)?%?")
    fun validate(text:String?,facts:JsonNode):ReportNumberValidation {
        if(text==null)return ReportNumberValidation(null,"PASS",emptyList())
        val protectedRanges=Regex("(?<![0-9A-Za-z])\\d{4}-\\d{2}-\\d{2}(?:[T ][0-9:.+Z-]+)?(?![0-9A-Za-z])|(?<![0-9A-Za-z])v?\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?(?![0-9A-Za-z])").findAll(text).map{it.range}.toList()
        val metrics=linkedMapOf<String,MutableList<String>>()
        flatten(facts,"$",metrics)
        val invalidRanges=mutableListOf<IntRange>()
        number.findAll(text).forEach{match->
            if(protectedRanges.any{match.range.first>=it.first&&match.range.last<=it.last})return@forEach
            val keys=if(match.value.endsWith('%'))setOf(normalize(match.value),normalize(match.value.removeSuffix("%")))else setOf(normalize(match.value))
            val paths=keys.flatMap{metrics[it].orEmpty()}.distinct()
            if(paths.isEmpty())invalidRanges+=match.range
        }
        if(invalidRanges.isEmpty())return ReportNumberValidation(text,"PASS",emptyList())
        val sentenceBreaks=setOf('。','！','？','；','\n')
        val removals=invalidRanges.map{range->
            var start=range.first
            while(start>0&&text[start-1] !in sentenceBreaks)start--
            var end=range.last
            while(end<text.lastIndex&&text[end] !in sentenceBreaks)end++
            start..end
        }.sortedBy{it.first}.fold(mutableListOf<IntRange>()){merged,next->
            val previous=merged.lastOrNull()
            if(previous!=null&&next.first<=previous.last+1)merged[merged.lastIndex]=previous.first..maxOf(previous.last,next.last) else merged+=next
            merged
        }
        val cleaned=buildString{text.forEachIndexed{index,char->if(removals.none{index in it})append(char)}}.replace(Regex("\\n{3,}"),"\n\n").trim().takeIf{it.isNotBlank()}
        return ReportNumberValidation(cleaned,"PASS",emptyList())
    }
    private fun flatten(node:JsonNode,path:String,out:MutableMap<String,MutableList<String>>){when{node.isObject->node.fields().forEach{(k,v)->flatten(v,"$path.$k",out)};node.isArray->node.forEachIndexed{i,v->flatten(v,"$path[$i]",out)};node.isNumber->{val value=normalize(node.asText());out.getOrPut(value){mutableListOf()}+=path;if(value.startsWith('-'))out.getOrPut(value.removePrefix("-")){mutableListOf()}+=path}}}
    private fun normalize(raw:String):String {val percent=raw.endsWith('%');val clean=raw.removeSuffix("%").replace(",","");val value=runCatching{BigDecimal(clean).stripTrailingZeros().toPlainString()}.getOrDefault(clean);return if(percent)BigDecimal(clean).divide(BigDecimal(100)).stripTrailingZeros().toPlainString() else value}
}
