package com.feedback.mock

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Random

/**
 * 第 9 天的确定性、隐私安全夹具生成器。
 *
 * 它刻意不连接 Spring 或任何基础设施。生成的 SDK 信封只是
 * D9 集成夹具的素材；真正的传输加密与请求签名由那套夹具执行，
 * 绝不模拟成成功。
 */
object MockDataCommand {
    private const val DEFAULT_SEED = 20260804L
    private val mapper = ObjectMapper().registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)
    private val supportedTasks = setOf("generate-sdk-mock", "generate-table-mock")

    fun supports(args: Array<String>): Boolean = option(args, "task") in supportedTasks

    fun run(args: Array<String>) {
        val options = Options.parse(args)
        val result = MockFixtureGenerator(mapper).generate(options)
        println("DAY9_MOCK_GENERATED task=${options.task} seed=${options.seed} records=${result.recordCount}")
        println("output=${result.outputDir.toAbsolutePath().normalize()}")
        println("manifest=${result.manifest.toAbsolutePath().normalize()}")
    }

    private fun option(args: Array<String>, name: String): String? {
        val prefix = "--$name="
        return args.firstOrNull { it.startsWith(prefix) }?.substring(prefix.length)
    }

    data class Options(
        val task: String,
        val count: Int,
        val seed: Long,
        val channel: String?,
        val output: Path,
        val format: String,
        val scenario: String
    ) {
        companion object {
            fun parse(args: Array<String>): Options {
                val values = args.filter { it.startsWith("--") && it.contains('=') }
                    .associate { raw -> raw.removePrefix("--").substringBefore('=') to raw.substringAfter('=') }
                val task = values["task"] ?: error("缺少 --task")
                require(task in supportedTasks) { "不支持的 mock task: $task" }
                val count = values["count"]?.toIntOrNull() ?: error("--count 必须是整数")
                require(count in 1..100_000) { "--count 必须在 1..100000" }
                val seed = values["seed"]?.toLongOrNull() ?: DEFAULT_SEED
                val channel = values["channel"]?.uppercase()
                if (task == "generate-table-mock") require(channel in TableSchema.entries.map { it.name }) {
                    "--channel 必须是 JIRA、QUALITY 或 REDBLACK"
                }
                require(task != "generate-sdk-mock" || channel == null) { "SDK 任务不接受 --channel" }
                val format = values["format"]?.lowercase() ?: if (task == "generate-table-mock") "both" else "ndjson"
                require(format in setOf("csv", "xlsx", "both", "ndjson")) { "--format 必须是 csv、xlsx、both 或 ndjson" }
                if (task == "generate-table-mock") require(format != "ndjson") { "表格任务不支持 ndjson" }
                if (task == "generate-sdk-mock") require(format == "ndjson") { "SDK 任务仅支持 ndjson" }
                val scenario = values["scenario"] ?: if (values.containsKey("crash-spike")) "crash-spike" else "mixed"
                require(scenario in setOf("mixed", "normal", "crash-spike")) { "未知 --scenario: $scenario" }
                val output = Path.of(values["output"] ?: "data/mock")
                    .resolve("seed-$seed")
                    .resolve(if (task == "generate-sdk-mock") "sdk" else channel!!.lowercase())
                return Options(task, count, seed, channel, output, format, scenario)
            }
        }
    }
}

data class GenerationResult(val outputDir: Path, val manifest: Path, val recordCount: Int)

class MockFixtureGenerator(private val mapper: ObjectMapper = ObjectMapper().registerKotlinModule().enable(SerializationFeature.INDENT_OUTPUT)) {
    private val anchor = Instant.parse("2026-08-04T02:00:00Z")

    fun generate(options: MockDataCommand.Options): GenerationResult {
        Files.createDirectories(options.output)
        val files = mutableListOf<Path>()
        val distribution: Map<String, Any>
        val expectations: Map<String, Int>
        if (options.task == "generate-sdk-mock") {
            val records = sdkRecords(options)
            val target = options.output.resolve("sdk-${options.count}.ndjson")
            Files.writeString(target, records.joinToString("\n") { mapper.writeValueAsString(it) } + "\n")
            files.add(target)
            distribution = distribution(records)
            expectations = records.groupingBy { it.expected_outcome }.eachCount()
        } else {
            val schema = TableSchema.valueOf(options.channel!!)
            val rows = tableRows(schema, options.count, options.seed)
            val businessRows = rows.map { it.dropLast(2) }
            if (options.format in setOf("csv", "both")) files.add(writeCsv(options.output.resolve("${schema.name.lowercase()}-${options.count}.csv"), schema.headers, businessRows))
            if (options.format in setOf("xlsx", "both")) files.add(writeXlsx(options.output.resolve("${schema.name.lowercase()}-${options.count}.xlsx"), schema.headers, schema.name, businessRows))
            files.add(writeCsv(options.output.resolve("fixture-expectations.csv"), listOf("问题编号", "预期结果", "跨渠道簇"), rows.map { listOf(it.first(), it[it.size - 2], it.last()) }))
            files.addAll(writeTableVariants(options.output, schema, rows))
            distribution = mapOf("channel" to schema.name, "cross_channel_cluster_rows" to rows.count { it.last() != "NONE" })
            expectations = rows.groupingBy { it[it.size - 2] }.eachCount()
        }
        val manifest = options.output.resolve("manifest.json")
        val hashes = files.sortedBy { it.fileName.toString() }.associate { it.fileName.toString() to sha256(it) }
        val report = linkedMapOf<String, Any?>(
            "schema_version" to "day9-mock-manifest-v1",
            "task" to options.task,
            "channel" to options.channel,
            "seed" to options.seed,
            "anchor_time" to anchor.toString(),
            "record_count" to options.count,
            "scenario" to options.scenario,
            "synthetic_data_only" to true,
            "contains_real_personal_data" to false,
            "transport_note" to "SDK plaintext fixtures must be encrypted and signed by the integration harness",
            "distribution" to distribution,
            "expected_outcomes" to expectations,
            "files_sha256" to hashes
        )
        mapper.writeValue(manifest.toFile(), report)
        return GenerationResult(options.output, manifest, options.count)
    }

    private fun sdkRecords(options: MockDataCommand.Options): List<SdkFixture> {
        val random = Random(options.seed)
        val packages = listOf("com.company.english", "com.company.math", "com.company.tutor")
        val modules = listOf("AI_TUTOR", "SYNC_COURSE", "WRONG_QUESTION_BOOK", "CAMERA_MIC", "VIDEO_PLAYBACK")
        val problems = listOf("CRASH", "FREEZE", "MIC_FAIL", "WRONG_RESULT", "NO_RESPONSE", "LAG")
        val versions = listOf("3.1.9", "3.2.0", "3.2.1", "3.2.2", "4.0.0")
        val regions = listOf(
            Triple("440000", "440100", "440106"), // 广东省 广州市 天河区
            Triple("110000", "110100", "110105"), // 北京市 市辖区 朝阳区
            Triple("310000", "310100", "310115"), // 上海市 市辖区 浦东新区
            Triple("330000", "330100", "330106"), // 浙江省 杭州市 西湖区
            Triple("510000", "510100", "510107")  // 四川省 成都市 武侯区
        )
        val devices = listOf("XT11A-MOCK", "XT12-MOCK", "PAD-A-MOCK", "PAD-B-MOCK")
        val networks = listOf("WIFI", "5G", "4G", "UNKNOWN")
        return (0 until options.count).map { index ->
            val bucket = index % 20
            val outcome = when (bucket) {
                0 -> "REJECT_SIGNATURE"
                1 -> "REJECT_REPLAY"
                2 -> "REVIEW_NEEDED"
                3 -> "FAILED_MASKING"
                4 -> "IDEMPOTENT_REPLAY"
                else -> "PASS"
            }
            val problem = when {
                options.scenario == "crash-spike" -> "CRASH"
                options.scenario == "mixed" && index % 10 < 3 -> "CRASH"
                else -> problems[index % problems.size]
            }
            val version = if (options.scenario == "crash-spike" || (problem == "CRASH" && index % 3 != 0)) "3.2.1" else versions[index % versions.size]
            val cluster = if (index < minOf(options.count, 40)) "XC-%02d".format(index % 16 + 1) else "NONE"
            val sentiment = when (index % 20) { in 0..11 -> "NEGATIVE"; in 12..16 -> "NEUTRAL"; else -> "POSITIVE" }
            val sensitive = if (outcome == "REVIEW_NEEDED") "未知编号 1234567890 需要人工判断" else when (index % 4) {
                0 -> "测试用户张三反馈，测试手机号1380013%04d".format(index % 10000)
                1 -> "虚构家长李四邮箱 demo%04d@example.test".format(index)
                2 -> "模拟设备SN MOCK-SN-%06d，地址测试市测试区".format(index)
                else -> "不含身份信息的模拟反馈"
            }
            val module = modules[index % modules.size]
            val region = regions[index % regions.size]
            val title = when (problem) {
                "CRASH" -> "AI助教打开后闪退"
                "FREEZE" -> "同步课程页面卡死"
                "MIC_FAIL" -> "麦克风无法录音"
                "WRONG_RESULT" -> "题目结果判断错误"
                "NO_RESPONSE" -> "点击按钮无响应"
                else -> "视频播放明显卡顿"
            }
            SdkFixture(
                client_feedback_id = if (outcome == "IDEMPOTENT_REPLAY" && index > 0) "D9-SDK-%05d".format(index - 1) else "D9-SDK-%05d".format(index),
                package_name = packages[index % packages.size], app_version = version, function_module = module,
                issue_type = problem, severity_level = "S${index % 4}", sentiment = sentiment,
                reproducibility = reproducibility(index),
                source_event_time = anchor.minus((index * 37L) % 10080, ChronoUnit.MINUTES).toString(),
                province_code = region.first, city_code = region.second, district_code = region.third,
                device_model = devices[index % devices.size],
                device_sn = "MOCK-SN-%06d".format(index), network_type = networks[index % networks.size],
                title = title, description = "$title。$sensitive。随机变体${random.nextInt(10000)}。",
                expected_outcome = outcome, cross_channel_cluster = cluster,
                contact_allowed = index % 3 == 0,
                attachment = when {
                    index % 25 == 0 -> AttachmentFixture("INVALID_TYPE", 1024, "REJECT")
                    index % 10 == 0 -> AttachmentFixture("SCREENSHOT", 4096L + index, "RETRY_THEN_PASS")
                    else -> null
                },
                transport = TransportFixture(
                    needs_encryption = true,
                    signature_mode = if (outcome == "REJECT_SIGNATURE") "INVALID" else "VALID",
                    nonce_mode = if (outcome == "REJECT_REPLAY") "REUSED" else "UNIQUE",
                    fixture_token = Base64.getUrlEncoder().withoutPadding().encodeToString("${options.seed}:$index".toByteArray())
                )
            )
        }
    }

    private fun tableRows(schema: TableSchema, count: Int, seed: Long): List<List<String>> {
        val random = Random(seed + schema.ordinal * 997L)
        return (0 until count).map { index ->
            val cluster = if (index < minOf(count, 30)) "XC-%02d".format(index % 16 + 1) else "NONE"
            val expected = when (index % 25) { 0 -> "ROW_VALIDATION_FAILED"; 1 -> "MASKING_REVIEW"; 3 -> "UPDATE_EXISTING"; else -> "IMPORTED" }
            val business = schema.row(index, anchor.minus((index * 53L) % 20160, ChronoUnit.MINUTES).toString(), random.nextInt(10000), cluster).toMutableList()
            if (expected == "ROW_VALIDATION_FAILED") business[3] = "not-a-date"
            if (expected == "UPDATE_EXISTING" && index > 0) business[0] = schema.row(index - 1, "ignored", 0, cluster).first()
            if (expected == "MASKING_REVIEW") business[2] = "未知编号 1234567890 需要人工判断"
            business + listOf(expected, cluster)
        }
    }

    private fun writeCsv(path: Path, headers: List<String>, rows: List<List<String>>): Path {
        val content = (listOf(headers) + rows).joinToString("\r\n") { row -> row.joinToString(",") { csv(it) } } + "\r\n"
        Files.writeString(path, "\uFEFF$content")
        return path
    }

    private fun writeXlsx(path: Path, headers: List<String>, sheetName: String, rows: List<List<String>>): Path {
        XSSFWorkbook().use { workbook ->
            val sheet = workbook.createSheet(sheetName)
            val header = sheet.createRow(0)
            headers.forEachIndexed { i, value -> header.createCell(i).setCellValue(value) }
            rows.forEachIndexed { r, values ->
                val row = sheet.createRow(r + 1)
                values.forEachIndexed { c, value -> row.createCell(c).setCellValue(value) }
            }
            Files.newOutputStream(path).use(workbook::write)
        }
        return path
    }

    private fun writeTableVariants(dir: Path, schema: TableSchema, rows: List<List<String>>): List<Path> {
        val variants = dir.resolve("variants").also(Files::createDirectories)
        val output = mutableListOf<Path>()
        output.add(writeCsv(variants.resolve("missing-required.csv"), schema.headers.drop(1), rows.take(5).map { it.dropLast(2).drop(1) }))
        output.add(writeCsv(variants.resolve("unknown-nonempty-column.csv"), schema.headers + "未经审批的新列", rows.take(5).map { it.dropLast(2) + "必须暂停" }))
        val wrong = TableSchema.entries.first { it != schema }
        output.add(writeCsv(variants.resolve("channel-mismatch-${wrong.name.lowercase()}.csv"), wrong.headers, tableRows(wrong, 5, 99L).map { it.dropLast(2) }))
        output.add(writeCsv(variants.resolve("duplicate-and-out-of-order.csv"), schema.headers, listOf(rows.first().dropLast(2), rows.first().dropLast(2))))
        return output
    }

    private fun distribution(records: List<SdkFixture>): Map<String, Any> = linkedMapOf(
        "packages" to records.groupingBy { it.package_name }.eachCount(),
        "modules" to records.groupingBy { it.function_module }.eachCount(),
        "problems" to records.groupingBy { it.issue_type }.eachCount(),
        "severity" to records.groupingBy { it.severity_level }.eachCount(),
        "sentiment" to records.groupingBy { it.sentiment }.eachCount(),
        "versions" to records.groupingBy { it.app_version }.eachCount(),
        "provinces" to records.groupingBy { it.province_code }.eachCount(),
        "cross_channel_cluster_rows" to records.count { it.cross_channel_cluster != "NONE" }
    )

    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}

data class SdkFixture(
    val client_feedback_id: String, val package_name: String, val app_version: String,
    val function_module: String, val issue_type: String, val severity_level: String,
    val sentiment: String, val reproducibility: String, val source_event_time: String, val province_code: String,
    val city_code: String, val district_code: String,
    val device_model: String, val device_sn: String, val network_type: String,
    val title: String, val description: String, val expected_outcome: String,
    val cross_channel_cluster: String, val contact_allowed: Boolean,
    val attachment: AttachmentFixture?, val transport: TransportFixture
)
data class AttachmentFixture(val type: String, val size_bytes: Long, val expected_outcome: String)
data class TransportFixture(val needs_encryption: Boolean, val signature_mode: String, val nonce_mode: String, val fixture_token: String)

private fun reproducibility(index: Int) = when (index % 100) {
    in 0..34 -> "ALWAYS"
    in 35..62 -> "FREQUENT"
    in 63..87 -> "OCCASIONAL"
    else -> "ONCE"
}

private val mockModules = listOf("AI_TUTOR", "SYNC_COURSE", "WRONG_QUESTION_BOOK", "CAMERA_MIC", "VIDEO_PLAYBACK")
private val mockSymptoms = listOf(
    "AI助教打开后闪退", "同步课程页面卡死", "错题本答案错误", "麦克风无法录音", "视频播放明显卡顿"
)
private val mockCategories = listOf("BUG", "BUG", "CONTENT_ERROR", "HARDWARE", "BUG")
private fun mockModule(index: Int) = mockModules[index % mockModules.size]
private fun mockSymptom(index: Int) = mockSymptoms[index % mockSymptoms.size]
private fun mockCategory(index: Int) = mockCategories[index % mockCategories.size]

enum class TableSchema(val headers: List<String>) {
    JIRA(listOf("问题编号", "问题标题", "问题描述", "创建时间", "更新时间", "状态", "优先级", "项目Key", "经办人", "报告人", "功能模块", "问题类型", "可重现性")) {
        override fun row(i: Int, time: String, variant: Int, cluster: String) = listOf(
            "D9-${1000 + i}", if (cluster == "NONE") "${mockSymptom(i)}-$variant" else "AI助教打开后闪退-$cluster",
            "${if (cluster == "NONE") mockSymptom(i) else "AI助教打开后闪退"}，虚构用户张三，测试电话1380013%04d；仅用于脱敏验证".format(i), time, time,
            listOf("Open", "处理中", "Resolved", "Reopened")[i % 4], listOf("Highest", "High", "Medium", "Low")[i % 4],
            "DEMO", "模拟经办人${i % 7}", "模拟报告人${i % 9}", mockModule(i),
            if (cluster == "NONE") listOf("Bug", "缺陷", "内容错误", "缺陷", "Bug")[i % 5] else "Bug", reproducibility(i)
        )
    },
    QUALITY(listOf("问题编号", "问题标题", "问题描述", "发生时间", "维修区域", "省份", "城市", "区县", "区域", "申请类型", "换货申请", "退货原因", "发货日期", "功能模块", "可重现性")) {
        override fun row(i: Int, time: String, variant: Int, cluster: String) = listOf(
            "Q-D9-%05d".format(i), if (cluster == "NONE") "${mockSymptom(i)}质量单-$variant" else "AI助教闪退-$cluster",
            "模拟设备MOCK-SN-%06d出现%s，不含真实数据".format(i, if (cluster == "NONE") mockSymptom(i) else "AI助教打开后闪退"), time, listOf("华东", "华南", "华北")[i % 3],
            listOf("440000", "110000", "310000", "330000", "510000")[i % 5],
            listOf("440100", "110100", "310100", "330100", "510100")[i % 5],
            listOf("440106", "110105", "310115", "330106", "510107")[i % 5],
            listOf("天河区", "朝阳区", "浦东新区", "西湖区", "武侯区")[i % 5], listOf("换机", "维修", "投诉", "咨询")[i % 4],
            if (i % 4 == 0) "是" else "否", if (i % 5 == 0) "模拟退货原因" else "", time.take(10), mockModule(i), reproducibility(i)
        )
    },
    REDBLACK(listOf("问题编号", "问题标题", "问题描述", "发布时间", "原始ID", "UID", "作者", "链接", "来源平台", "发布类型", "板块", "红黑榜类型", "是否删除", "功能模块", "问题分类", "可重现性")) {
        override fun row(i: Int, time: String, variant: Int, cluster: String): List<String> {
            val praise = i % 3 != 0
            val symptom = if (cluster == "NONE") mockSymptom(i) else "AI助教打开后闪退"
            val title = if (praise) "红榜好评：${mockModule(i)}使用体验良好-$variant" else "${symptom}评价-$variant"
            val description = if (praise) "模拟红榜正面评价：${mockModule(i)}使用体验良好，操作顺畅，推荐使用。" else "模拟黑榜：$symptom"
            return listOf(
            "RB-D9-%05d".format(i), title,
            description, time, "ORIG-${10000 + i}",
            "MOCK-UID-%05d".format(i), "虚构作者${i % 11}", "https://example.test/mock/$i", "模拟平台",
            "公开评价", listOf("产品", "课程", "服务")[i % 3], if (praise) "红榜" else "黑榜", "否",
            mockModule(i), if (praise) "PRAISE" else mockCategory(i), reproducibility(i)
        )
        }
    };
    abstract fun row(i: Int, time: String, variant: Int, cluster: String): List<String>
}
