package com.feedback.service.cluster

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ClusterReclassificationContractTest {
    @Test
    fun `V220 defines durable run snapshot and link provenance facts`() {
        val migration = Files.readString(Path.of("src/main/resources/db/migration/V220__govern_full_cluster_reclassification.sql"))

        assertTrue(migration.contains("CREATE TABLE t_cluster_reclassification_run"))
        assertTrue(migration.contains("CREATE TABLE t_cluster_reclassification_cluster_snapshot"))
        assertTrue(migration.contains("UNIQUE KEY uk_cluster_reclassification_request"))
        assertTrue(migration.contains("run_status IN ('REQUESTED','RUNNING','FINALIZING','COMPLETED','FAILED','ROLLING_BACK','ROLLED_BACK')"))
        assertTrue(migration.contains("reclassification_run_id BIGINT"))
        assertTrue(migration.contains("superseded_by_reclassification_run_id BIGINT"))
        assertTrue(migration.contains("original_status_reason"))
        assertFalse(migration.contains("INSERT INTO t_cluster_link"))
        assertFalse(migration.contains("UPDATE t_cluster_link"))
    }

    @Test
    fun `reclassification service keeps AI prohibition and safe coverage checks`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/feedback/service/cluster/ClusterReclassificationService.kt"))
        val repository = Files.readString(Path.of("src/main/kotlin/com/feedback/repository/cluster/ClusterReclassificationRepository.kt"))
        val contentMapper = Files.readString(Path.of("src/main/resources/mybatis/mapper/cluster/ClusterReclassificationContentMapper.xml"))

        assertTrue(source.contains("CLUSTER_RECLASSIFICATION_AI_ASSISTED_ENABLED"))
        assertTrue(contentMapper.contains("masking_status='PASS' AND is_deleted=0"))
        assertTrue(source.contains("mains == runMains && unownedSafeMains == 0L"))
        assertTrue(repository.contains("CLUSTER_RECLASSIFICATION_ROLLBACK_MAIN_COUNT_MISMATCH"))
        assertTrue(source.contains("TransactionTemplate"))
        assertFalse(Regex("JdbcTemplate|NamedParameterJdbcTemplate|EntityManager").containsMatchIn(source))
    }
}
