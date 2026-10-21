package com.feedback.mapper.applicationidentity

import com.feedback.model.po.applicationidentity.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** t_feedback_source 单表游标 Mapper；所有查询必须稳定排序且有 limit。 */
@Mapper
interface ApplicationIdentitySourceMapper {
    /** 读取 SDK/站端身份来源批次。 */
    fun findSupportedAfter(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<ApplicationIdentitySourcePo>
    /** 读取站端与三类安全文本渠道的历史影子批次。 */
    fun findFourChannelAfter(@Param("afterSourceId") afterSourceId: Long, @Param("limit") limit: Int): List<ApplicationIdentitySourcePo>
}
