package com.feedback.mapper.station.legacy

import com.feedback.model.po.station.legacy.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

/** Station Legacy 附件证据 Mapper；只返回有附件证据的来源 ID，不读取文件内容。 */
@Mapper
interface StationLegacyAttachmentMapper {
    /** 按受控条件查询附件记录；SQL 必须参数化且保持单表职责，本方法不承载业务判断。 */
    fun findSourceIdsWithEvidence(@Param("sourceIds") sourceIds: List<Long>): List<Long>
}
