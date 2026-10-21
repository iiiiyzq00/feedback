package com.feedback.service.masking

import com.hankcs.hanlp.HanLP
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

fun interface NerMasker {
    fun mask(text: String): NerMaskingResult
}

data class NerMaskingResult(
    val text: String,
    val findings: List<MaskingFinding> = emptyList(),
    val available: Boolean = true
)

object NoopNerMasker : NerMasker {
    override fun mask(text: String) = NerMaskingResult(text)
}

@Component
class HanlpNerMasker(
    @Value("\${app.masking.ner-enabled:true}") private val enabled: Boolean,
    private val allowlist:NerAllowlistRegistry?=null
) : NerMasker {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun mask(text: String): NerMaskingResult {
        if (!enabled || text.isBlank()) return NerMaskingResult(text)
        return runCatching {
            val protected=linkedMapOf<String,String>();var input=text
            allowlist?.activeTerms().orEmpty().forEachIndexed{index,term->if(input.contains(term)){val token="NERPROTECTED${index}TERM";protected[token]=term;input=input.replace(term,token)}}
            val findings = mutableListOf<MaskingFinding>()
            val output = StringBuilder(text.length)
            HanLP.segment(input).forEach { term ->
                val word = term.word
                val nature = term.nature?.toString().orEmpty()
                when {
                    nature.startsWith("nr") && isChineseName(word) -> {
                        findings += MaskingFinding("NAME", word)
                        output.append("[NAME_REMOVED]")
                    }
                    nature.startsWith("nt") && SCHOOL_SUFFIX.containsMatchIn(word) -> {
                        findings += MaskingFinding("SCHOOL", word)
                        output.append("某学校")
                    }
                    else -> output.append(word)
                }
            }
            var restored=output.toString();protected.forEach{(token,term)->restored=restored.replace(token,term)}
            NerMaskingResult(restored, findings)
        }.getOrElse { error ->
            log.error("HanLP NER unavailable; masking gate will require review", error)
            NerMaskingResult(text, available = false)
        }
    }

    private fun isChineseName(value: String): Boolean =
        value.length in 2..4 &&
            value.all { it in '\u3400'..'\u9fff' } &&
            value !in NON_PERSON_TERMS &&
            NON_PERSON_SUFFIXES.none(value::endsWith)

    companion object {
        private val SCHOOL_SUFFIX = Regex("(?:小学|中学|学校|学院|幼儿园|大学)")
        private val NON_PERSON_TERMS = setOf(
            "卡顿", "横屏", "步数", "画布", "页面", "按钮", "弹窗", "截图", "录屏",
            "总览", "趋势图", "工具栏", "状态栏", "画笔", "颜色", "睡眠", "饮水"
        )
        private val NON_PERSON_SUFFIXES = setOf(
            "页面", "画布", "屏", "按钮", "弹窗", "卡片", "图", "栏", "数", "记录", "模块"
        )
    }
}
