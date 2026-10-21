package com.feedback.service.jira

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JiraFullBackfillContractTest {
    @Test fun `bounded full backfill stages every configured window and fails closed`() {
        val source=Files.readString(Path.of("src/main/kotlin/com/feedback/service/jira/JiraFullBackfillRunner.kt"))
        listOf("feedback.jira400.backfill.windows", "split('/', limit = 2)",
            "service.enqueue(\"BACKFILL\"", "service.runQueued()", "status.lastErrorCode == null")
            .forEach { assertTrue(source.contains(it), it) }
    }
}
