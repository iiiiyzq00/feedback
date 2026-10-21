package com.feedback.dto.sdk

import jakarta.validation.Validation
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SdkFeedbackRequestValidationTest{
    @Test fun `client feedback id is rejected before database truncation`(){
        val request=SdkFeedbackRequest("x".repeat(65),"com.test","1",null,null,"2026-07-30T00:00:00Z",null,null,null,
            EncryptedPayloadDto("key","AES-256-GCM+RSA-OAEP-256","a","b","c","d"),null,emptyList())
        val violations=Validation.buildDefaultValidatorFactory().validator.validate(request)
        assertTrue(violations.any{it.propertyPath.toString()=="clientFeedbackId"})
    }
}
