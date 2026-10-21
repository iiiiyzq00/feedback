package com.feedback.controller.query

import com.feedback.service.attachment.FeedbackAttachmentApplicationService
import com.feedback.service.impl.attachment.FeedbackAttachmentApplicationServiceImpl
import com.feedback.service.attachment.AttachmentSecureStreamService
import com.feedback.manager.attachment.AttachmentGrantConsumer
import com.feedback.model.attachment.SecureAttachmentContent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.Transactional
import java.lang.reflect.Modifier

/** 冻结 PR4 的附件分层边界；HTTP、持久化和对象存储实现不得重新渗入应用契约。 */
class AttachmentArchitectureContractTest {
    @Test
    fun `attachment controller depends only on explicit use cases`() {
        val dependencies = FeedbackAttachmentController::class.java.declaredConstructors
            .filterNot { it.isSynthetic }
            .single()
            .parameterTypes
            .toSet()

        assertTrue(dependencies == setOf(
            FeedbackAttachmentApplicationService::class.java,
            AttachmentSecureStreamService::class.java
        ), dependencies.joinToString { it.name })
    }

    @Test
    fun `attachment application service exposes no persistence or transport dependency`() {
        val forbiddenFragments = listOf(
            ".mapper.", ".model.po.", "jakarta.servlet", "org.springframework.http", "minio"
        )
        FeedbackAttachmentApplicationServiceImpl::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                assertFalse(
                    forbiddenFragments.any { field.type.name.contains(it, ignoreCase = true) },
                    "${field.name}: ${field.type.name}"
                )
                assertTrue(field.type.isInterface, "${field.name} must be an application port: ${field.type.name}")
            }
    }

    @Test
    fun `grant authorization owns the atomic grant and audit transaction`() {
        val method = FeedbackAttachmentApplicationServiceImpl::class.java.getMethod(
            "authorize",
            com.feedback.model.attachment.AuthorizeFeedbackAttachmentCommand::class.java
        )
        assertTrue(method.isAnnotationPresent(Transactional::class.java))
    }

    @Test
    fun `grant consumer collaborators are ports and stream snapshot is transport neutral`() {
        val dependencies = AttachmentGrantConsumer::class.java.declaredConstructors
            .filterNot { it.isSynthetic }.single().parameterTypes
        assertTrue(dependencies.all { it.isInterface }, dependencies.joinToString { it.name })

        SecureAttachmentContent::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                assertFalse(field.type.name.startsWith("org.springframework"), "${field.name}: ${field.type.name}")
                assertFalse(field.type.name.startsWith("jakarta.servlet"), "${field.name}: ${field.type.name}")
            }
    }
}
