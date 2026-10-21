package com.feedback.repository.remote.station

/** Station 用户目录外部端口；目标环境提供 UC/Dubbo 实现，失败时不得猜测或回退联系方式。 */
fun interface StationUserDirectory {
    /** 按受控条件查询 Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun findPhone(userId: String, productCode: String): String?
}
