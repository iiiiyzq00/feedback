package com.feedback.mapper.imports

import com.feedback.model.po.imports.ImportQualityContentPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper interface ImportQualityContentMapper { fun findBySourceIds(@Param("sourceIds") sourceIds: List<Long>): List<ImportQualityContentPo> }
