package com.feedback.agent.rca.tools

import com.feedback.agent.rca.domain.RcaToolInvoker
import com.feedback.agent.rca.domain.RcaToolName

/**
 * 工具注册表。
 *
 * 构造时校验**每个枚举成员都有实现**，缺一个直接启动失败。这是刻意的选择：
 * 如果允许缺实现，运行时模型选到那个工具会落到「未知工具」分支，而模型会把
 * 「工具不存在」理解成「查了没问题」，正好是设计里最想避免的语义混淆。
 *
 * 注册表只持有只读工具。[RcaToolInvoker] 本身没有写方法，因此「Agent 不能修改业务数据」
 * 是由类型系统保证的，不依赖调用方自觉。
 */
class RcaToolRegistry(invokers: List<RcaToolInvoker>) {

    private val byName: Map<RcaToolName, RcaToolInvoker> = invokers.associateBy(RcaToolInvoker::tool)

    init {
        val missing = RcaToolName.entries.filterNot(byName::containsKey)
        require(missing.isEmpty()) {
            "以下工具声明了但没有实现：${missing.joinToString { it.storageCode }}"
        }
        val duplicated = invokers.groupBy(RcaToolInvoker::tool).filterValues { it.size > 1 }.keys
        require(duplicated.isEmpty()) {
            "以下工具重复注册：${duplicated.joinToString { it.storageCode }}"
        }
    }

    fun require(name: RcaToolName): RcaToolInvoker =
        byName[name] ?: error("工具 ${name.storageCode} 未注册，注册表构造时本应拦截")

    fun find(name: RcaToolName): RcaToolInvoker? = byName[name]

    /** 当前可用的主线工具；预算紧张时优先保留它们。 */
    fun availableMainline(): List<RcaToolName> = RcaToolName.MAINLINE.filter(byName::containsKey)
}
