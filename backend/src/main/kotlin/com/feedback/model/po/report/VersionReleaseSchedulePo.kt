package com.feedback.model.po.report

import org.apache.ibatis.annotations.Mapper
import java.time.LocalDate

data class VersionReleaseSchedulePo(var configKey:String="",var packageName:String?=null,var version:String?=null,var releasedAt:LocalDate=LocalDate.MIN)

