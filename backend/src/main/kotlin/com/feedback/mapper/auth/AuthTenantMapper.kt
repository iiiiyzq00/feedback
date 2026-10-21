package com.feedback.mapper.auth

import org.apache.ibatis.annotations.Mapper

/**
 * 租户注册表 Mapper。
 *
 * **只有读方法。** 租户的建立与停用是部署期治理动作，本仓库没有对应入口（V245 只建了表与默认租户），
 * 所以这里不提供写入，免得出现一个「能建租户但没有任何地方用到」的 API。
 *
 * 读取存在的意义是让「给账号分配租户」这一个写动作能在**落库之前**校验目标租户存在：
 * 否则调用方拿到的是外键抛出的持久化异常，分不清是租户填错了还是数据库出了问题，
 * 而这两件事的处置方式完全不同（改参数 vs 报障）。
 */
@Mapper
interface AuthTenantMapper {
    /** 读取全部**可分配**租户键；SUSPENDED 的租户不在其中。 */
    fun findActiveKeys(): List<String>
}
