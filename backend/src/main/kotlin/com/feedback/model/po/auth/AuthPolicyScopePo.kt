package com.feedback.model.po.auth

/** 飞书策略来源数据范围的 MyBatis 投影，不承载 ALLOW/READ 默认规则。 */
data class AuthPolicyScopePo(
    val scopeType: String,
    val scopeValue: String,
)
