package com.feedback.service.query

import com.feedback.service.cluster.ClusteringEngine
import com.feedback.controller.admin.MaskingSecurityController
import com.feedback.repository.masking.AutomaticMaskingRecoveryRepository
import com.feedback.service.masking.AutomaticMaskingRecoveryService
import com.feedback.service.station.StationOnlineMaskingRecoveryService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import org.springframework.web.bind.annotation.PostMapping

class CurrentPrivacyQueueContractTest {
    @Test
    fun `phase one replaces human privacy review with automatic masking recovery`() {
        val recoveryDependencies = AutomaticMaskingRecoveryService::class.java.declaredConstructors
            .single { !it.isSynthetic }.parameterTypes.toSet()
        assertTrue(recoveryDependencies.contains(AutomaticMaskingRecoveryRepository::class.java))
        assertTrue(recoveryDependencies.contains(StationOnlineMaskingRecoveryService::class.java))

        val controllerMethods = MaskingSecurityController::class.java.declaredMethods
        assertFalse(controllerMethods.any { it.name == "reviewRaw" })
        assertFalse(controllerMethods.flatMap { method ->
            method.getAnnotation(PostMapping::class.java)?.value?.toList() ?: emptyList()
        }.any { it == "/raw-review" })

        val router = Files.readString(Path.of("../frontend/src/router/index.ts"))
        assertFalse(router.contains("monitor/operations/privacy"))
        assertFalse(router.contains("PrivacyOperations"))

        val persistenceTypes = ClusteringEngine::class.java.declaredFields.map { it.type.name } +
            ClusteringEngine::class.java.declaredConstructors.flatMap { constructor ->
                constructor.parameterTypes.map { it.name }
            }
        assertFalse(persistenceTypes.any { it.startsWith("org.springframework.jdbc") })
    }
}
