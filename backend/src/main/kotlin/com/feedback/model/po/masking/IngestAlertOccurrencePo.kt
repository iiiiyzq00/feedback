package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class IngestAlertOccurrencePo(var id:Long=0,var instanceId:Long=0,var snapshotJson:String="{}")

