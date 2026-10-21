package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityAlertInstancePo
import org.apache.ibatis.annotations.Mapper

@Mapper interface UnifiedQualityAlertInstanceMapper { fun insert(row:UnifiedQualityAlertInstancePo):Int }
