package com.feedback.service.station

import com.fasterxml.jackson.databind.JsonNode
import com.feedback.model.station.PreparedStationRawPayload
import java.time.LocalDateTime

/** Station 原始载荷安全 Service；明文只在受控加解密调用的局部内存中存在。 */
interface StationRawPayloadService {
    /** 在事务外序列化并加密明文载荷，返回只含密文的写模型。 */
    fun prepare(payload: Any, now: LocalDateTime = LocalDateTime.now()): PreparedStationRawPayload

    /** 在调用方短事务内仅保存预加密载荷，不执行 JSON 或密码学运算。 */
    fun storePrepared(sourceId: Long, payload: PreparedStationRawPayload)

    /** 加密并保存载荷；默认时间在接口边界固定，数据库只接收密文。 */
    fun store(sourceId: Long, payload: Any, now: LocalDateTime = LocalDateTime.now())

    /** 受控读取一条载荷并解析为 JSON。 */
    fun inspect(sourceId: Long): JsonNode

    /** 有界批量读取；任一密文损坏时失败关闭。 */
    fun inspectMany(sourceIds: List<Long>): Map<Long, JsonNode>

    /** 恢复任务批量读取时隔离损坏密文，不把未知来源写回在线状态。 */
    fun inspectManySafely(sourceIds: List<Long>): Map<Long, JsonNode>
}
