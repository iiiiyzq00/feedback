package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityBatchPo
import org.apache.ibatis.annotations.Mapper

@Mapper interface UnifiedQualityBatchMapper { fun findActiveByChannel(channel:String):List<UnifiedQualityBatchPo> }
