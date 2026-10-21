package com.feedback.integration

import com.feedback.model.jira.ChannelJira
import com.feedback.model.feedback.FeedbackSource
import com.feedback.repository.ChannelJiraRepository
import com.feedback.repository.FeedbackSourceRepository
import com.feedback.repository.jiraowner.JiraOwnerInitializationRepository
import com.feedback.service.jira.JiraAssigneeOwnerInitializer
import com.feedback.service.query.listprojection.FeedbackListProjectionDirtyMarker
import com.ninjasquad.springmockk.SpykBean
import io.mockk.every
import io.restassured.RestAssured
import io.restassured.RestAssured.given
import io.restassured.specification.RequestSpecification
import io.restassured.http.ContentType
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.util.AopTestUtils
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.util.UUID

/**
 * 真实 MySQL 8.4 合同：输入名称和 t_auth_user 行必须由同一 0900_ai_ci 权重键归组。
 * 测试数据通过正式 API/JPA 写入，测试代码不直接执行 SQL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "JIRA_OWNER_MYSQL_ENABLED", matches = "true")
class JiraOwnerInitializationMysqlIntegrationTest {
    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var sources: FeedbackSourceRepository

    @Autowired
    private lateinit var jira: ChannelJiraRepository

    @Autowired
    private lateinit var repository: JiraOwnerInitializationRepository

    @Autowired
    private lateinit var initializer: JiraAssigneeOwnerInitializer

    @SpykBean
    private lateinit var dirtyMarker: FeedbackListProjectionDirtyMarker

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    private lateinit var namespace: String

    @BeforeEach
    fun configureApi() {
        namespace = UUID.randomUUID().toString().take(12)
        RestAssured.baseURI = "http://127.0.0.1"
        RestAssured.port = port
        RestAssured.basePath = "/api/v1"
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails()
    }

    @Test
    fun `as cs user table still returns both case variants as one ambiguous owner key`() {
        val displayName = "Wang-$namespace"
        createUser(displayName)
        createUser(displayName.replaceFirst("Wang", "wang"))

        val candidates = inTransaction { repository.findActiveUserCandidates(listOf(displayName)) }

        assertFalse(candidates.overflowed)
        assertEquals(2, candidates.groups.single().activeUsers.size)
        val sourceId = createJiraSource(displayName)
        assertFalse(initializer.initialize(sourceId))
        inTransaction {
            assertTrue(repository.lockAssignments(listOf(sourceId)).isEmpty())
            assertEquals("UNASSIGNED", repository.lockWorkflows(listOf(sourceId)).single().distributionStatus)
        }
    }

    @Test
    fun `unique active database match initializes assignment and workflow`() {
        val displayName = "Unique-$namespace"
        val ownerId = createUser(displayName)
        val sourceId = createJiraSource(displayName)

        assertTrue(initializer.initialize(sourceId))

        inTransaction {
            assertEquals(ownerId, repository.lockAssignments(listOf(sourceId)).single().ownerUserId)
            assertEquals("ACCEPTED", repository.lockWorkflows(listOf(sourceId)).single().distributionStatus)
        }
    }

    @Test
    fun `inactive case equivalent user does not make the active owner ambiguous`() {
        val displayName = "Active-$namespace"
        val ownerId = createUser(displayName)
        val inactiveId = createUser(displayName.replaceFirst("Active", "active"))
        updateUser(inactiveId, displayName.replaceFirst("Active", "active"), active = false)
        val sourceId = createJiraSource(displayName)

        assertTrue(initializer.initialize(sourceId))

        inTransaction {
            assertEquals(ownerId, repository.lockAssignments(listOf(sourceId)).single().ownerUserId)
        }
    }

    @Test
    fun `composed and decomposed accent variants remain ambiguous`() {
        val composed = "Jos\u00e9-$namespace"
        val decomposed = "Jose\u0301-$namespace"
        createUser(composed)
        createUser(decomposed)

        val candidates = inTransaction { repository.findActiveUserCandidates(listOf(composed)) }

        assertEquals(2, candidates.groups.single().activeUsers.size)
    }

    @Test
    fun `existing platform assignment is never replaced by a later Jira assignee`() {
        val firstName = "First-$namespace"
        val secondName = "Second-$namespace"
        val firstOwnerId = createUser(firstName)
        createUser(secondName)
        val sourceId = createJiraSource(firstName)
        assertTrue(initializer.initialize(sourceId))
        jira.saveAndFlush(requireNotNull(jira.findById(sourceId).orElse(null)).copy(assignee = secondName))

        assertFalse(initializer.initialize(sourceId))

        inTransaction {
            assertEquals(firstOwnerId, repository.lockAssignments(listOf(sourceId)).single().ownerUserId)
            assertEquals("ACCEPTED", repository.lockWorkflows(listOf(sourceId)).single().distributionStatus)
        }
    }

    @Test
    fun `marker failure rolls back assignment workflow and disposition transaction`() {
        val displayName = "Rollback-$namespace"
        createUser(displayName)
        val sourceId = createJiraSource(displayName)
        val markerTarget = AopTestUtils.getUltimateTargetObject<FeedbackListProjectionDirtyMarker>(dirtyMarker)
        every { markerTarget.mark(any()) } throws IllegalStateException("MYSQL_MARKER_FAILURE")

        val error = assertThrows(IllegalStateException::class.java) { initializer.initialize(sourceId) }

        assertEquals("MYSQL_MARKER_FAILURE", error.message)
        inTransaction {
            assertTrue(repository.lockAssignments(listOf(sourceId)).isEmpty())
            assertEquals("UNASSIGNED", repository.lockWorkflows(listOf(sourceId)).single().distributionStatus)
        }
    }

    private fun createUser(displayName: String): Long {
        val response = authenticatedRequest()
            .contentType(ContentType.JSON)
            .body(
                mapOf(
                    "username" to "jira-owner-${UUID.randomUUID()}",
                    "displayName" to displayName,
                    "password" to "Jira-Owner-Integration-Password!",
                    "roles" to setOf("USER"),
                    "scopes" to emptyList<Any>()
                )
            )
            .post("/admin/users")
            .then()
            .statusCode(200)
            .body("code", equalTo(200))
        return response.extract().path<Number>("data.id").toLong()
    }

    private fun updateUser(id: Long, displayName: String, active: Boolean) {
        val response = authenticatedRequest()
            .contentType(ContentType.JSON)
            .body(mapOf("displayName" to displayName, "active" to active))
            .put("/admin/users/$id")
            .then()
            .statusCode(200)
            .body("code", equalTo(200))
    }

    private fun authenticatedRequest(): RequestSpecification {
        val login = given()
            .contentType(ContentType.JSON)
            .body(mapOf("username" to BOOTSTRAP_USERNAME, "password" to BOOTSTRAP_PASSWORD))
            .post("/auth/login")
            .then()
            .statusCode(200)
            .body("code", equalTo(200))
            .extract()
            .response()
        return given()
            .cookie("feedback_session", login.cookie("feedback_session"))
            .cookie("XSRF-TOKEN", login.cookie("XSRF-TOKEN"))
            .header("X-XSRF-TOKEN", login.cookie("XSRF-TOKEN"))
    }

    private fun createJiraSource(assignee: String): Long {
        val source = sources.saveAndFlush(
            FeedbackSource(
                publicId = uuidBytes(UUID.randomUUID()),
                sourceChannel = "JIRA",
                sourceRecordId = "JIRA-OWNER-${UUID.randomUUID()}",
                sourceEventTime = LocalDateTime.now()
            )
        )
        jira.saveAndFlush(
            ChannelJira(
                sourceId = source.id,
                jiraBaseUrlHash = ByteArray(32) { 7 },
                jiraKey = "OWNER-${UUID.randomUUID()}",
                assignee = assignee
            )
        )
        return source.id
    }

    private fun uuidBytes(value: UUID): ByteArray = ByteBuffer.allocate(16)
        .putLong(value.mostSignificantBits)
        .putLong(value.leastSignificantBits)
        .array()

    private fun <T> inTransaction(block: () -> T): T = requireNotNull(
        TransactionTemplate(transactionManager).execute { block() }
    )

    companion object {
        private const val BOOTSTRAP_USERNAME = "jira-owner-it-admin"
        private const val BOOTSTRAP_PASSWORD = "Jira-Owner-Integration-Admin!"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                System.getenv("JIRA_OWNER_MYSQL_URL") ?: run {
                    "jdbc:mysql://127.0.0.1:3306/feedback_platform_jira_owner_it" +
                        "?createDatabaseIfNotExist=true&useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai"
                }
            }
            registry.add("spring.datasource.username") { System.getenv("JIRA_OWNER_MYSQL_USERNAME") ?: "root" }
            registry.add("spring.datasource.password") { System.getenv("JIRA_OWNER_MYSQL_PASSWORD") ?: "" }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("app.auth.bootstrap.username") { BOOTSTRAP_USERNAME }
            registry.add("app.auth.bootstrap.password") { BOOTSTRAP_PASSWORD }
            registry.add("app.jira.assignee-owner-backfill-enabled") { "false" }
            registry.add("app.milvus.enabled") { "false" }
            registry.add("app.ai.embedding.provider") { "hashing" }
            registry.add("app.ai.enqueue-delay-ms") { "3600000" }
            registry.add("app.ai.worker-delay-ms") { "3600000" }
            registry.add("app.cluster.worker-delay-ms") { "3600000" }
            registry.add("app.sync.worker-delay-ms") { "3600000" }
            registry.add("app.masking.fingerprint-secret") { "jira-owner-fingerprint-secret-at-least-32-characters" }
            registry.add("app.masking.contact-storage-secret") { "jira-owner-contact-secret-at-least-32-characters" }
            registry.add("app.imports.raw-storage-secret") { "jira-owner-import-secret-at-least-32-characters" }
            registry.add("app.station-feedback.raw-storage-secret") { "jira-owner-station-secret-at-least-32-characters" }
            registry.add("app.auth.session-cookie.secure") { "false" }
            registry.add("dubbo.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun resetApi() = RestAssured.reset()
    }
}
