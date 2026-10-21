package com.feedback.service.masking

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MaskingEvaluationEvidenceMigrationTest{
    @Test fun `V61 creates reproducible masking evidence chain`(){
        val sql=Files.readString(Path.of("src/main/resources/db/migration/V61__masking_dataset_evaluation_evidence.sql")).lowercase()
        listOf("t_masking_evaluation_dataset","dataset_sha256","approved_historical","pii_approval_reference","annotated_by","reviewed_by","approved_by","expires_at","t_masking_evaluation_run","ner_tp","execution_log_sha256","failure_summary_sha256","evaluation_run_id").forEach{assertTrue(sql.contains(it),it)}
    }
}
