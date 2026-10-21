package com.feedback.model.po.feedback.detail

import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

data class RedblackDetailPo(
    var originalIdRaw: String? = null, var headline: String? = null, var author: String? = null,
    var sourcePlatform: String? = null, var pubTypeName: String? = null, var listType: String? = null,
    var sectionRaw: String? = null, var folderName: String? = null, var keyword: String? = null,
    var emotionTypeRaw: String? = null, var evaluationObject: String? = null, var evaluationDimension: String? = null,
    var impactLevelRaw: String? = null, var dataPeriod: String? = null, var sourceDepartmentMasked: String? = null,
    var sourceRoleMasked: String? = null, var pushTime: LocalDateTime? = null, var pubTime: LocalDateTime? = null,
    var createTime: LocalDateTime? = null, var updateTime: LocalDateTime? = null, var sourceDeleted: Boolean? = null,
    var url: String? = null
)

