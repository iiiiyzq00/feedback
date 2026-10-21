package com.feedback.service.report

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReportNumberValidatorTest {
    private val mapper=jacksonObjectMapper();private val validator=ReportNumberValidator()
    @Test fun `sentence containing invented number is removed from final narration`() {val facts=mapper.readTree("""{"count":1234,"rate":0.12}""");val result=validator.validate("入库1,234条，通过率12%。预测增长99%。",facts);assertEquals("PASS",result.status);assertTrue(result.text!!.contains("1,234"));assertTrue(result.text.contains("12%"));assertFalse(result.text.contains("99%"));assertFalse(result.text.contains("数据校验"));assertTrue(result.errors.isEmpty())}
    @Test fun `same verified value on multiple metric paths remains valid`() {val facts=mapper.readTree("""{"sdk":{"count":20},"jira":{"count":20}}""");val result=validator.validate("两个渠道均有20个问题",facts);assertEquals("PASS",result.status);assertEquals("两个渠道均有20个问题",result.text);assertTrue(result.errors.isEmpty())}
    @Test fun `percentage point facts validate percent narration`() {val facts=mapper.readTree("""{"qualityRate":98.09,"changeRate":-4.0,"completeRate":100.0}""");val result=validator.validate("质量率98.09%，环比下降4%，完整率100%",facts);assertEquals("PASS",result.status);assertTrue(result.errors.isEmpty())}
    @Test fun `text without narration is a valid SQL fallback`() {val result=validator.validate(null,mapper.createObjectNode());assertEquals("PASS",result.status);assertTrue(result.errors.isEmpty())}
    @Test fun `dates and semantic versions are not treated as business metrics`() {val result=validator.validate("版本 v1.2.3 于2026-07-30发布",mapper.createObjectNode());assertEquals("PASS",result.status);assertEquals("版本 v1.2.3 于2026-07-30发布",result.text)}
}
