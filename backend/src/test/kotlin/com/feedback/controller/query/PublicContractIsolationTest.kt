package com.feedback.controller.query

import com.feedback.config.GlobalExceptionHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.feedback.controller.report.ReportController
import com.feedback.service.query.ApplicationFeedbackFilters
import com.feedback.service.query.CommonFeedbackFilters
import com.feedback.service.query.FeedbackListQuery
import com.feedback.service.query.FeedbackPageRequest
import com.feedback.service.query.FeedbackSortSpec
import com.feedback.service.query.JiraFeedbackFilters
import com.feedback.service.query.QualityFeedbackFilters
import com.feedback.service.query.RedblackFeedbackFilters
import com.feedback.service.query.StationFeedbackFilters
import com.feedback.service.query.FeedbackDiagnosticsQueryUseCase
import com.feedback.service.query.RelatedFeedbackQueryUseCase
import com.feedback.service.query.SearchSuggestionQueryUseCase
import com.feedback.service.query.SimilarFeedbackService
import com.feedback.service.query.analysis.ClusterReadUseCase
import com.feedback.service.query.analysis.ProblemAnalysisQueryUseCase
import com.feedback.service.query.detail.FeedbackDetailQueryService
import com.feedback.service.query.listprojection.FeedbackExportUseCase
import com.feedback.service.query.listprojection.FeedbackListQueryUseCase
import com.feedback.service.query.listprojection.FeedbackListResponse
import com.feedback.service.query.listprojection.FeedbackListRowResponse
import com.feedback.service.query.listprojection.FeedbackListStatsResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.swagger.v3.oas.annotations.media.Schema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.web.bind.annotation.RequestMethod
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.time.LocalDateTime

/** 冻结批次 F 的公开合同隔离、具名边界和失败关闭，不覆盖应用服务的数据口径测试。 */
class PublicContractIsolationTest {
    private val listReads = mockk<FeedbackListQueryUseCase>()
    private val exports = mockk<FeedbackExportUseCase>()
    private val detailReads = mockk<FeedbackDetailTestDouble>()
    private val relatedReads = mockk<RelatedFeedbackQueryUseCase>()
    private val attachmentReads = mockk<FeedbackAttachmentTestDouble>()
    private val attachmentStreams = mockk<com.feedback.service.attachment.AttachmentSecureStreamService>()
    private val diagnosticsReads = mockk<FeedbackDiagnosticsQueryUseCase>()
    private val suggestions = mockk<SearchSuggestionQueryUseCase>()
    private val analysis = mockk<ProblemAnalysisQueryUseCase>()
    private val clusterReads = mockk<ClusterReadUseCase>()
    private val mvc = MockMvcBuilders.standaloneSetup(
        FeedbackListController(listReads),
        FeedbackDetailController(detailReads, detailReads, detailReads, detailReads),
        FeedbackController(relatedReads, diagnosticsReads),
        FeedbackAttachmentController(attachmentReads, attachmentStreams),
        FeedbackExportController(exports),
        ProblemAnalysisController(analysis),
        ClusterReadController(clusterReads),
        SearchSuggestionController(suggestions)
    ).setControllerAdvice(GlobalExceptionHandler(SimpleMeterRegistry())).build()

    @Test
    fun `phase one routes are owned only by isolated controllers`() {
        assertEquals(setOf("list"), httpMethods(FeedbackListController::class.java))
        assertEquals(
            setOf("detail", "modifications", "timeline", "externalLink"),
            httpMethods(FeedbackDetailController::class.java)
        )
        assertEquals(setOf("related", "viewDiagnostics", "authorizeDiagnostics"), httpMethods(FeedbackController::class.java))
        assertEquals(
            setOf(
                "attachments", "authorizeAttachment", "authorizeAttachmentPreview", "authorizeAttachmentPreviews", "authorizeOriginalLogDownload",
                "streamAttachment", "streamAttachmentPreview", "downloadOriginalLog"
            ),
            httpMethods(FeedbackAttachmentController::class.java)
        )
        assertEquals(setOf("export", "exportSelection"), httpMethods(FeedbackExportController::class.java))
        assertTrue(httpMethods(ProblemAnalysisController::class.java).contains("problemAnalysis"))
        assertTrue(httpMethods(ClusterReadController::class.java).containsAll(setOf("cluster", "feedback")))
        assertTrue(httpMethods(SearchSuggestionController::class.java).contains("suggest"))

        assertFalse(httpMethods(ReportController::class.java).contains("cluster"))
        assertFalse(httpMethods(ReportController::class.java).contains("feedback"))
        assertFalse(httpMethods(SearchController::class.java).contains("suggest"))

        // 已完成 MyBatis 迁移的非反馈报告与相似搜索入口继续保持隔离。
        assertTrue(httpMethods(ReportController::class.java).containsAll(setOf("list", "generate", "clusterRca")))
        assertTrue(httpMethods(SearchController::class.java).contains("similar"))
    }

    @Test
    fun `phase one feedback controllers keep focused constructor boundaries`() {
        val controllers = listOf(
            FeedbackListController::class.java,
            FeedbackDetailController::class.java,
            FeedbackController::class.java,
            FeedbackAttachmentController::class.java,
            FeedbackExportController::class.java,
            ProblemAnalysisController::class.java,
            ClusterReadController::class.java,
            SearchSuggestionController::class.java
        )
        controllers.forEach { controller ->
            val dependencyCount = controller.declaredConstructors.filterNot { it.isSynthetic }
                .maxOf { it.parameterCount }
            assertTrue(dependencyCount <= 5, "${controller.simpleName} has $dependencyCount dependencies")
            controller.declaredConstructors.filterNot { it.isSynthetic }.flatMap { it.parameterTypes.asIterable() }
                .forEach { dependency ->
                    assertTrue(dependency.isInterface, "${controller.simpleName} depends on concrete ${dependency.name}")
                }
        }
    }

    @Test
    fun `compiled phase one route manifest contains exactly the nineteen governed endpoints`() {
        val actual = listOf(
            FeedbackListController::class.java,
            FeedbackDetailController::class.java,
            FeedbackController::class.java,
            FeedbackAttachmentController::class.java,
            FeedbackExportController::class.java,
            ProblemAnalysisController::class.java,
            ClusterReadController::class.java,
            SearchSuggestionController::class.java
        ).flatMap(::compiledRoutes).toSet()
        val expected = setOf(
            Route("GET", "/api/v1/feedback"),
            Route("GET", "/api/v1/feedback/export"),
            Route("POST", "/api/v1/feedback/export"),
            Route("GET", "/api/v1/feedback/{id}"),
            Route("GET", "/api/v1/feedback/{id}/modifications"),
            Route("GET", "/api/v1/feedback/{id}/timeline"),
            Route("GET", "/api/v1/feedback/{id}/related"),
            Route("GET", "/api/v1/feedback/{id}/attachments"),
            Route("GET", "/api/v1/feedback/{id}/diagnostics"),
            Route("POST", "/api/v1/feedback/{id}/diagnostics/authorize"),
            Route("POST", "/api/v1/feedback/{id}/attachments/{attachmentId}/authorize"),
            Route("POST", "/api/v1/feedback/{id}/attachments/{attachmentId}/authorize-preview"),
            Route("POST", "/api/v1/feedback/{id}/attachments/authorize-previews"),
            Route("POST", "/api/v1/feedback/{id}/attachments/{attachmentId}/authorize-original-download"),
            Route("GET", "/api/v1/feedback/{id}/attachments/{attachmentId}/stream"),
            Route("GET", "/api/v1/feedback/{id}/attachments/{attachmentId}/preview"),
            Route("GET", "/api/v1/feedback/{id}/attachments/{attachmentId}/original"),
            Route("GET", "/api/v1/feedback/{id}/external-link"),
            Route("GET", "/api/v1/search/suggest"),
            Route("GET", "/api/v1/dashboard/problem-analysis"),
            Route("GET", "/api/v1/reports/cluster/{id}"),
            Route("GET", "/api/v1/reports/cluster/{id}/feedback")
        )

        assertEquals(expected, actual)
    }

    @Test
    fun `isolated controller methods and request fields do not expose generic maps`() {
        val controllers = listOf(
            FeedbackListController::class.java,
            FeedbackDetailController::class.java,
            FeedbackController::class.java,
            FeedbackAttachmentController::class.java,
            FeedbackExportController::class.java,
            ProblemAnalysisController::class.java,
            ClusterReadController::class.java,
            SearchSuggestionController::class.java
        )
        controllers.flatMap { type -> type.declaredMethods.filter { it.name in httpMethods(type) } }.forEach { method ->
            assertFalse(method.genericReturnType.typeName.contains("java.util.Map"), method.toGenericString())
            assertFalse(method.genericParameterTypes.any { it.typeName.contains("java.util.Map") }, method.toGenericString())
            assertFalse(method.returnType == Any::class.java, method.toGenericString())
        }

        val requests = listOf(
            FeedbackFilterRequest::class.java,
            FeedbackListRequest::class.java,
            FeedbackExportQueryRequest::class.java,
            FeedbackSelectionRequest::class.java,
            BatchAttachmentPreviewAuthorizationRequest::class.java,
            SearchSuggestionRequest::class.java,
            ProblemAnalysisRequest::class.java,
            ClusterTimeRangeRequest::class.java,
            ClusterFeedbackRequest::class.java
        )
        requests.flatMap(::allFields).filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
            assertFalse(Map::class.java.isAssignableFrom(field.type), "${field.declaringClass.simpleName}.${field.name}")
        }
    }

    @Test
    fun `detail service does not restore retired row compatibility reads`() {
        val retiredMethods = setOf(
            "channelMetaForVisibleRow",
            "diagnosticAvailabilityForVisibleRow"
        )

        assertFalse(FeedbackDetailQueryService::class.java.methods.any { it.name in retiredMethods })
    }

    @Test
    fun `phase one source channel fields publish the same closed five-channel contract`() {
        val uppercase = setOf("SDK", "STATION_FEEDBACK", "JIRA", "QUALITY", "REDBLACK")
        val lowercase = uppercase.map(String::lowercase).toSet()
        val requestFields = listOf("channel", "channels").map {
            FeedbackFilterRequest::class.java.getDeclaredField(it).getAnnotation(Schema::class.java)
        }
        assertTrue(requestFields.all { it != null && it.allowableValues.toSet() == uppercase })

        val responseFields = listOf(
            FeedbackListRowResponse::class.java.getDeclaredField("sourceChannel"),
            com.feedback.service.query.detail.FeedbackDetailResponse::class.java.getDeclaredField("sourceChannel")
        )
        assertTrue(responseFields.all {
            it.getAnnotation(Schema::class.java)?.allowableValues?.toSet() == lowercase
        })
    }

    @Test
    fun `public list filters map into named internal groups without losing fields`() {
        val publicFields = allFields(FeedbackListRequest::class.java)
            .filterNot { Modifier.isStatic(it.modifiers) }.mapTo(mutableSetOf()) { it.name }
        val groupedFields = listOf(
            CommonFeedbackFilters::class.java,
            ApplicationFeedbackFilters::class.java,
            JiraFeedbackFilters::class.java,
            QualityFeedbackFilters::class.java,
            RedblackFeedbackFilters::class.java,
            StationFeedbackFilters::class.java
        ).flatMap { it.declaredFields.filterNot { field -> Modifier.isStatic(field.modifiers) } }
            .mapTo(mutableSetOf()) { if (it.name == "resolutionState") "sourceResolutionState" else it.name }
            .plus(setOf("page", "size", "sortBy", "sortDirection"))

        assertEquals(publicFields, groupedFields)

        val request = FeedbackListRequest()
        val instant = LocalDateTime.of(2026, 8, 30, 12, 34, 56)
        val requestFields = allFields(request.javaClass).filterNot { Modifier.isStatic(it.modifiers) }
        requestFields.forEach { field ->
            field.isAccessible = true
            field.set(request, sample(field, instant))
        }
        val query = request.toQuery()
        val actual = linkedMapOf<String, Any?>()
        listOf(query.common, query.application, query.jira, query.quality, query.redblack, query.station).forEach { group ->
            group.javaClass.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.forEach { field ->
                field.isAccessible = true
                actual[if (field.name == "resolutionState") "sourceResolutionState" else field.name] = field.get(group)
            }
        }
        actual["page"] = query.page.page
        actual["size"] = query.page.size
        actual["sortBy"] = query.sort.sortBy
        actual["sortDirection"] = query.sort.direction
        requestFields.forEach { field -> assertEquals(field.get(request), actual[field.name], field.name) }
    }

    @Test
    fun `comma separated channels preserve the existing wire contract`() {
        val captured = slot<FeedbackListQuery>()
        every { listReads.list(capture(captured)) } returns emptyListResponse()

        mvc.get("/api/v1/feedback") { param("channels", "SDK,JIRA") }.andExpect { status { isOk() } }

        assertEquals(listOf("SDK", "JIRA"), captured.captured.common.channels)
        assertEquals(1, captured.captured.page.page)
        assertEquals(20, captured.captured.page.size)
    }

    @Test
    fun `invalid phase one query values fail closed with 400`() {
        listOf(
            "/api/v1/feedback?keyword=${"x".repeat(201)}",
            "/api/v1/feedback?size=0",
            "/api/v1/search/suggest?q=x",
            "/api/v1/search/suggest?q=valid&limit=21",
            "/api/v1/dashboard/problem-analysis?allSize=0",
            "/api/v1/dashboard/problem-analysis?startTime=not-a-time&endTime=2026-08-30T12:00:00",
            "/api/v1/reports/cluster/1/feedback?page=0",
            "/api/v1/reports/cluster/1/feedback?size=101"
        ).forEach { path ->
            mvc.get(path).andExpect { status { isBadRequest() } }
        }
    }

    private fun httpMethods(type: Class<*>): Set<String> = type.declaredMethods.filter { method ->
        method.isAnnotationPresent(GetMapping::class.java) || method.isAnnotationPresent(PostMapping::class.java) ||
            method.isAnnotationPresent(PutMapping::class.java) || method.isAnnotationPresent(PatchMapping::class.java) ||
            method.isAnnotationPresent(DeleteMapping::class.java)
    }.mapTo(linkedSetOf()) { it.name }

    private data class Route(val method: String, val path: String)

    private fun compiledRoutes(type: Class<*>): List<Route> {
        val classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping::class.java)
        val basePaths = classMapping?.path?.ifEmpty { classMapping.value }?.toList().orEmpty().ifEmpty { listOf("") }
        return type.declaredMethods.flatMap { method ->
            val mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping::class.java)
                ?: return@flatMap emptyList()
            val childPaths = mapping.path.ifEmpty { mapping.value }.toList().ifEmpty { listOf("") }
            val methods = mapping.method.toList().ifEmpty { listOf(RequestMethod.GET) }
            basePaths.flatMap { base ->
                childPaths.flatMap { child ->
                    methods.map { httpMethod -> Route(httpMethod.name, normalizePath(base, child)) }
                }
            }
        }
    }

    private fun normalizePath(base: String, child: String): String =
        "/" + listOf(base, child).flatMap { it.split('/') }.filter { it.isNotBlank() }.joinToString("/")

    private fun allFields(type: Class<*>): List<Field> = generateSequence(type) { it.superclass }
        .takeWhile { it != Any::class.java }.flatMap { it.declaredFields.asSequence() }.toList()

    private fun findField(type: Class<*>, name: String): Field? = allFields(type).firstOrNull { it.name == name }

    private fun sample(field: Field, instant: LocalDateTime): Any = when {
        field.type == String::class.java -> field.name
        field.type == LocalDateTime::class.java -> instant
        field.type == java.lang.Integer.TYPE -> if (field.name == "page") 7 else 13
        field.type == java.lang.Long::class.java -> 37L
        field.type == java.lang.Boolean.TYPE -> true
        List::class.java.isAssignableFrom(field.type) -> listOf(field.name)
        else -> error("No sample for ${field.name}: ${field.type.name}")
    }

    private fun emptyListResponse() = FeedbackListResponse(
        emptyList(), 0, 1, 20,
        FeedbackListStatsResponse(0, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0)
    )
}
