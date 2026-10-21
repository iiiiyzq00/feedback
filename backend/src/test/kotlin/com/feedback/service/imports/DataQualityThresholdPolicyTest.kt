package com.feedback.service.imports

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import com.feedback.repository.masking.MaskingConfigRepository
import com.feedback.model.po.masking.MaskingConfigPo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DataQualityThresholdPolicyTest {
    @Test
    fun `loads published channel and batch thresholds`() {
        val configs=mockk<MaskingConfigRepository>()
        every { configs.active("data-quality.thresholds") } returns MaskingConfigPo(configValue =
            """{"requiredCompletenessMin":0.97,"primaryKeyDuplicateMax":0.02,"timeValidMin":0.99,"enumValidMin":0.96,"mappingMin":0.93,"maskingPassMin":1.0,"importSuccessMin":0.92,"crossChannelDuplicateMax":0.25,"quarantineMax":0}""")

        val thresholds=DataQualityThresholdPolicy(configs,jacksonObjectMapper()).current()

        assertEquals(.97,thresholds.requiredCompletenessMin)
        assertEquals(.02,thresholds.primaryKeyDuplicateMax)
        assertEquals(0,thresholds.quarantineMax)
    }

    @Test
    fun `fails closed to governed defaults when config cannot be read`() {
        val configs=mockk<MaskingConfigRepository>()
        every { configs.active(any()) } throws IllegalStateException("db unavailable")

        assertEquals(.99,DataQualityThresholdPolicy(configs,jacksonObjectMapper()).current().maskingPassMin)
    }
}
