package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityStationPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualityStationMapper { fun findBySourceIds(@Param("sourceIds") sourceIds:List<Long>):List<UnifiedQualityStationPo> }
