package com.feedback.agent.rca.runtime

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.feedback.agent.rca.domain.RcaSharedState
import com.feedback.agent.rca.tools.RcaCanonicalDigest

/**
 * 状态快照的序列化产物。
 *
 * [sha256] 与 [json] 一起产出，而不是落库时再算一次：两者必须来自同一次序列化。
 * 分开算就会出现「摘要对应的是另一个版本的内容」这种极难发现的情形——
 * 恢复时摘要校验通过，但反序列化出来的是别的状态。
 */
data class RcaSnapshot(val json: String, val sha256: String)

/**
 * 共享状态的快照编解码。
 *
 * 单独成接口而不是直接调用 Jackson，有两个具体理由：
 * 1. **回放与注入测试需要可控的编解码**。注入测试要在「快照已写、状态未迁移」这类
 *    中间点上做文章，必须能替换掉编解码实现，而不是被迫走真实的 JSON 往返。
 * 2. **序列化实现会被替换**。JSON 现在是够用的（状态是普通数据类集合），
 *    但如果将来状态里出现大对象或需要压缩，换实现不应该波及 Worker 的调度逻辑。
 */
interface RcaTaskSnapshotCodec {

    fun encode(state: RcaSharedState): RcaSnapshot

    /**
     * 反序列化。**失败必须抛出而不是返回一个空状态**：
     * 恢复时读不出状态意味着恢复信息已经不完整，此时按空状态继续跑会产出
     * 一份「看起来完整、实际丢了全部证据」的结论，比直接失败危险得多。
     */
    fun decode(json: String): RcaSharedState
}

/**
 * JSON 实现。
 *
 * 三个配置项都不是随手写的：
 * - `JavaTimeModule` + 关闭 `WRITE_DATES_AS_TIMESTAMPS`：时间是 ISO-8601 字符串而不是
 *   时间戳数字。时间戳与数据库 `DATETIME(3)` 的精度不同，落库再读回会漂移，
 *   而状态里多处用时间做相等判断（例如水位、窗口边界）。
 * - 关闭 `FAIL_ON_UNKNOWN_PROPERTIES`：状态结构演进时，旧快照里多出来的字段不应让恢复失败。
 *   这条与上面那条方向相反但目的一致——**能恢复的尽量恢复，不能恢复的必须炸**。
 * - 不用 Spring 容器里的 ObjectMapper：那个实例带业务侧的序列化定制（脱敏、命名策略），
 *   状态快照的格式不应随 Web 层的展示需求变化，否则同一个状态会因为一次展示层改动而摘要不同。
 */
class RcaSnapshotJsonCodec(
    private val mapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
) : RcaTaskSnapshotCodec {

    override fun encode(state: RcaSharedState): RcaSnapshot {
        val json = mapper.writeValueAsString(state)
        return RcaSnapshot(json = json, sha256 = RcaCanonicalDigest.sha256(json))
    }

    override fun decode(json: String): RcaSharedState =
        mapper.readValue(json, RcaSharedState::class.java)
}
