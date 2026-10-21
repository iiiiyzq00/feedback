package com.feedback.model.po.imports

import java.math.BigDecimal
import java.time.LocalDateTime

data class UnifiedQualitySnapshotPo(var id:Long=0,val dimensionKey:String,val sampleCount:Long,val completeness:BigDecimal?,val dataContractVersion:String,val evaluationSnapshot:String,val windowStart:LocalDateTime?,val windowEnd:LocalDateTime?)
