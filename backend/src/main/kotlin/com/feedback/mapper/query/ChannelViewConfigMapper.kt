package com.feedback.mapper.query

import com.feedback.model.po.query.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface ChannelViewConfigMapper {
    fun findPublished(@Param("channel") channel: String): ChannelViewConfigPo?
    fun nextVersion(@Param("channel") channel: String): Long
    fun insertDraft(@Param("channel") channel: String, @Param("version") version: Long,
                    @Param("fieldsJson") fieldsJson: String, @Param("reason") reason: String,
                    @Param("actor") actor: Long): Int
    fun countDraft(@Param("channel") channel: String, @Param("version") version: Long): Int
    fun supersedePublished(@Param("channel") channel: String): Int
    fun publish(@Param("channel") channel: String, @Param("version") version: Long, @Param("actor") actor: Long): Int
    fun findVersion(@Param("channel") channel: String, @Param("version") version: Long): ChannelViewConfigPo?
    fun insertRollback(@Param("channel") channel: String, @Param("version") version: Long,
                       @Param("fieldsJson") fieldsJson: String, @Param("reason") reason: String,
                       @Param("actor") actor: Long): Int
    fun history(@Param("channel") channel: String): List<ChannelViewConfigPo>
}

