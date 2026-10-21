package com.feedback.service.cluster

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.audit.BusinessAuditService
import com.feedback.model.cluster.Cluster
import com.feedback.repository.cluster.ClusterMutationRepository
import com.feedback.repository.cluster.ClusterProfileFactRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class ClusterStatisticsTransactionTest {
    private val repository=mockk<ClusterMutationRepository>()
    private val transactions=CountingTransactions()
    private val service=ClusterStatisticsService(
        repository,mockk<ClusterProfileFactRepository>(),jacksonObjectMapper(),SimpleMeterRegistry(),
        mockk<BusinessAuditService>(relaxed=true),transactions,
    )

    @Test fun `full recompute commits each keyset page in its own short transaction`() {
        val first=(1L..200L).toList();val second=(201L..400L).toList()
        every{repository.clusterIdsAfter(0,200)} returns first
        every{repository.clusterIdsAfter(200,200)} returns second
        every{repository.clusterIdsAfter(400,200)} returns emptyList()
        every{repository.lockClusters(any())} answers { firstArg<List<Long>>().map{Cluster(id=it)} }
        every{repository.visitActiveMainPages(any(),any())} just runs
        every{repository.updateStatistics(any())} just runs

        assertEquals(400,service.recomputeAll())
        assertEquals(2,transactions.count)
    }

    @Test fun `batch coordinator is not one outer transaction`() {
        val method=ClusterStatisticsService::class.java.getDeclaredMethod("recomputeAll")
        assertFalse(method.isAnnotationPresent(Transactional::class.java))
    }

    private class CountingTransactions:TransactionOperations{
        var count=0
        override fun <T:Any?> execute(action:TransactionCallback<T>):T?{
            count++
            return action.doInTransaction(mockk<TransactionStatus>())
        }
    }
}
