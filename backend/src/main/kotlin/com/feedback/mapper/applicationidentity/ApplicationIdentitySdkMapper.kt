package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_channel_sdk 单表来源事实 Mapper。 */
@Mapper
interface ApplicationIdentitySdkMapper {
    /** 按有界 source ID 集合批量读取 appId 与 packageName。 */
    fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ApplicationIdentitySdkPo>
}
