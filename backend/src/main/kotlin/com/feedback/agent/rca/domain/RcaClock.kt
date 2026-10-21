package com.feedback.agent.rca.domain

import java.time.LocalDateTime

/**
 * 可注入时钟。
 *
 * 回放夹具必须在固定时间点上复算，所以 RCA 运行时代码里不允许直接调用 [LocalDateTime.now]：
 * 一旦某处漏了注入，固定回放就会随执行时刻产生不同结果，评测结论也就不可复现。
 *
 * 放在 domain 而不是各包各自声明：工具层与图编排层都要用它，
 * 重复定义会让「哪些时间是注入的」这件事失去单一事实来源。
 */
fun interface RcaClock {
    fun now(): LocalDateTime

    companion object {
        /** 生产实现。 */
        fun system(): RcaClock = RcaClock { LocalDateTime.now() }

        /** 固定时钟，用于回放与测试。 */
        fun fixed(at: LocalDateTime): RcaClock = RcaClock { at }
    }
}
