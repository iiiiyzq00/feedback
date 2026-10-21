package com.feedback.strategy.applicationidentity

import com.feedback.model.applicationidentity.*

import com.feedback.service.applicationidentity.*

import com.feedback.model.applicationidentity.ApplicationIdentitySafeText
import com.feedback.model.applicationidentity.ApplicationIdentitySnapshot
import org.springframework.stereotype.Component
import java.text.Normalizer
import java.util.Locale

/** 规范应用名在安全文本中的封闭匹配结果。 */
sealed interface CanonicalApplicationTextMatch {
    /** 未命中任何唯一规范名。 */
    data object Unresolved : CanonicalApplicationTextMatch
    /** 唯一命中一个既有 identity。 */
    data class Resolved(val identity: ApplicationIdentitySnapshot) : CanonicalApplicationTextMatch
    /** 同时命中多个 identity，必须失败关闭。 */
    data class Ambiguous(val identityPublicIds: Set<String>) : CanonicalApplicationTextMatch
}

/** 规范应用名精确提及匹配器；绝不从反馈正文学习或扩充应用名称。 */
@Component
class CanonicalApplicationTextMatcher {
    /** 仅对 masking PASS 文本做精确规范名提及匹配，零/多命中均失败关闭。 */
    fun match(
        text: ApplicationIdentitySafeText?,
        identities: Collection<ApplicationIdentitySnapshot>
    ): CanonicalApplicationTextMatch {
        if (text == null) {
            return CanonicalApplicationTextMatch.Unresolved
        }
        val corpus = normalize(listOfNotNull(text.title, text.description).joinToString("\n"))
        if (corpus.isBlank()) {
            return CanonicalApplicationTextMatch.Unresolved
        }
        val matched = identities.filter { identity ->
            val canonicalName = normalize(identity.displayName)
            canonicalName.isNotBlank() && containsExactMention(corpus, canonicalName)
        }.distinctBy { it.id }
        return when (matched.size) {
            0 -> CanonicalApplicationTextMatch.Unresolved
            1 -> CanonicalApplicationTextMatch.Resolved(matched.single())
            else -> CanonicalApplicationTextMatch.Ambiguous(matched.map { it.publicId }.toSet())
        }
    }

    /** ASCII 名称要求左右词边界，避免短应用名命中较长英文标识。 */
    private fun containsExactMention(corpus: String, name: String): Boolean {
        var start = corpus.indexOf(name)
        while (start >= 0) {
            val end = start + name.length
            val leftOk = !name.first().isAsciiLetterOrDigit() || start == 0 ||
                !corpus[start - 1].isAsciiLetterOrDigit()
            val rightOk = !name.last().isAsciiLetterOrDigit() || end == corpus.length ||
                !corpus[end].isAsciiLetterOrDigit()
            if (leftOk && rightOk) {
                return true
            }
            start = corpus.indexOf(name, start + 1)
        }
        return false
    }

    /** 对审核安全文本执行 NFKC 与固定 Locale 小写转换。 */
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)

    /** 仅 ASCII 字母数字参与英文词边界，中文名称按完整子串处理。 */
    private fun Char.isAsciiLetterOrDigit(): Boolean = this in 'a'..'z' || this in '0'..'9'
}
