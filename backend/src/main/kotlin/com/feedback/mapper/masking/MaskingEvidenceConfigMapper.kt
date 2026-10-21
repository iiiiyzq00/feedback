package com.feedback.mapper.masking

import com.feedback.model.po.masking.*
import com.feedback.model.masking.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface MaskingEvidenceConfigMapper {
    fun insert(@Param("row") row: MaskingEvidenceInsertPo): Int
    fun finalizeKey(@Param("id") id: Long, @Param("prefix") prefix: String): Int
    fun reviewDataset(@Param("version") version: String, @Param("actor") actor: Long): Int
    fun approveDataset(@Param("version") version: String, @Param("actor") actor: Long): Int
    fun findDatasets(): List<MaskingEvidenceConfigPo>
    fun lockRelease(@Param("version") version: String): MaskingEvidenceConfigPo?
    fun findLiveDataset(@Param("version") version: String): MaskingEvidenceConfigPo?
    fun countRun(@Param("releaseId") releaseId: Long, @Param("datasetId") datasetId: Long,
                 @Param("engineVersion") engineVersion: String): Int
    fun lockRun(@Param("runId") runId: Long): MaskingEvidenceConfigPo?
    fun findDatasetsByBusinessIds(@Param("ids") ids: List<Long>): List<MaskingEvidenceConfigPo>
    fun findByBusinessId(@Param("configType") configType: String, @Param("id") id: Long): MaskingEvidenceConfigPo?
    fun approveRun(@Param("id") id: Long, @Param("actor") actor: Long, @Param("reason") reason: String): Int
    fun evaluateRelease(@Param("id") id: Long, @Param("runId") runId: Long, @Param("auditId") auditId: Long,
                        @Param("actor") actor: Long): Int
    fun findRuns(@Param("version") version: String): List<MaskingEvidenceConfigPo>
    fun expireDatasets(): Int
}

