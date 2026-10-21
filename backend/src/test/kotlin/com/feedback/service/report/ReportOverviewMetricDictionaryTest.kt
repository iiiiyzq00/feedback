package com.feedback.service.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReportOverviewMetricDictionaryTest {
 @Test fun `six report overview labels have explicit business meanings`() {
  assertEquals("有效反馈",ReportOverviewMetricDictionary.primaryLabel("DAILY_INGEST"))
  assertEquals("有效反馈",ReportOverviewMetricDictionary.primaryLabel("WEEKLY_TREND"))
  assertEquals("版本反馈",ReportOverviewMetricDictionary.primaryLabel("VERSION_RELEASE"))
  assertEquals("质量样本",ReportOverviewMetricDictionary.primaryLabel("QUALITY_LINKAGE"))
  assertEquals("红黑榜样本",ReportOverviewMetricDictionary.primaryLabel("REDBLACK_ANALYSIS"))
  assertEquals("风险分析反馈",ReportOverviewMetricDictionary.primaryLabel("RISK_BRIEF"))
  assertEquals("版本主问题簇",ReportOverviewMetricDictionary.secondaryLabel("VERSION_RELEASE"))
  assertEquals("质量主问题簇",ReportOverviewMetricDictionary.secondaryLabel("QUALITY_LINKAGE"))
 }
}
