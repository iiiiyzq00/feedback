package com.feedback.service.cluster

import com.feedback.model.feedback.FeedbackContent
import com.feedback.repository.cluster.ClusterProfileFactRepository
import org.springframework.stereotype.Component

/**
 * 一条候选记录里**允许离开进程**的那部分文本。
 *
 * `description` 与 `functionModule` / `issueCategory` 放在一起，是因为它们出自同一批
 * 「脱敏判定为 PASS 才保留」的列——不是「这几个字段看起来不敏感」。
 * 这个区别是本节全部分量所在：判断依据只有 **`t_feedback_content.masking_status`**。
 */
data class RerankDocument(
    val title: String?,
    val description: String?,
    val functionModule: String?,
    val issueCategory: String?
) {
    /** 拼接成送给重排模型的一段。字段顺序固定，让同一条记录永远得到同一个字符串。 */
    fun render(): String = listOfNotNull(
        title?.trim()?.takeIf(String::isNotEmpty)?.let { "标题：$it" },
        functionModule?.trim()?.takeIf(String::isNotEmpty)?.let { "模块：$it" },
        issueCategory?.trim()?.takeIf(String::isNotEmpty)?.let { "分类：$it" },
        description?.trim()?.takeIf(String::isNotEmpty)?.let { "描述：$it" }
    ).joinToString("\n")
}

/**
 * 按 sourceId 取「可用于重排的正文」。
 *
 * ## 这个端口存在的唯一理由是**脱敏边界**
 *
 * 重排要把候选正文送出进程（给模型）。平台对正文的既定规则是：
 * 非 PASS 或已删除的记录，其正文**在库里那一层就是 NULL**
 * （`ClusterProfileFactRepository.loadShadowSafe` 的 SQL 空化，
 * 并由 `CLUSTER_SHADOW_RESTRICTED_CONTENT_EXPOSED` 兜底断言）。
 *
 * 所以本端口**只允许有一个读取途径**：`loadShadowSafe`。用 `load` 会把未脱敏正文读出来，
 * 而它在类型上完全合法——这就是为什么这里不是「记得用哪个方法」，而是把方法藏起来。
 *
 * ## 拿不到正文是常态，不是错误
 *
 * 返回的 Map **允许少于入参**：非 PASS 的行、已删除的行、以及库里就没有的行都不返回。
 * 调用方必须把「有候选没拿到正文」当成**跳过重排**的理由而不是「当作空描述继续排」——
 * 后者会让模型在看不到内容的情况下给它一个名次，而那个名次与随机无异。
 */
interface CandidateRerankDocuments {
    fun load(sourceIds: List<Long>): Map<Long, RerankDocument>
}

/**
 * 生产实现：走 `loadShadowSafe`，把「哪些行会被空化」这件事交给它的 SQL。
 *
 * 本类刻意不在这里再判一次 `maskingStatus == "PASS" && !isDeleted`：
 * 那会是同一规则的第二个实现，两份实现迟早会分叉，而分叉的那一刻泄漏已经发生了。
 * 这里只做一件 SQL 不做的事——把空化后的行丢掉，让「拿不到」在类型上就是「不存在」。
 */
@Component
class ShadowSafeCandidateRerankDocuments(
    private val facts: ClusterProfileFactRepository
) : CandidateRerankDocuments {

    override fun load(sourceIds: List<Long>): Map<Long, RerankDocument> {
        val ids = sourceIds.distinct().filter { it > 0 }
        if (ids.isEmpty()) return emptyMap()
        val contents = runCatching { facts.loadShadowSafe(ids).contents }.getOrElse { return emptyMap() }
        return ids.mapNotNull { id ->
            contents[id]?.toRerankDocument()?.takeIf { it.render().isNotBlank() }?.let { id to it }
        }.toMap()
    }

    private fun FeedbackContent.toRerankDocument() = RerankDocument(
        title = title, description = description,
        functionModule = functionModule, issueCategory = issueCategory
    )
}
