package com.feedback.service.sync

import org.slf4j.LoggerFactory

/**
 * 把任意 [Reranker] 收进「只能重排、不能改集合、不许抛出」这三条约束里。
 *
 * ## 为什么这一层不能省
 *
 * 融合结果的**集合**承载着一组召回保证，顺序才是重排有权碰的东西：
 *
 * | 被破坏的性质 | 后果 |
 * |---|---|
 * | 元素被删 | `ClusteringEngine` 用返回条数判断「召回窗口是否被截断」（`derivedRecallLimitHit`）。删掉几条会把一次正常的截断报成「没截断」 |
 * | 元素被加 | `textRank` / `vectorRank` 是「这条确实被某个引擎召回了」的证据。凭空加一条就是伪造一次召回，而下游会据此开始查一个从未被召回的簇 |
 * | 抛异常 | 归簇是核心写入路径。一次重排故障不该让一次反馈分类整个失败——重排是**增益**，不是前置条件 |
 *
 * ## 与「模型输出必须被校验」的关系
 *
 * LLM 实现自己也会把输出规整成排列（见 `LlmCandidateReranker`），但那只是**实现的选择**；
 * 本层是**框架的保证**。区别在兜底的时机：实现自己规整是「尽量不要出错」，
 * 本层是「出错了也不会传下去」。两者都要有——只有前者时，一个实现缺陷会直接变成线上数据缺陷。
 *
 * ## 短路条件
 *
 * 少于两条候选时直接返回，连委托都不调：一条没有顺序可言，
 * 而模型实现的一次调用是要花钱的。
 */
class GuardedReranker(private val delegate: Reranker) : Reranker {

    override fun rerank(query: String, candidates: List<HybridCandidate>): List<HybridCandidate> {
        if (candidates.size < 2) return candidates

        val proposed = try {
            delegate.rerank(query, candidates)
        } catch (error: Exception) {
            // 记 warn 而不是 error：重排失败是**预期内的**运行状态（模型超时、配额用尽、
            // 未配置），不是本层有缺陷。error 级别会与「实现写错了」混成同一档告警。
            logger.warn("RERANK_FAILED size={}：重排抛出异常，保持融合顺序", candidates.size, error)
            return candidates
        }

        if (!isPermutationOf(candidates, proposed)) {
            // 这一条才是 error 级别的：走到这里说明某个实现违反了契约。
            logger.error(
                "RERANK_ORDER_INVALID in={} out={}：重排结果不是入参的排列，保持融合顺序",
                candidates.size, proposed.size
            )
            return candidates
        }
        return proposed
    }

    /**
     * 逐元素的排列判定，用**计数**而不是去重后的集合。
     *
     * 用集合判定会让「把 A 重复两次、丢掉 B」这种形状通过：去重后两边都只剩 A 和另一个 id，
     * 看起来相等，而返回的列表里 B 已经不见了。sourceId 在融合结果里本来就不会重复
     * （它由 `textRanks.keys + vectorRanks.keys` 生成），所以重复只可能来自实现本身——
     * 那正是这里要拦的东西。
     */
    private fun isPermutationOf(original: List<HybridCandidate>, proposed: List<HybridCandidate>): Boolean {
        if (original.size != proposed.size) return false
        if (proposed.any { it.sourceId <= 0 }) return false
        val counts = HashMap<Long, Int>(original.size)
        original.forEach { counts[it.sourceId] = (counts[it.sourceId] ?: 0) + 1 }
        proposed.forEach { candidate ->
            val remaining = counts[candidate.sourceId] ?: return false
            if (remaining == 1) counts.remove(candidate.sourceId) else counts[candidate.sourceId] = remaining - 1
        }
        return counts.isEmpty()
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(GuardedReranker::class.java)
    }
}
