package com.feedback.job

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class XxlJobArchitectureTest {
    @Test
    fun `every manifest task has one thin handler in the job layer`() {
        val jobSources = Files.walk(Path.of("src/main/kotlin/com/feedback/job")).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith("JobHandlers.kt") }
                .map(Files::readString)
                .toList()
        }
        val annotations = jobSources.flatMap { source ->
            Regex("@JobHandler\\(\"([^\"]+)\"\\)").findAll(source).map { it.groupValues[1] }.toList()
        }
        val manifestHandlers = Files.readAllLines(Path.of("../config/xxl-job-tasks.conf"))
            .asSequence()
            .filterNot { it.isBlank() || it.startsWith("#") }
            .map { it.substringBefore('|') }
            .toList()

        // 43 → 45 → 48：RCA 接入/执行（`feedbackRcaIntakeHandler` /
        // `feedbackRcaWorkerHandler`），随后是 MQ 三条轨的中继
        // （`feedbackRcaEventRelayHandler` / `feedbackNotificationRelayHandler` /
        // `feedbackDerivedIndexRelayHandler`）。这个数字是**断言异常掉线**用的固定值，
        // 不是设计基线：它存在的意义是让「删了一个 handler」也红，
        // 而下一行的集合相等只看得出「多了一个」。
        // 新增 handler 时必须同时改这里与 `config/xxl-job-tasks.conf`，两处都改才是有意的。
        assertEquals(48, annotations.size)
        assertEquals(annotations.size, annotations.distinct().size)
        assertEquals(manifestHandlers.toSet(), annotations.toSet())
        jobSources.forEach { source ->
            assertTrue(source.contains("@EnabledXxlJob"))
            assertFalse(source.contains("@Scheduled"))
        }
    }

    @Test
    fun `xxl job is the only scheduling entry point`() {
        val sourceRoot = Path.of("src/main/kotlin")
        val forbiddenSchedulingSources = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".kt") }
                .filter {
                    val source = Files.readString(it)
                    listOf("@Scheduled", "@EnableScheduling", "scheduling.annotation.Scheduled")
                        .any(source::contains)
                }
                .map { sourceRoot.relativize(it).toString() }
                .toList()
        }

        assertTrue(forbiddenSchedulingSources.isEmpty(), forbiddenSchedulingSources.joinToString())
        assertFalse(Files.exists(Path.of("src/main/kotlin/com/feedback/config/SchedulingConfig.kt")))
    }
}
