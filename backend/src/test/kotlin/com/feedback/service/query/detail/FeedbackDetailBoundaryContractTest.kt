package com.feedback.service.query.detail

import com.feedback.model.auth.*
import com.feedback.repository.feedback.detail.*
import com.feedback.application.port.feedback.detail.*
import com.feedback.controller.query.FeedbackDetailController
import com.feedback.model.po.feedback.detail.FeedbackDetailContentPo
import com.feedback.model.po.feedback.detail.FeedbackDetailSourcePo
import com.feedback.repository.feedback.detail.FeedbackDetailAuditRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.annotation.Isolation
import org.springframework.aop.framework.ProxyFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager

/** 冻结 PR3 的依赖方向、构造边界和分 Bean 事务设计。 */
class FeedbackDetailBoundaryContractTest {
    @Test
    fun `controller depends on four explicit use cases`() {
        val parameters = FeedbackDetailController::class.java.declaredConstructors
            .single { !it.isSynthetic }.parameterTypes.toList()

        assertEquals(
            listOf(
                FeedbackDetailQueryUseCase::class.java,
                FeedbackModificationQueryUseCase::class.java,
                FeedbackTimelineQueryUseCase::class.java,
                FeedbackExternalLinkQueryUseCase::class.java
            ),
            parameters
        )
    }

    @Test
    fun `application ports expose no persistence or mybatis types`() {
        val ports = listOf(
            FeedbackDetailReadPort::class.java,
            FeedbackModificationReadPort::class.java,
            FeedbackTimelineReadPort::class.java,
            FeedbackExternalLinkReadPort::class.java,
            FeedbackNavigationReadPort::class.java,
            FeedbackDetailAuditPort::class.java,
            FeedbackExternalLinkConfigurationPort::class.java
        )
        ports.flatMap { it.methods.toList() }.forEach { method ->
            val signature = method.toGenericString()
            assertFalse(signature.contains(".model.po."), signature)
            assertFalse(signature.contains("mybatis", ignoreCase = true), signature)
            assertFalse(signature.contains("Criteria"), signature)
            assertFalse(signature.contains("Mapper"), signature)
        }
    }

    @Test
    fun `use cases remain focused and authorized readers own consistent snapshot transactions`() {
        listOf(
            FeedbackDetailQueryService::class.java,
            FeedbackModificationQueryService::class.java,
            FeedbackTimelineQueryService::class.java,
            FeedbackExternalLinkQueryService::class.java
        ).forEach { type ->
            val count = type.declaredConstructors.filterNot { it.isSynthetic }.maxOf { it.parameterCount }
            assertTrue(count <= 5, "${type.simpleName} has $count dependencies")
            assertFalse(type.isAnnotationPresent(Transactional::class.java), type.simpleName)
        }
        val readers = listOf(
            AuthorizedFeedbackDetailSnapshotReader::class.java,
            AuthorizedFeedbackModificationSnapshotReader::class.java,
            AuthorizedFeedbackTimelineSnapshotReader::class.java,
            AuthorizedFeedbackExternalLinkSnapshotReader::class.java
        )
        val transactions = readers.map { type ->
            val count = type.declaredConstructors.filterNot { it.isSynthetic }.maxOf { it.parameterCount }
            assertTrue(count <= 5, "${type.simpleName} has $count dependencies")
            type.getMethod("read", Long::class.javaPrimitiveType).getAnnotation(Transactional::class.java)
        }
        val audit = FeedbackDetailAuditRepository::class.java.getMethod(
            "append", FeedbackDetailAuditEvent::class.java
        ).getAnnotation(Transactional::class.java)

        assertTrue(transactions.all(Transactional::readOnly))
        assertTrue(transactions.all { it.isolation == Isolation.REPEATABLE_READ })
        assertFalse(audit.readOnly)
        assertTrue(readers.none { it == FeedbackDetailAuditRepository::class.java })
    }

    @Test
    fun `application snapshot types are immutable Kotlin values`() {
        listOf(
            FeedbackSourceSnapshot::class.java,
            FeedbackContentSnapshot::class.java,
            FeedbackAiSnapshot::class.java,
            FeedbackFieldModificationFact::class.java,
            FeedbackTimelineSnapshot::class.java
        ).flatMap { it.declaredFields.toList() }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field -> assertTrue(java.lang.reflect.Modifier.isFinal(field.modifiers), field.toString()) }

        assertTrue(FeedbackDetailSourcePo::class.java.declaredFields.any { !java.lang.reflect.Modifier.isFinal(it.modifiers) })
        assertTrue(FeedbackDetailContentPo::class.java.declaredFields.any { !java.lang.reflect.Modifier.isFinal(it.modifiers) })
    }

    @Test
    fun `read snapshots contain no public response DTO`() {
        val snapshotTypes = listOf(
            FeedbackDetailSnapshot::class.java,
            FeedbackAttachmentSummaryFact::class.java,
            FeedbackNavigationSnapshot::class.java,
            SdkFeedbackChannelFacts::class.java,
            JiraFeedbackChannelFacts::class.java,
            QualityFeedbackChannelFacts::class.java,
            RedblackFeedbackChannelFacts::class.java,
            StationFeedbackChannelFacts::class.java
        )
        snapshotTypes.flatMap { it.declaredFields.toList() }
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .forEach { field -> assertFalse(field.genericType.typeName.contains("Response"), field.toString()) }
    }

    @Test
    fun `spring transaction proxy encloses guard policy and detail facts in one read snapshot`() {
        val access = mockk<FeedbackDetailAccessContextProvider>()
        val reads = mockk<FeedbackDetailReadPort>()
        val links = mockk<FeedbackExternalLinkResolver>()
        val authorization = mockk<AuthorizedFeedbackRead>()
        val scope = mockk<com.feedback.model.auth.DataAccessScope>()
        val principal = mockk<com.feedback.security.WebPrincipal>()
        val detail = mockk<FeedbackDetailSnapshot>()
        val externalFacts = mockk<FeedbackExternalLinkFacts>()
        every { authorization.scope } returns scope
        every { authorization.principal } returns principal
        every { detail.externalLinkFacts } returns externalFacts
        every { access.authorizeWithFieldPolicy(7) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            authorization
        }
        every { reads.read(7, scope) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            detail
        }
        every { links.available(externalFacts, principal) } answers {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive())
            false
        }
        val transactionManager = RecordingTransactionManager()
        val target = AuthorizedFeedbackDetailSnapshotReader(access, reads, links)
        val interceptor = TransactionInterceptor().apply {
            setTransactionManager(transactionManager)
            transactionAttributeSource = AnnotationTransactionAttributeSource()
        }
        val proxy = ProxyFactory(target).apply {
            isProxyTargetClass = true
            addAdvice(interceptor)
        }.proxy as AuthorizedFeedbackDetailSnapshotReader

        proxy.read(7)

        assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
        assertEquals(1, transactionManager.definitions.size)
        assertTrue(transactionManager.definitions.single().isReadOnly)
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, transactionManager.definitions.single().isolationLevel)
        assertEquals(listOf("begin", "commit"), transactionManager.events)
        verifyOrder {
            access.authorizeWithFieldPolicy(7)
            reads.read(7, scope)
            links.available(externalFacts, principal)
        }
    }
}

private class RecordingTransactionManager : AbstractPlatformTransactionManager() {
    val definitions = mutableListOf<TransactionDefinition>()
    val events = mutableListOf<String>()

    override fun doGetTransaction(): Any = Any()
    override fun doBegin(transaction: Any, definition: TransactionDefinition) {
        definitions += definition
        events += "begin"
    }
    override fun doCommit(status: DefaultTransactionStatus) { events += "commit" }
    override fun doRollback(status: DefaultTransactionStatus) { events += "rollback" }
}
