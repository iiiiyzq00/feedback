package com.feedback.service.query.analysis

import com.feedback.repository.cluster.ClusterProfileFactRepository
import com.feedback.repository.cluster.ClusterShadowGenerationRepository
import com.feedback.repository.cluster.ClusterShadowPreviewRepository
import com.feedback.repository.impl.applicationidentity.MyBatisApplicationIdentityRepositoryImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mybatis.spring.annotation.MapperScan
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@SpringBootTest(
    classes = [ClusterShadowVerifiedGenerationRealAuditTest.ReadOnlyConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["spring.flyway.enabled=false"]
)
@EnabledIfEnvironmentVariable(named = "RUN_REAL_SHADOW_PREVIEW_AUDIT", matches = "true")
class ClusterShadowVerifiedGenerationRealAuditTest {
    @Autowired private lateinit var reads: ClusterShadowPreviewRepository
    @Autowired private lateinit var projector: ClusterShadowPreviewPhenomenonProjector

    @Test
    fun `verified v4 generation rehydrates every cluster without application fabrication`() {
        val dataset = reads.readVerified(ClusterShadowPreviewApplicationService.ALLOWED_GENERATION)
        val grouped = dataset.members.groupBy { checkNotNull(it.clusterKeyHash) }
        val readable = dataset.clusters.associateWith { cluster ->
            projector.project(
                cluster.clusterKeyHash,
                cluster.clusterNameHash,
                grouped.getValue(cluster.clusterKeyHash),
                dataset.evidenceBySourceId
            )
        }
        val confirmedCount = dataset.members.count { it.applicationHash != null }.toLong()
        val pendingCount = dataset.members.count { it.applicationHash == null }.toLong()
        val largest = dataset.clusters.maxBy { it.memberCount }
        val largestName = readable.getValue(largest)
        dataset.clusters.sortedByDescending { it.memberCount }.take(20).forEach { cluster ->
            val value = readable.getValue(cluster)
            println("REAL_SHADOW_SAFE_TOP count=${cluster.memberCount} problem=${value.application}·${value.phenomenon}")
        }

        assertEquals(1_358, readable.size)
        assertEquals(5_404L, confirmedCount)
        assertEquals(5_154L, pendingCount)
        assertEquals(2_013L, largest.memberCount)
        assertFalse(largestName.application.isBlank())
        assertFalse(largestName.phenomenon.isBlank())
        assertFalse(readable.values.any { value ->
            value.application.contains(Regex("[0-9a-f]{32,}", RegexOption.IGNORE_CASE)) ||
                value.phenomenon.contains(Regex("[0-9a-f]{16,}", RegexOption.IGNORE_CASE)) ||
                value.phenomenon.contains(Regex("[0-9]{8,}"))
        })
        assertEquals(0, grouped.values.count { members ->
            members.map { it.applicationHash }.toSet().size > 1
        })
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("com.feedback.mapper")
    @Import(
        ClusterShadowGenerationRepository::class,
        ClusterProfileFactRepository::class,
        MyBatisApplicationIdentityRepositoryImpl::class,
        ClusterShadowPreviewRepository::class,
        ClusterShadowPreviewPhenomenonProjector::class
    )
    class ReadOnlyConfiguration
}
