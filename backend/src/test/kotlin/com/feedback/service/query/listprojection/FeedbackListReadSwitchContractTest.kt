package com.feedback.service.query.listprojection

import com.feedback.application.port.feedback.list.FeedbackListReadPort
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.nio.file.Files
import java.nio.file.Path

class FeedbackListReadSwitchContractTest {
    @Test
    fun `read switch defaults disabled and old list export SQL cannot be reached`() {
        val application = Files.readString(Path.of("src/main/resources/application.yml"))

        assertTrue(application.contains("read-enabled: \${PHASE_ONE_LIST_PROJECTION_READ_ENABLED:false}"))
        assertTrue(constructorTypes(com.feedback.controller.query.FeedbackListController::class.java).contains(FeedbackListQueryUseCase::class.java))
        assertTrue(constructorTypes(com.feedback.controller.query.FeedbackExportController::class.java).contains(FeedbackExportUseCase::class.java))
        assertTrue(constructorTypes(FeedbackListSnapshotReader::class.java).contains(FeedbackListReadPort::class.java))
        val orchestratorTransaction = FeedbackListApplicationService::class.java
            .getDeclaredMethod("list", com.feedback.service.query.FeedbackListQuery::class.java)
            .getAnnotation(Transactional::class.java)
        assertTrue(orchestratorTransaction == null)
        val readTransaction = FeedbackListSnapshotReader::class.java
            .getDeclaredMethod(
                "read",
                com.feedback.service.query.FeedbackListQuery::class.java,
                com.feedback.security.WebPrincipal::class.java
            ).getAnnotation(Transactional::class.java)
        assertTrue(readTransaction.readOnly)
        assertTrue(readTransaction.isolation == Isolation.REPEATABLE_READ)
        val auditTransaction = ListDiagnosticAuditWriter::class.java
            .getDeclaredMethod("append", List::class.java)
            .getAnnotation(Transactional::class.java)
        assertTrue(auditTransaction.propagation == org.springframework.transaction.annotation.Propagation.REQUIRED)
        FeedbackListReadPort::class.java.methods.forEach { method ->
            assertFalse(method.toGenericString().contains("com.feedback.model.po"), method.toGenericString())
            assertFalse(method.toGenericString().contains("com.feedback.model.query"), method.toGenericString())
        }
    }

    @Test
    fun `problem analysis switch defaults disabled and approved controllers have no legacy fallback`() {
        val application = Files.readString(Path.of("src/main/resources/application.yml"))

        assertTrue(application.contains("read-enabled: \${PHASE_ONE_PROBLEM_ANALYSIS_PROJECTION_READ_ENABLED:false}"))
        assertTrue(constructorTypes(com.feedback.controller.query.ProblemAnalysisController::class.java)
            .contains(com.feedback.service.query.analysis.ProblemAnalysisQueryUseCase::class.java))
        assertTrue(constructorTypes(com.feedback.controller.query.ClusterReadController::class.java)
            .contains(com.feedback.service.query.analysis.ClusterReadUseCase::class.java))
        assertEquals(setOf("problemAnalysis"), httpMethods(
            com.feedback.controller.query.ProblemAnalysisController::class.java
        ))
        assertEquals(setOf("cluster", "feedback"), httpMethods(
            com.feedback.controller.query.ClusterReadController::class.java
        ))
        assertTrue(Files.notExists(Path.of("src/main/kotlin/com/feedback/service/query/DashboardQueryService.kt")))
        assertFalse(com.feedback.service.report.ClusterReportService::class.java.methods.any { it.name == "feedback" })
    }

    private fun constructorTypes(type: Class<*>): Set<Class<*>> = type.declaredConstructors
        .flatMap { it.parameterTypes.asIterable() }.toSet()

    private fun httpMethods(type: Class<*>): Set<String> = type.declaredMethods.filter {
        it.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping::class.java)
    }.mapTo(linkedSetOf()) { it.name }
}
