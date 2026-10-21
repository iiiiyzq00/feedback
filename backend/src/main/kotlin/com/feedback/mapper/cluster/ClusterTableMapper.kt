package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper
interface ClusterTableMapper {
    fun findByIds(@Param("ids") ids: List<Long>): List<ClusterPo>
    fun lockByIds(@Param("ids") ids: List<Long>): List<ClusterPo>
    fun findByNameKeys(@Param("nameKeys") nameKeys: List<String>, @Param("statuses") statuses: List<String>): List<ClusterPo>
    fun lockByNameKeys(@Param("nameKeys") nameKeys: List<String>, @Param("statuses") statuses: List<String>): List<ClusterPo>
    fun findByProductScopeAndStatuses(@Param("productScope") productScope: String, @Param("statuses") statuses: List<String>, @Param("limit") limit: Int): List<ClusterPo>
    fun findByProductScopeSignatureAndStatuses(@Param("productScope") productScope: String, @Param("signature") signature: String, @Param("statuses") statuses: List<String>, @Param("limit") limit: Int): List<ClusterPo>
    fun findByExactProfile(@Param("productScope") productScope: String, @Param("functionModule") functionModule: String?, @Param("issueCategory") issueCategory: String?, @Param("symptom") symptom: String, @Param("statuses") statuses: List<String>, @Param("limit") limit: Int): List<ClusterPo>
    fun findIdsAfter(@Param("afterId") afterId: Long, @Param("limit") limit: Int): List<Long>
    fun insertOrReuse(@Param("row") row: ClusterInsertPo): Int
    fun insertLegacy(@Param("row") row: ClusterLegacyInsertPo): Int
    fun insertAll(@Param("rows") rows: List<ClusterInsertPo>): Int
    fun updateStatusCas(@Param("row") row: ClusterStatusCasPo): Int
    fun updateStatusesCas(@Param("rows") rows: List<ClusterStatusCasPo>): Int
    fun bumpLifecycleCas(@Param("row") row: ClusterStatusCasPo): Int
    fun reopenResolved(@Param("id") id: Long, @Param("expectedVersion") expectedVersion: Long, @Param("reason") reason: String): Int
    fun updateStatistics(@Param("rows") rows: List<ClusterStatisticsUpdatePo>): Int
    fun evolveName(@Param("id") id: Long, @Param("expectedVersion") expectedVersion: Long, @Param("oldName") oldName: String, @Param("newName") newName: String, @Param("nameKey") nameKey: String): Int
}

