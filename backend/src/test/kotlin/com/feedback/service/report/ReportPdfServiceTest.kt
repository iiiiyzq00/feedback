package com.feedback.service.report

import com.feedback.common.exception.ServiceException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 字体交给**生产自己的** `resolveFont()` 决定：第二个构造参数留空 = 不指定字体，落回候选表。
 *
 * 原先这两个测试各自抄了一份候选表（`Arial Unicode.ttf` / `NotoSansCJK-Regular.ttc`），
 * 抄漏了生产候选表里的 `/usr/share/fonts/truetype/arphic/uming.ttc`。
 * 本机没有 Arial Unicode，于是只剩列表最后那个 Noto CJK TTC ——
 * 那个集合 10 款字体**全部是 CFF 轮廓、没有一个 `glyf` 表**，
 * 正是 `loadFont` 明确要排除的类型，于是稳定失败于 `REPORT_PDF_FONT_GLYPH_MISSING`。
 * 而生产在同一台机器上会选中 uming.ttc（4 款字体全部有 glyf），根本走不到那个分支。
 * 抄一份候选表的代价就是它会漂移，所以这里把那份副本删掉，而不是把它同步一次。
 */
class ReportPdfServiceTest {
 @Test fun `pdf contains governance snapshot and all business sections without drilldown payload`() {
  val mapper=jacksonObjectMapper();val reports=mockk<ReportService>();val rows=(1..18).map{mapOf("channel" to "SDK","module" to if(it==1) "CAMERA_MIC" else "学习中心","category" to if(it==1) "CONTENT_ERROR" else "BUG","count" to it*123,"warning" to (it%5==0),"factors" to mapOf("blackResonance" to 0,"negativeSurges" to 1,"activeAlertScore" to 2,"severeCrossChannel" to 0),"_drilldown" to mapOf("path" to "/channel/all","query" to mapOf("token" to "must-not-export")))}
  val content=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("sections" to linkedMapOf("ingestOverview" to rows,"dataQuality" to rows.take(4),"hotClusters" to rows.take(10),"jiraTimeliness" to rows.take(5),"riskLevel" to rows.take(1),"attentionItems" to rows.take(3))))
  val freshness=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("overallStatus" to "BREACHED","channels" to listOf(mapOf("channel" to "SDK","latestIngestTime" to "2026-07-30T08:00:00","lagSeconds" to 1800,"thresholdSeconds" to 900,"slaStatus" to "BREACHED"),mapOf("channel" to "JIRA","latestIngestTime" to "2026-07-29T10:00:00","lagSeconds" to 82800,"thresholdSeconds" to 129600,"slaStatus" to "MET"))))
  every{reports.detail(9001)} returns mapOf("id" to 9001L,"title" to "Day 8 六类报告统一验收样例","type" to "WEEKLY_TREND","status" to "PUBLISHED","periodStart" to "2026-07-20","periodEnd" to "2026-07-26","generatedAt" to "2026-07-30T09:00:00","dataLatestTime" to "2026-07-30T08:00:00","rawRecordCount" to 2214,"uniqueProblemCount" to 187,"freshnessStatus" to "BREACHED","freshness" to freshness,"validationStatus" to "PASS","generationSource" to "SCHEDULED","promptVersion" to "report-v3","skillVersion" to "six-report-v3","modelName" to "acceptance-model","degraded" to false,"generatedSummary" to "本周问题主要集中在学习中心。所有数字均来自已固化的 SQL 事实快照。","content" to content)
  val (_,bytes)=ReportPdfService(reports,"").export(9001)
  Loader.loadPDF(bytes).use{doc->assertTrue(doc.numberOfPages>=4);assertTrue(doc.documentInformation.title.contains("统一验收"));val text=PDFTextStripper().getText(doc);assertTrue(text.contains("目录"));assertTrue(text.contains("客户验收版"));assertTrue(text.contains("报告概览"));assertTrue(text.contains("分析摘要"));assertTrue(text.contains("数据质量与生成信息"));assertTrue(text.contains("提示词/技能"));assertTrue(text.contains("统计口径与阅读说明"));assertTrue(text.contains("渠道"));assertTrue(text.contains("已超时"));assertTrue(text.contains("定时生成"));assertTrue(text.contains("摄像头与麦克风"));assertTrue(text.contains("内容错误"));assertTrue(text.contains("黑榜共振：0"));assertTrue(!text.contains("must-not-export"));assertTrue(!text.contains("_drilldown"))}
  val target=Path.of("../output/pdf/day8-r24-r28-unified-acceptance.pdf").normalize();Files.createDirectories(target.parent);Files.write(target,bytes);assertTrue(bytes.size>10_000)
 }

 @Test fun `special characters and wide large tables render without missing glyph squares`() {
  val mapper=jacksonObjectMapper();val reports=mockk<ReportService>();val rows=(1..260).map{index->linkedMapOf<String,Any?>(
   "channel" to "SDK", "title" to "超长标题-$index：摄像头/麦克风 <异常> & 复核 😀", "module" to "AI_TUTOR",
   "category" to "BUG", "severity" to "S${index%5}", "count" to index, "status" to "PENDING",
   "warning" to "制表符\t换行\n与不换行空格\u00A0及长破折号—均需安全处理", "packageName" to "com.example.$index",
   "version" to "1.0.$index", "comparability" to "样本仅用于极端表格回归")}
  val content=mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(mapOf("sections" to linkedMapOf("hotClusters" to rows)))
  every{reports.detail(9002)} returns mapOf("id" to 9002L,"title" to "特殊字符 😀 与极端大报告","type" to "RISK_BRIEF","status" to "PUBLISHED","periodStart" to "2026-08-01","periodEnd" to "2026-08-04","generatedAt" to "2026-08-04T12:00:00","rawRecordCount" to 260,"uniqueProblemCount" to 260,"validationStatus" to "PASS","generationSource" to "MANUAL","degraded" to true,"content" to content)
  val (_,bytes)=ReportPdfService(reports,"").export(9002)
  Loader.loadPDF(bytes).use{doc->val text=PDFTextStripper().getText(doc);assertTrue(doc.numberOfPages>=10);assertTrue(text.contains("字段组 1/2"));assertTrue(text.contains("字段组 2/2"));assertTrue(text.contains("超长标题-260"));assertTrue(text.contains("[符号]"));assertFalse(text.contains("□"))}
  val target=Path.of("../output/pdf/topic11-special-large-acceptance.pdf").normalize();Files.createDirectories(target.parent);Files.write(target,bytes)
 }

 @Test fun `production requires an explicitly configured font`() {
  val reports=mockk<ReportService>();every{reports.detail(9003)} returns mapOf("content" to jacksonObjectMapper().createObjectNode())
  val error=assertThrows(ServiceException::class.java){ReportPdfService(reports,"","prod").export(9003)}
  assertTrue(error.reasonCode=="REPORT_PDF_FONT_EXPLICIT_CONFIG_REQUIRED")
 }
}
