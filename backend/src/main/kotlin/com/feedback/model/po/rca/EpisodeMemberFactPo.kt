package com.feedback.model.po.rca

/**
 * 一次 join 的结果行：成员背后的身份事实。
 *
 * 出现在这里的是 [fingerprint] 的**原始字节**而不是 `HEX(...)` 或 `TO_BASE64(...)`；
 * 编码放在 Kotlin 侧做，是刻意的：库里的 `BINARY(32)` 是唯一的事实，
 * 让 SQL 决定「怎么呈现指纹」会让编码规则多出一个 SQL 实现，
 * 而只要它和领域层的编码不一致，「已见过的身份」集合就会静默多出条目。
 */
data class EpisodeMemberFactPo(
    var identityType: String = "",
    var fingerprint: ByteArray = ByteArray(0)
)
