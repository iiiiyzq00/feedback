package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper
interface MaskingConfigMapper {
    fun findActiveByKey(@Param("configKey") configKey:String):MaskingConfigPo?
    fun findDraftRelease(@Param("version") version:String):MaskingConfigPo?
    fun findByType(@Param("configType") configType:String):List<MaskingConfigPo>
    fun findReleaseForUpdate(@Param("version") version:String):MaskingConfigPo?
    fun findByTypeAndKey(@Param("configType") configType:String,@Param("configKey") configKey:String):MaskingConfigPo?
    fun countByType(@Param("configType") configType:String):Int
    fun insertRelease(@Param("version") version:String,@Param("rulesJson") rulesJson:String,@Param("allowlist") allowlist:String,@Param("reason") reason:String,@Param("actor") actor:Long):Int
    fun publishRelease(@Param("id") id:Long,@Param("reason") reason:String,@Param("actor") actor:Long):Int
    fun deactivateRuntimeKeys(@Param("keys") keys:List<String>):Int
    fun insertRuntimeConfigs(@Param("rows") rows:List<MaskingRuntimeConfigWritePo>):Int
}

