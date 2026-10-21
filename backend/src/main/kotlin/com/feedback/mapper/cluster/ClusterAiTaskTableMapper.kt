package com.feedback.mapper.cluster

import com.feedback.model.po.cluster.*
import com.feedback.model.cluster.*
import org.apache.ibatis.annotations.Mapper
import org.apache.ibatis.annotations.Param
import java.math.BigDecimal
import java.time.LocalDateTime

@Mapper interface ClusterAiTaskTableMapper {
    fun findPendingSourceIds(@Param("sourceIds") sourceIds: List<Long>): List<Long>
    fun findDecisionByTaskKey(@Param("taskKey") taskKey: String): ClusterAiDecisionTaskPo?
    fun insertDecision(@Param("row") row: ClusterAiDecisionTaskInsertPo): Int
    fun recoverExpiredDecisions(): Int
    fun findDueDecisionIds(@Param("limit") limit: Int): List<Long>
    fun claimDecision(@Param("id") id: Long, @Param("holder") holder: String): Int
    fun findDecisionById(@Param("id") id: Long): ClusterAiDecisionTaskPo?
    fun completeDecision(@Param("row") row: ClusterAiDecisionCompletionPo): Int
    fun failDecision(@Param("id") id: Long, @Param("holder") holder: String, @Param("errorDetail") errorDetail: String): Int
    fun insertEvaluation(@Param("row") row: ClusterEvaluationInsertPo): Int
    fun findLatestEvaluation(): ClusterEvaluationPo?
}

