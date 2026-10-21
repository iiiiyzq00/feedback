package com.feedback.mapper.config

import com.feedback.model.po.config.DynamicConfigCatalogPo
import com.feedback.model.po.config.MaskingEvidencePo
import com.feedback.model.po.config.SystemConfigInsertPo
import com.feedback.model.po.config.SystemConfigPo
import com.feedback.model.po.config.SystemConfigVersionCountPo
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param

@Mapper
interface SystemConfigMapper {
    fun findActiveCatalogs(@Param("limit") limit: Int): List<DynamicConfigCatalogPo>
    fun findCatalog(@Param("catalogKey") catalogKey: String, @Param("limit") limit: Int): List<DynamicConfigCatalogPo>
    fun lockCatalogs(@Param("catalogKeys") catalogKeys: List<String>): List<DynamicConfigCatalogPo>
    fun findActiveConfigurations(@Param("keys") keys: List<String>): List<SystemConfigPo>
    fun findHistory(
        @Param("key") key: String,
        @Param("limit") limit: Int,
        @Param("offset") offset: Long
    ): List<SystemConfigPo>
    fun countHistory(@Param("key") key: String): Long
    fun countHistoryByKeys(@Param("keys") keys: List<String>): List<SystemConfigVersionCountPo>
    fun deactivate(@Param("keys") keys: List<String>): Int
    fun insertAll(@Param("records") records: List<SystemConfigInsertPo>): Int
    fun findByKeyAndVersion(
        @Param("key") key: String,
        @Param("version") version: String,
        @Param("limit") limit: Int
    ): List<SystemConfigPo>
    fun findActiveRuntime(@Param("key") key: String, @Param("limit") limit: Int): List<SystemConfigPo>
    fun findActiveMaskingRuleVersion(@Param("limit") limit: Int): List<MaskingEvidencePo>
    fun findMaskingRelease(@Param("version") version: String, @Param("limit") limit: Int): List<MaskingEvidencePo>
    fun findMaskingEvidence(
        @Param("key") key: String,
        @Param("type") type: String,
        @Param("limit") limit: Int
    ): List<MaskingEvidencePo>
}
