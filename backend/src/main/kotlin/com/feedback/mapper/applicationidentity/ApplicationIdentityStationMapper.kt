package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_channel_station_feedback 单表来源与 reviewed candidate Mapper。 */
@Mapper
interface ApplicationIdentityStationMapper {
    /** 按有界 source ID 集合批量读取 Station 应用证据。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ApplicationIdentityStationPo>
    /** 按展示名有界聚合 source_app_id 候选，展示名本身不作为 alias。 */
    fun findCandidatesByDisplayName(
        @Param("displayName") displayName: String,
        @Param("limit") limit: Int
    ): List<ApplicationIdentityStationCandidatePo>
}
