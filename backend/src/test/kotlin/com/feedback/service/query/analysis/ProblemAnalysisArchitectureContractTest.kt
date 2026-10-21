package com.feedback.service.query.analysis

import com.feedback.controller.query.ProblemAnalysisController
import com.feedback.controller.query.ProblemAnalysisRequest
import com.feedback.controller.query.ClusterReadController
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsAuditPort
import com.feedback.application.port.feedback.diagnostics.FeedbackDiagnosticsReadPort
import com.feedback.application.port.problem.analysis.RelatedFeedbackReadPort
import com.feedback.application.port.problem.analysis.ProblemAnalysisReadPort
import com.feedback.application.port.problem.analysis.ClusterReadPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** 冻结 PR5 的问题分析分层：HTTP 只见用例，应用边界只传递具名不可变事实。 */
class ProblemAnalysisArchitectureContractTest {
    @Test
    fun `controller maps independent pages into one semantic query use case`() {
        val request = ProblemAnalysisRequest().apply {
            startTime = LocalDateTime.of(2026, 8, 1, 0, 0)
            endTime = LocalDateTime.of(2026, 8, 31, 23, 59, 59)
            allPage = 2
            allSize = 10
            crossChannelPage = 3
            crossChannelSize = 5
        }

        assertEquals(
            ProblemAnalysisQuery(
                period = ProblemAnalysisPeriod(request.startTime, request.endTime),
                allClusters = ProblemAnalysisPage(2, 10),
                crossChannelClusters = ProblemAnalysisPage(3, 5)
            ),
            request.toQuery()
        )
        assertTrue(
            ProblemAnalysisController::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asIterable() }
                .contains(ProblemAnalysisQueryUseCase::class.java)
        )
        assertTrue(
            ClusterReadController::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asIterable() }
                .contains(ClusterReadUseCase::class.java)
        )
    }

    @Test
    fun `application and port boundaries expose no persistence contracts`() {
        (
            ProblemAnalysisQueryUseCase::class.java.methods + ProblemAnalysisReadPort::class.java.methods +
                ClusterReadUseCase::class.java.methods + ClusterReadPort::class.java.methods +
                RelatedFeedbackReadPort::class.java.methods + FeedbackDiagnosticsReadPort::class.java.methods +
                FeedbackDiagnosticsAuditPort::class.java.methods
        ).forEach { method ->
            val signature = method.toGenericString()
            assertFalse(signature.contains("com.feedback.model.po"), signature)
            assertFalse(signature.contains("com.feedback.mapper"), signature)
            assertFalse(signature.contains("AnalysisCriteria"), signature)
            assertFalse(signature.contains("JsonNode"), signature)
        }
        val constructorSize = ProblemAnalysisApplicationService::class.java.declaredConstructors
            .filterNot { it.isSynthetic }.maxOf { it.parameterCount }
        assertTrue(constructorSize <= 5, "analysis service has $constructorSize dependencies")
    }

    @Test
    fun `composite analysis read has an explicit repeatable read transaction`() {
        val transaction = ProblemAnalysisApplicationService::class.java
            .getDeclaredMethod("analyze", ProblemAnalysisQuery::class.java)
            .getAnnotation(Transactional::class.java)

        assertTrue(transaction.readOnly)
        assertEquals(Isolation.REPEATABLE_READ, transaction.isolation)
    }
}
