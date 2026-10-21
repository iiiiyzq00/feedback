package com.feedback.service.report

import com.fasterxml.jackson.databind.JsonNode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.fontbox.ttf.TrueTypeCollection
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.Color
import jakarta.annotation.PostConstruct

@Service
class ReportPdfService(
    private val reports: ReportService,
    @Value("\${report.pdf.font-path:}") private val configuredFont: String,
    @Value("\${spring.profiles.active:local}") private val activeProfiles: String = "local"
) {
    @PostConstruct
    fun validateFontConfiguration() { resolveFont() }

    fun export(id: Long): Pair<String, ByteArray> {
        val report = reports.detail(id)
        val out = ByteArrayOutputStream()
        PDDocument().use { doc ->
            val fontFile = resolveFont()
            val collection = fontFile.takeIf { it.extension.equals("ttc", true) || it.extension.equals("otc", true) }
                ?.let(::TrueTypeCollection)
            try {
                val font = loadFont(doc, fontFile, collection)
                val writer = PdfWriter(doc, font)
                val content = report["content"] as JsonNode
            val sectionNames=content.path("sections").fieldNames().asSequence().map(::sectionLabel).toList()
            writer.cover(report["title"].toString(),typeLabel(report["type"]),"${report["periodStart"]} 至 ${report["periodEnd"]}",formatDateTime(report["generatedAt"]),statusLabel(report["status"]))
            writer.contents(sectionNames)
            writer.heading(report["title"].toString())
            writer.line("生成时间：${formatDateTime(report["generatedAt"])}    报告状态：${statusLabel(report["status"])}")
            writer.subheading("一、报告概览")
            val overview=content.path("overviewMetrics")
            val primary=overview.path("primary");val secondary=overview.path("secondary");val unclustered=overview.path("unclustered")
            writer.infoGrid(listOf(
                "报告类型" to typeLabel(report["type"]), "统计周期" to "${report["periodStart"]} 至 ${report["periodEnd"]}",
                primary.path("label").asText("有效反馈") to "${report["rawRecordCount"] ?: 0} ${primary.path("unit").asText("条")}", secondary.path("label").asText("主问题簇") to "${report["uniqueProblemCount"] ?: 0} ${secondary.path("unit").asText("个")}",
                unclustered.path("label").asText("未归簇反馈") to "${unclustered.path("value").asLong(0)} ${unclustered.path("unit").asText("条")}", "指标版本" to overview.path("version").asText("历史口径"),
                "数据最新时间" to formatDateTime(report["dataLatestTime"],"无有效数据"), "数据新鲜度" to freshnessLabel(report["freshnessStatus"])
            ))
            writer.subheading("二、分析摘要")
            if(report["degraded"]==true) writer.paragraph("本报告未获得 AI 文字解读，以下结论仅展示经 SQL 校验的事实数据。")
            else report["generatedSummary"]?.toString()?.takeIf(String::isNotBlank)?.let(writer::paragraph)
                ?: writer.paragraph("本周期暂无可用的文字分析摘要，请结合下方事实表阅读。")
            writer.subheading("三、数据质量与生成信息")
            writer.infoGrid(listOf(
                "数字校验" to validationLabel(report["validationStatus"]), "生成方式" to generationLabel(report["generationSource"]),
                "提示词/技能" to "${report["promptVersion"] ?: "-"} / ${report["skillVersion"] ?: "-"}",
                "分析模型" to "${report["modelName"] ?: if(report["degraded"]==true) "SQL 事实模式" else "未记录"}"
            ))
            val freshness=report["freshness"] as? JsonNode
            freshness?.path("channels")?.takeIf { it.isArray && !it.isEmpty }?.let {
                writer.minorHeading("各渠道数据新鲜度 SLA")
                writer.table(it)
            }
            writer.subheading("四、专题分析")
            val sections = content.path("sections")
            sections.fields().forEach { (name, values) ->
                writer.sectionHeading(sectionLabel(name))
                writer.paragraph(sectionDescription(name))
                if (!values.isArray || values.isEmpty) writer.line("本周期暂无数据。") else writer.table(values)
            }
            writer.subheading("五、统计口径与阅读说明")
            writer.paragraph("本报告中的数量均由预定义参数化 SQL 计算，仅统计已通过脱敏、未删除且位于报告周期内的有效反馈。原始反馈数按记录计数，去重问题数按主问题簇计数；百分比和环比应结合样本量阅读。报告查看页展示生成时的事实快照，不提供跨页面下钻。")
                writer.finish()
                doc.documentInformation.title = report["title"].toString()
                doc.documentInformation.subject = "反馈分析平台结构化报告"
                doc.save(out)
            } finally {
                collection?.close()
            }
        }
        return "report-$id.pdf" to out.toByteArray()
    }

    private fun resolveFont(): File {
        val explicit = listOf(configuredFont, System.getenv("REPORT_PDF_FONT").orEmpty()).filter(String::isNotBlank)
        if (activeProfiles.split(',').any { it.trim() == "prod" } && explicit.isEmpty())
            reportFailed("REPORT_PDF_FONT_EXPLICIT_CONFIG_REQUIRED")
        val candidates = explicit + listOf("/System/Library/Fonts/Supplemental/Arial Unicode.ttf",
            "/usr/share/fonts/truetype/arphic/uming.ttc",
            "/usr/share/fonts/truetype/unfonts-core/UnDotum.ttf",
            "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
            "/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc")
        return candidates.filter(String::isNotBlank).map(::File).firstOrNull(File::isFile)
            ?: reportFailed("REPORT_PDF_FONT_NOT_FOUND")
    }

    private fun validateFont(font:PDType0Font) {
        runCatching { listOf("反馈分析平台", "ABC 0123").forEach(font::getStringWidth) }
            .getOrElse { reportFailed("REPORT_PDF_FONT_GLYPH_MISSING",it) }
    }

    private fun loadFont(doc:PDDocument,file:File,collection:TrueTypeCollection?):PDType0Font {
        if(collection==null)return PDType0Font.load(doc,file).also(::validateFont)
        val fonts=mutableListOf<org.apache.fontbox.ttf.TrueTypeFont>()
        collection.processAllFonts(fonts::add)
        return fonts.firstNotNullOfOrNull { candidate ->
            // PDFBox 的子集化要求真正的 TrueType glyf 表。这里据此
            // 确定性地排除 CFF/OpenType 字体，而不是靠文件名判断。
            candidate.takeIf { runCatching { it.glyph }.getOrNull() != null }
                ?.let { runCatching { PDType0Font.load(doc,it,true).also(::validateFont) }.getOrNull() }
        } ?: reportFailed("REPORT_PDF_FONT_GLYPH_MISSING")
    }

    private fun display(value:JsonNode):String=when {
        value.isNull -> "-"
        value.isObject -> value.fields().asSequence().filter { it.key!="_drilldown" }.joinToString(", ") { "${it.key}=${display(it.value)}" }
        value.isArray -> value.joinToString(", ") { display(it) }
        else -> value.asText()
    }

    private fun typeLabel(value:Any?)=mapOf("DAILY_INGEST" to "每日数据接入报告","JIRA_IMPORT_RECONCILIATION" to "Jira 数据接入与对账报告","WEEKLY_TREND" to "每周多渠道趋势报告","VERSION_RELEASE" to "版本上线观察报告","QUALITY_LINKAGE" to "质量关联报告","REDBLACK_ANALYSIS" to "红黑榜分析报告","RISK_BRIEF" to "风险与舆情简报")[value?.toString()]?:value?.toString().orEmpty()
    private fun statusLabel(value:Any?)=mapOf("PUBLISHED" to "已发布","DRAFT" to "草稿","FAILED" to "生成失败")[value?.toString()]?:value?.toString().orEmpty()
    private fun freshnessLabel(value:Any?)=mapOf("MET" to "正常","BREACHED" to "已超时","UNKNOWN" to "未知")[value?.toString()]?:value?.toString().orEmpty()
    private fun validationLabel(value:Any?)=mapOf("PASS" to "全部通过","PARTIAL" to "部分待核实","FAILED" to "校验失败")[value?.toString()]?:value?.toString().orEmpty()
    private fun generationLabel(value:Any?)=mapOf("MANUAL" to "人工生成","SCHEDULED" to "定时生成","VERSION_EVENT" to "版本事件触发")[value?.toString()]?:value?.toString().orEmpty()
    private fun formatDateTime(value:Any?,fallback:String="-")=value?.toString()?.takeIf(String::isNotBlank)?.replace('T',' ')?.substringBeforeLast('.')?:fallback
    private fun sectionDescription(key:String)=mapOf("ingestOverview" to "各数据渠道在统计周期内的接入规模及变化。","dataQuality" to "字段完整性、映射成功率和综合质量情况。","hotClusters" to "同类反馈归并后的高频问题，不以重复记录补足名次。","redblackOverview" to "红榜代表正向认可，黑榜代表负向反馈；两者按标准情感分别统计。","blackTop10" to "黑榜中出现频率最高的负向问题簇。","positiveModules" to "红榜正向评价集中出现的功能模块。","riskLevel" to "基于活跃告警、负面突增和跨渠道严重问题计算的风险结果。","methodologyNotes" to "用于解释统计限制和跨渠道可比性。 ")[key]?:"以下为本章节经校验的事实数据。"
    private fun fieldLabel(key:String)=mapOf("channel" to "渠道","module" to "功能模块","category" to "问题类型","severity" to "严重度","count" to "数量","previousCount" to "上期数量","changeRate" to "变化率(%)","dailyChangeRate" to "日变化率(%)","concentrationRate" to "集中度(%)","date" to "日期","status" to "状态","taskType" to "任务类型","qualityRate" to "质量率(%)","requiredCompletenessRate" to "必填完整率(%)","mappingSuccessRate" to "映射成功率(%)","warning" to "风险提示","clusterId" to "问题簇ID","title" to "问题名称","recordCount" to "关联反馈数","channelCount" to "渠道数","channels" to "涉及渠道","listType" to "榜单类型","sentiment" to "情感","percentage" to "占比(%)","version" to "版本","packageName" to "应用包名","latestIngestTime" to "最新接入时间","lagHours" to "延迟(小时)","lagSeconds" to "延迟(秒)","thresholdSeconds" to "阈值(秒)","slaStatus" to "SLA状态","linkedRate" to "关联率(%)","linkedClusters" to "已关联问题簇","qualityClusters" to "质量问题簇","sdkCount" to "SDK数量","jiraCount" to "Jira数量","qualityCount" to "质量平台数量","level" to "风险等级","score" to "风险分","factors" to "风险因子","releaseCount" to "发布后数量","baselineCount" to "基线数量","releaseSevereCount" to "发布后严重问题","baselineSevereCount" to "基线严重问题","freshness" to "新鲜度说明","mergedBus" to "合并口径","comparability" to "可比性")[key]?:key

    private fun displayValue(key:String,value:JsonNode):String {
        val raw=display(value).ifBlank{"-"}
        if(key=="factors"&&value.isObject)return value.fields().asSequence().filter{it.key!="_drilldown"}.joinToString("，"){"${factorLabel(it.key)}：${display(it.value)}"}
        val translated=mapOf("BREACHED" to "已超时","MET" to "正常","UNKNOWN" to "未知","PASS" to "已安全放行","REVIEW_NEEDED" to "待人工复核","FAIL" to "处理失败","PENDING" to "处理中","NEGATIVE" to "负向","NEUTRAL" to "中性","POSITIVE" to "正向","CRITICAL" to "严重","HIGH" to "高","MEDIUM" to "中","LOW" to "低","IMPORT_FAILED" to "导入失败","CLUSTER_REVIEW" to "归簇待复核","CAMERA_MIC" to "摄像头与麦克风","AI_TUTOR" to "AI 助教","SYNC_COURSE" to "同步课程","WRONG_QUESTION_BOOK" to "错题本","VIDEO_PLAYBACK" to "视频播放","BUG" to "程序缺陷","HARDWARE" to "硬件问题","CONTENT_ERROR" to "内容错误")
        if(raw in translated)return translated.getValue(raw)
        return if(key.endsWith("Time")||key.endsWith("At"))formatDateTime(raw) else raw
    }

    private fun factorLabel(key:String)=mapOf("blackResonance" to "黑榜共振","negativeSurges" to "负面情感突增","activeAlertScore" to "活跃告警得分","severeCrossChannel" to "跨渠道严重问题")[key]?:key

    private fun sectionLabel(key: String) = mapOf("ingestOverview" to "接入总览","importBatchStatus" to "导入批次状态","maskingStatus" to "脱敏情况","dataQuality" to "数据质量","pendingTasks" to "待处理任务","dailyTrend" to "七日趋势","mergedBus" to "合并总线","channelComparison" to "渠道对比","categorySeverity" to "问题类型与严重度","versionTop10" to "应用版本 TOP10","hotClusters" to "热点问题 TOP10","crossChannelClusters" to "跨渠道关联","jiraTimeliness" to "Jira处理时效","sentimentTrend" to "情感趋势","methodologyNotes" to "口径说明","releaseMetadata" to "版本信息","versionOverview" to "版本概况","newClusters" to "新增问题清单","baselineComparison" to "对比基线","channelDistribution" to "渠道分布","concentration" to "问题集中度","threeDayTrend" to "三日趋势","linkageOverview" to "关联概览","linkageMatrix" to "关联矩阵","qualityOnly" to "质量单向问题","reverseBlindSpots" to "质量检测盲区","firstSeenLag" to "时效分析","redblackOverview" to "红黑榜概况","blackTop10" to "黑榜热点","resonance" to "共振检测","positiveModules" to "红榜提取","channelMatrix" to "渠道关联矩阵","burstCandidates" to "刷屏/水军候选","riskLevel" to "风险等级","activeAlerts" to "活跃告警","negativeSentimentSurges" to "负面情感突增","crossChannelRisks" to "跨渠道风险","blackResonance" to "黑榜共振","hotProblems" to "热点问题","attentionItems" to "需关注项")[key] ?: key

    private inner class PdfWriter(private val doc: PDDocument, private val font: PDType0Font) {
        private var page = PDPage(PDRectangle.A4)
        private var stream: PDPageContentStream
        private var y = 800f
        private var pageNo = 1
        private var currentSection: String? = null
        init { doc.addPage(page); stream = PDPageContentStream(doc, page); header() }
        fun cover(title:String,type:String,period:String,generated:String,status:String){
            y=700f;write("反馈分析平台",14f,54f,28f);wrap(title,24f,487f).forEach{write(it,24f,54f,34f)};y-=18f
            stream.setNonStrokingColor(Color(47,91,138));stream.addRect(54f,y-4f,90f,4f);stream.fill();y-=38f
            infoGrid(listOf("报告类型" to type,"统计周期" to period,"生成时间" to generated,"报告状态" to status))
            y=105f;write("客户验收版 · 数据仅来自已脱敏事实快照",10f,54f,18f);newPage()
        }
        fun contents(sections:List<String>){
            heading("目录");listOf("报告概览","分析摘要","数据质量与生成信息","专题分析").plus(sections).plus("统计口径与阅读说明").forEachIndexed{i,item->line("${i+1}. $item")};newPage()
        }
        fun heading(text: String) { write(text, 20f, 54f, 28f); y -= 8 }
        fun subheading(text: String) { ensure(34f); currentSection=text;write(text, 14f, 54f, 22f) }
        fun minorHeading(text:String) { ensure(28f); write(text,12f,54f,19f) }
        fun sectionHeading(text:String) { ensure(92f); write(text,12f,54f,19f) }
        fun line(text: String) { text.replace('\t',' ').split('\n').flatMap { wrap(it,10.5f,487f) }.forEach { ensure(19f); write(it, 10.5f, 54f, 18f) } }
        fun paragraph(text:String) { wrap(text.replace('\n',' '),10.5f,487f).forEach { ensure(18f); write(it,10.5f,54f,17f) }; y-=5f }
        fun infoGrid(items:List<Pair<String,String>>) {
            items.chunked(2).forEach { row ->
                ensure(34f); val width=243.5f
                row.forEachIndexed { index,item ->
                    val x=54f+index*width;stream.setNonStrokingColor(Color(244,247,250));stream.addRect(x,y-27f,width-4f,29f);stream.fill()
                    writeAt(item.first,8.5f,x+7f,y-9f,Color(95,105,115));writeAt(item.second,10f,x+72f,y-10f,Color(25,40,55))
                };y-=34f
            };y-=3f
        }
        fun table(values:JsonNode) {
            val allRows=values.filter { it.isObject };val rows=allRows.take(2000)
            if(rows.isEmpty()){line("本周期暂无数据。");return}
            val keys=rows.flatMap { it.fieldNames().asSequence().toList() }.filter { it!="_drilldown" }.distinct()
            if(keys.isEmpty()){line("本周期暂无可展示字段。");return}
            keys.chunked(6).forEachIndexed { groupIndex,group ->
                if(keys.size>6)minorHeading("字段组 ${groupIndex+1}/${(keys.size+5)/6}")
                val width=487f/group.size; val fontSize=if(group.size>=5)7.2f else 8.2f
                val headerHeight=maxOf(25f,group.map(::fieldLabel).maxOf{wrap(it,fontSize,width-8f).size}*10f+7f)
                fun drawHeader(){ensure(headerHeight+4f);group.forEachIndexed{i,key->drawCell(fieldLabel(key),54f+i*width,y,width,headerHeight,fontSize,true)};y-=headerHeight}
                drawHeader()
                rows.forEach { row ->
                    val texts=group.map { displayValue(it,row.path(it)) }
                    val lineCounts=texts.map{wrap(it,fontSize,width-8f).size};val height=maxOf(23f,(lineCounts.maxOrNull()?:1)*9.5f+7f).coerceAtMost(250f)
                    if(y-height<48f){ensure(height+headerHeight+8f);drawHeader()}
                    group.indices.forEach{i->drawCell(texts[i],54f+i*width,y,width,height,fontSize,false)};y-=height
                };y-=24f
            }
            if(allRows.size>rows.size)paragraph("本章节共有 ${allRows.size} 行，PDF 为保证可阅读性仅展示前 ${rows.size} 行；完整数据请使用受控数据导出功能。")
        }
        private fun drawCell(text:String,x:Float,top:Float,width:Float,height:Float,size:Float,header:Boolean){
            stream.setNonStrokingColor(if(header)Color(47,91,138) else Color.WHITE);stream.addRect(x,top-height,width,height);stream.fill()
            stream.setStrokingColor(Color(205,213,222));stream.addRect(x,top-height,width,height);stream.stroke()
            val color=if(header)Color.WHITE else Color(35,45,55);wrap(text,size,width-8f).take(((height-6f)/9.5f).toInt().coerceAtLeast(1)).forEachIndexed{i,line->writeAt(line,size,x+4f,top-11f-i*9.5f,color)}
        }
        fun rule() { ensure(18f); stream.moveTo(54f, y); stream.lineTo(541f, y); stream.stroke(); y -= 16f }
        private fun header() { stream.setNonStrokingColor(Color(35, 82, 124)); stream.addRect(0f, 812f, 595f, 30f); stream.fill();stream.beginText();stream.setFont(font,9f);stream.setNonStrokingColor(Color.WHITE);stream.newLineAtOffset(54f,822f);stream.showText("反馈分析平台 · AI报告");stream.endText(); y = 790f }
        private fun newPage(){stream.close();pageNo++;page=PDPage(PDRectangle.A4);doc.addPage(page);stream=PDPageContentStream(doc,page);header()}
        private fun ensure(height: Float) { if (y - height < 48f) { newPage();currentSection?.let{write("$it（续）",12f,54f,20f)} } }
        private fun write(text: String, size: Float, x: Float, leading: Float) { stream.beginText(); stream.setFont(font,size); stream.setNonStrokingColor(Color(30,30,30)); stream.newLineAtOffset(x,y); stream.showText(safeText(text)); stream.endText(); y -= leading }
        private fun writeAt(text:String,size:Float,x:Float,textY:Float,color:Color){stream.beginText();stream.setFont(font,size);stream.setNonStrokingColor(color);stream.newLineAtOffset(x,textY);stream.showText(safeText(text));stream.endText()}
        private fun safeText(value:String):String{val out=StringBuilder();value.codePoints().forEach{codePoint->val char=String(Character.toChars(codePoint));when{codePoint==9||codePoint==10||codePoint==13||codePoint==0x00A0->out.append(' ');codePoint<32||codePoint in setOf(0x200B,0x200C,0x200D,0xFEFF)->Unit;codePoint in 0x2010..0x2015||codePoint==0x2212->out.append('-');runCatching{font.getStringWidth(char)}.isSuccess->out.append(char);Character.isSupplementaryCodePoint(codePoint)->out.append("[符号]");else->out.append('?')}};return out.toString()}
        private fun wrap(value:String,size:Float,width:Float):List<String>{val safe=safeText(value);if(safe.isEmpty())return listOf("");val lines=mutableListOf<String>();var current="";safe.codePoints().forEach{codePoint->val char=String(Character.toChars(codePoint));val candidate=current+char;if(current.isNotEmpty()&&font.getStringWidth(candidate)/1000f*size>width){lines+=current;current=char}else current=candidate};if(current.isNotEmpty())lines+=current;return lines}
        fun finish() { stream.close();val total=doc.numberOfPages;doc.pages.forEachIndexed{i,p->PDPageContentStream(doc,p,PDPageContentStream.AppendMode.APPEND,true).use{s->s.beginText();s.setFont(font,9f);s.setNonStrokingColor(Color(100,100,100));s.newLineAtOffset(268f,24f);s.showText("第 ${i+1} / $total 页");s.endText()}} }
    }
}
