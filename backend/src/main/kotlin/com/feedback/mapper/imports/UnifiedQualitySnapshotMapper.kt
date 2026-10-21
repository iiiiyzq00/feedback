package com.feedback.mapper.imports

import com.feedback.model.po.imports.UnifiedQualitySnapshotPo
import com.feedback.model.po.imports.UnifiedQualityThresholdOccurrencePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.time.LocalDateTime

@Mapper interface UnifiedQualitySnapshotMapper {
    fun insert(row:UnifiedQualitySnapshotPo):Int
    fun findLatest():List<UnifiedQualitySnapshotPo>
    fun countRecentThreshold(@Param("dimension") dimension:String,@Param("since") since:LocalDateTime):Int
    fun insertThreshold(row:UnifiedQualityThresholdOccurrencePo):Int
}
