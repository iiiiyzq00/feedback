package com.feedback.model.po.imports

import java.math.BigDecimal

data class UnifiedQualityContentPo(val sourceId:Long,val description:String?,val mappingError:Boolean,val functionModule:String?,val maskingStatus:String,val dataQualityScore:BigDecimal?)
