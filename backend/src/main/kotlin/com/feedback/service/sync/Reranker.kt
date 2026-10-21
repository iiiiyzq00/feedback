package com.feedback.service.sync

/**
 * 重排端口：插在 RRF 融合之后、`.take(topK)` 截断之前的一道可选重排。
 *
 * ## 为什么是一个「接口」而不是在 [HybridCandidateRetriever] 里写一段排序
 *
 * 融合阶段的口径是**可复算**的：两个引擎各自给出名次，`1/(K+rank)` 求和，全程没有
 * 任何外部依赖，同一份输入永远得到同一个输出。重排恰恰相反——它要么依赖一个模型
 * （不确定、有成本、会失败），要么依赖一组只有线上才有的特征。把这两件事分开，
 * 「召回为什么变慢/变贵/变差」才有唯一的怀疑对象；混在一起就只能整体关掉。
 *
 * 生产实现见 `service/cluster/LlmCandidateReranker`，它需要簇域的正文与脱敏规则，
 * 因此不放在本包——本包保持对簇域无依赖。
 *
 * ## 契约：只能重排，不能改集合
 *
 * 实现**必须**返回入参的一个排列。增删元素会破坏调用方依赖的两条性质，见
 * [GuardedReranker] 的说明。这条约束由 [GuardedReranker] 兜底，但它是契约的一部分，
 * 不是「框架替你收拾」的意思：一个总靠兜底才合法的实现，等价于把重排整个关掉，
 * 却仍然要付模型调用的钱。
 *
 * ## 与 `Reranker.NONE` 的区别
 *
 * [NONE] 是「这一层没有重排」，不是「默认安全」。默认注入它，行为与引入本端口之前
 * **逐字节相同**（同一个列表、同一个顺序）。这与
 * `agent/rca/runtime/RcaRecoveryResolver.NONE` 是同一条纪律。
 */
fun interface Reranker {

    /**
     * @param query 触发本次召回的问题描述（`HybridCandidateRetriever.retrieve` 的 `text`）。
     *   原样传入，不在本层截断或归一：查询改写是另一件事，混进来会让「重排前后差在哪」
     *   无法归因到一个原因上。
     * @param candidates RRF 融合后的**完整**列表（已按融合分降序、同分按 sourceId 升序），
     *   **不是**截断后的窗口。传入完整列表，实现才可能把一条本该被截掉的候选提上来。
     * @return 入参的一个排列。
     */
    fun rerank(query: String, candidates: List<HybridCandidate>): List<HybridCandidate>

    companion object {
        /** 恒等重排。默认值，行为与没有本端口时完全一致。 */
        val NONE: Reranker = Reranker { _, candidates -> candidates }
    }
}
