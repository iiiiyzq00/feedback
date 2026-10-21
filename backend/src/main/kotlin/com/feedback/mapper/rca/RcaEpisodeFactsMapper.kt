package com.feedback.mapper.rca

import com.feedback.model.po.rca.RcaEpisodeFactsPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/**
 * Episode 事实来源的语句集合。只读。
 *
 * 三条语句分开而不是一条宽表查到底，理由是**可用性不同**：
 * `loadEpisodeFacts` 与 `findAffectedVersions` 打在 V242 与既有业务表上，缺任何一边
 * 都说明这套运行时根本没被正确部署，理应报错；而 `findActiveControlVersion` 打的是
 * 版本发布治理表 `t_app_version_release_fact`（V98），它是**可选增强**——
 * 缺它只影响「版本对照基准」这一个字段，不该让整个事实装配失败。
 * 这条差别在 `MyBatisRcaEpisodeFactsRepositoryImpl` 里被显式处理，不在 SQL 层混合。
 */
@Mapper
interface RcaEpisodeFactsMapper {

    /**
     * 取一个 Episode 的事实行；不存在返回 `null`（调用方据此转人工，不产出空结论）。
     */
    fun loadEpisodeFacts(@Param("episodeId") episodeId: Long): RcaEpisodeFactsPo?

    /**
     * 该 Episode 的 ACTIVE 成员覆盖到的应用版本，去重后按版本号升序。
     *
     * 过滤掉 NULL 与空串：版本号缺失的成员不该让「跨版本」这条判据被误触发——
     * 把「读不到版本」当成「来自另一个版本」会凭空造出一次版本集中度结论。
     */
    fun findAffectedVersions(@Param("episodeId") episodeId: Long): List<String>

    /**
     * 该应用当前生效的版本对照基准。
     *
     * 取 `release_status='ACTIVE'` 里最新发布的一条。这是**平台声明的**当前线上稳定对照版本，
     * 不是程序按本 Episode 的受影响版本挑出来的——`ProblemProfile.controlVersion` 的注释
     * 明确要求由事实层给出，因为自动挑对照组会让同一份数据在不同时间跑出不同结论。
     *
     * 无匹配返回 `null`，语义与「事实不足、不做版本集中度结论」一致。
     */
    fun findActiveControlVersion(@Param("packageName") packageName: String): String?
}
