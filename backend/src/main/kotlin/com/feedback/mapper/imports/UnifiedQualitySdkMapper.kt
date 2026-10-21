package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualitySdkPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface UnifiedQualitySdkMapper { fun findBySourceIds(@Param("sourceIds") sourceIds:List<Long>):List<UnifiedQualitySdkPo> }
