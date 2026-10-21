package com.feedback.model.po.masking

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

data class IngestIncidentPo(var id:Long=0,var publicId:ByteArray=ByteArray(0),var title:String="",var severity:String="P2")

