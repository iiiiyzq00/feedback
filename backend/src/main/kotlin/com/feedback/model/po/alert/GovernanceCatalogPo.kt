package com.feedback.model.po.alert

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

data class GovernanceCatalogPo(var governanceStatus:String?=null,var implementationStatus:String?=null,var implementationEvidence:String?=null)

