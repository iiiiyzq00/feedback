package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualitySourcePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualitySourceMapper {
    fun findByChannel(channel:String):List<UnifiedQualitySourcePo>
    fun findByIds(@Param("ids") ids:List<Long>):List<UnifiedQualitySourcePo>
}
