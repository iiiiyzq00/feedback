package com.feedback.model.result.feedback

data class FeedbackAccessResult(
    val sourceId: Long,
    val sourceChannel: String,
    val dataEnvironment: String,
    val functionModule: String?,
    val provinceCode: String?,
    val cityCode: String?,
    val maskingStatus: String,
    val deleted: Boolean,
    /**
     * 数据归属租户，访问守卫据此判定跨租户。
     *
     * 该值由 SQL 现算（来源 → 主问题事实 → 应用身份，V245 第 4 节），不是 `t_feedback_source`
     * 上的列：租户是**派生事实**，冗余成列就会漂移。应用身份未解析时由 SQL 退到 `'DEFAULT'`，
     * 因此这里永远非空，不会出现「租户未知」这种需要调用方各自解释的状态。
     */
    val tenantKey: String
)
