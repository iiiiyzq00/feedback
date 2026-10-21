package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.ClusterShadowPreviewEvidencePo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

/** 一次读取指定 shadow generation 的全部 actionable 安全展示证据。 */
@Mapper
interface ClusterShadowPreviewEvidenceMapper {
    fun findByGeneration(@Param("generationId") generationId: Long): List<ClusterShadowPreviewEvidencePo>
}
