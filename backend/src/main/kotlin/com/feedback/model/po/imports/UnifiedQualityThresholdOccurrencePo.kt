package com.feedback.model.po.imports

data class UnifiedQualityThresholdOccurrencePo(var id:Long=0,val instanceId:Long,val dimensionKey:String,val sourceTable:String,val snapshotId:Long,val snapshotJson:String)
