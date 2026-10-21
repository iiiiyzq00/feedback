package com.feedback.service.ai

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AiTaskQueueSafetyContractTest {
    @Test
    fun `orphan and non pass invocation tasks are terminal instead of retried`() {
        val service=Files.readString(Path.of("src/main/kotlin/com/feedback/service/ai/AiTaskPipeline.kt"))
        val repository=Files.readString(Path.of("src/main/kotlin/com/feedback/repository/ai/AiPipelineRepository.kt"))
        val mapper=Files.readString(Path.of("src/main/resources/mybatis/mapper/ai/AiTaskMapper.xml"))
        assertTrue(mapper.contains("AI_SOURCE_UNAVAILABLE"))
        assertTrue(mapper.contains("task_status='CANCELLED'"))
        assertTrue(service.contains("errorCode == com.feedback.common.ErrorCode.NOT_FOUND"))
        assertTrue(repository.contains("content.maskingStatus != \"PASS\""))
    }

    @Test
    fun `lease write back is fenced by owner plus generation instead of owner only`() {
        val mapper=Files.readString(Path.of("src/main/resources/mybatis/mapper/ai/AiTaskMapper.xml"))
        // claim 必须发放新的 fencing token，否则同一个 holder 的两次租约无法区分（ABA）。
        assertTrue(mapper.contains("lease_generation=lease_generation+1"))
        assertTrue(mapper.contains("lease_expires_at,lease_generation,next_retry_at"))
        // 三条终态写回都必须同时匹配 holder 与 generation。
        assertTrue(Regex("AND task_status='PROCESSING' AND lease_holder=#\\{holder} AND lease_generation=#\\{generation}")
            .findAll(mapper).count() == 3, "terminalFailure/retryFailure/completeLease must all carry the generation guard")
    }
}
