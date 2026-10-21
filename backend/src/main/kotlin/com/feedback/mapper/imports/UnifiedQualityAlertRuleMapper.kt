package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualityAlertRulePo
import org.apache.ibatis.annotations.Mapper

@Mapper interface UnifiedQualityAlertRuleMapper { fun findActive():UnifiedQualityAlertRulePo? }
