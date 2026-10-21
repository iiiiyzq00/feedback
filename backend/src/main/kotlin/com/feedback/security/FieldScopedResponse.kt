package com.feedback.security

import com.feedback.model.auth.DataFieldPolicy
import com.fasterxml.jackson.annotation.JsonFilter
import com.fasterxml.jackson.annotation.JsonIgnore
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 反馈响应树中需要字段级控制的统一边界。
 *
 * 策略在请求开始时确定，仅用于序列化期的纯内存过滤，且永不进入公开 JSON 契约。
 */
@JsonFilter("feedbackDataFieldPolicy")
interface FieldScopedResponse {
    @get:JsonIgnore
    @get:Schema(hidden = true)
    val fieldPolicy: DataFieldPolicy
}
