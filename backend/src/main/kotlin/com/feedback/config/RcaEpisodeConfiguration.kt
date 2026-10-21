package com.feedback.config

import com.feedback.service.masking.IdentityFingerprintService
import com.feedback.agent.rca.domain.EpisodeSessionPolicy
import com.feedback.agent.rca.episode.ContactFingerprintPort
import com.feedback.agent.rca.episode.EpisodeIdentityResolver
import com.feedback.agent.rca.episode.EpisodeSessionizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * 问题对象会话化的装配。
 *
 * 三个 Bean 都是纯计算对象（无状态、无 I/O），之所以还要显式声明，是因为它们各自需要
 * 一个「口径从哪来」的答案，而那个答案必须写在装配处、不能藏在默认值里：
 *
 * - [ContactFingerprintPort]：指纹口径委托给既有的 [IdentityFingerprintService]，
 *   **不在这里实现第二遍 HMAC**。同一个账号在会话化与在 `t_feedback_identity` 里
 *   必须是同一个指纹，让两处共用一个实现是唯一能保证这件事的办法。
 * - [EpisodeSessionPolicy]：策略是版本化对象，落库到 `t_feedback_episode.policy_version`。
 *   装配处显式给出它，避免某天有人在会话化里内联一个 `Duration.ofMinutes(30)`,
 *   于是「这行数据是哪个策略版本算出来的」这个问题再也答不上来。
 * - [EpisodeSessionizer]：无状态纯函数，单例即可。
 *
 * [com.feedback.agent.rca.episode.EpisodeSessionAdapter] 与
 * [com.feedback.agent.rca.episode.EpisodeIntakeService] 自己带
 * `@Component` / `@Service`，不在这里重复声明：它们需要被 Spring 代理
 * （后者的事务边界依赖代理），手工 `@Bean` 声明会绕开代理而让 `@Transactional` 静默失效。
 */
@Configuration(proxyBeanMethods = false)
class RcaEpisodeConfiguration {

    @Bean
    fun contactFingerprintPort(fingerprints: IdentityFingerprintService): ContactFingerprintPort =
        ContactFingerprintPort { value, type -> fingerprints.fingerprint(value, type) }

    @Bean
    fun episodeSessionPolicy(): EpisodeSessionPolicy = EpisodeSessionPolicy.DEFAULT

    @Bean
    fun episodeIdentityResolver(fingerprints: ContactFingerprintPort): EpisodeIdentityResolver =
        EpisodeIdentityResolver(fingerprints)

    @Bean
    fun episodeSessionizer(policy: EpisodeSessionPolicy): EpisodeSessionizer = EpisodeSessionizer(policy)
}
