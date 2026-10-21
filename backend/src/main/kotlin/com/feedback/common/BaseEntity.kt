package com.feedback.common

import java.time.LocalDateTime

/**
 * 实体基类 — 所有表共用的审计字段
 */
abstract class BaseEntity {
    var createdAt: LocalDateTime = LocalDateTime.now()
    var updatedAt: LocalDateTime = LocalDateTime.now()
    fun preUpdate() {
        updatedAt = LocalDateTime.now()
    }
}
