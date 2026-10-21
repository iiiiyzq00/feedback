package com.feedback.model.po.imports

import java.time.LocalDateTime

data class UnifiedQualitySourcePo(val id:Long,val sourceChannel:String,val sourceRecordId:String?,val sourceEventTime:LocalDateTime?,val packageName:String?,val appVersion:String?)
