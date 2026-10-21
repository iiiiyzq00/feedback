package com.feedback.manager.station

import com.feedback.service.station.*
import com.feedback.model.station.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.ClientException
import com.feedback.service.imports.ImportFileParser
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Station 历史导出文件读取器；数据库写入前完成整文件大小、表头、行数和字段校验。 */
@Component
class StationLegacyExportFileReader(private val parser: ImportFileParser) {
    companion object {
        const val MAX_FILE_BYTES = 50L * 1024 * 1024
        const val MAX_ROWS = 50_000
        const val MAX_PHOTOS = 9
        private val IMPORT_HEADERS = setOf("反馈ID", "产品ID", "反馈标题", "反馈内容", "创建时间", "更新时间")
        private val RECOVERY_HEADERS = setOf("反馈ID", "用户电话", "图片")
        private val DATE_FORMATS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/M/d H:mm:ss"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
        )
    }

    /** 有界读取并校验 Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun readImport(path: Path): StationLegacyExportFile<StationLegacyExportRow> {
        val parsed = parse(path)
        requireHeaders(parsed.sheet.header, IMPORT_HEADERS, "STATION_LEGACY_HEADERS_INVALID")
        val rows = parsed.rows.mapIndexed { index, values ->
            val rowNumber = index + 2
            StationLegacyExportRow(
                rowNumber = rowNumber,
                recordId = required(values, "反馈ID", 100, rowNumber),
                productId = optional(values, "产品ID", 100) ?: "UNKNOWN",
                title = values["反馈标题"].orEmpty().take(500),
                content = values["反馈内容"].orEmpty(),
                createdAt = parseDate(required(values, "创建时间", 50, rowNumber), "创建时间", rowNumber),
                updatedAt = parseDate(required(values, "更新时间", 50, rowNumber), "更新时间", rowNumber),
                productName = optional(values, "产品名称", 200),
                appId = optional(values, "应用ID", 100),
                appName = optional(values, "应用名称", 200),
                appVersion = optional(values, "软件版本", 50),
                feedbackType = optional(values, "反馈类型", 50),
                platform = optional(values, "反馈平台", 50),
                subChannel = optional(values, "反馈渠道", 50),
                sourceDescription = optional(values, "反馈描述", 1_000),
                sourceUserId = optional(values, "uid", 100) ?: optional(values, "用户名", 100),
                phone = optional(values, "用户电话", 1_024),
                photoUrls = photos(values["图片"].orEmpty(), rowNumber),
                processingStatus = if (values["反馈状态"].orEmpty() == "未解决") "OPEN" else "CLOSED"
            )
        }
        requireUnique(rows.map(StationLegacyExportRow::recordId))
        return StationLegacyExportFile(rows, parsed.sha256)
    }

    /** 有界读取并校验 Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    fun readRecovery(path: Path): StationLegacyExportFile<StationLegacyRecoveryRow> {
        val parsed = parse(path)
        requireHeaders(parsed.sheet.header, RECOVERY_HEADERS, "STATION_LEGACY_RECOVERY_HEADERS_INVALID")
        val rows = parsed.rows.mapIndexed { index, values ->
            val rowNumber = index + 2
            StationLegacyRecoveryRow(
                rowNumber,
                required(values, "反馈ID", 100, rowNumber),
                optional(values, "用户电话", 1_024),
                photos(values["图片"].orEmpty(), rowNumber)
            )
        }
        requireUnique(rows.map(StationLegacyRecoveryRow::recordId))
        return StationLegacyExportFile(rows, parsed.sha256)
    }

    /** 解析并校验外部输入：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun parse(path: Path): ParsedRows {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_FILE_NOT_FOUND")
        }
        val size = Files.size(path)
        if (size !in 1..MAX_FILE_BYTES) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_FILE_SIZE_INVALID")
        }
        val bytes = Files.readAllBytes(path)
        val parsed = parser.parse(path.fileName.toString(), bytes)
        if (parsed.sheets.size != 1) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_SINGLE_SHEET_REQUIRED")
        }
        val sheet = parsed.sheets.single()
        if (sheet.dataRows.isEmpty()) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_NO_DATA")
        }
        if (sheet.dataRows.size > MAX_ROWS) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_ROW_LIMIT_EXCEEDED")
        }
        val indexes = sheet.header.withIndex().associate { it.value to it.index }
        val rows = sheet.dataRows.map { row ->
            indexes.mapValues { (_, column) -> row.getOrElse(column) { "" }.trim() }
        }
        return ParsedRows(sheet, rows, sha256(bytes))
    }

    /** 解析 Station 照片列为有序 URL 列表；去空、去重并限制单条记录最多九个。 */
    private fun photos(value: String, row: Int): List<String> = value.split(Regex("[,;\\n]+"))
        .map(String::trim).filter(String::isNotBlank).also { urls ->
            if (urls.size > MAX_PHOTOS || urls.any { it.length > 2_048 }) {
                throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_PHOTOS_INVALID")
            }
        }

    /** 校验输入或不变量：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun required(values: Map<String, String>, field: String, max: Int, row: Int): String =
        values[field].orEmpty().also { value ->
            if (value.isBlank() || value.length > max) {
                throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_FIELD_INVALID")
            }
        }

    /** 读取可选导入列并执行长度限制；空白值归一为空，超限立即拒绝当前行。 */
    private fun optional(values: Map<String, String>, field: String, max: Int): String? = values[field].orEmpty()
        .takeIf(String::isNotBlank)?.also {
            if (it.length > max) {
                throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_FIELD_TOO_LONG")
            }
        }

    /** 解析并校验外部输入：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun parseDate(value: String, field: String, row: Int): LocalDateTime = DATE_FORMATS.firstNotNullOfOrNull { format ->
        try { LocalDateTime.parse(value, format) } catch (_: DateTimeParseException) { null }
    } ?: throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_DATE_INVALID")

    /** 校验输入或不变量：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun requireHeaders(actual: List<String>, required: Set<String>, error: String) {
        val named = actual.filter(String::isNotBlank)
        if (!actual.containsAll(required) || named.distinct().size != named.size) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_HEADERS_INVALID")
        }
    }

    /** 校验输入或不变量：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun requireUnique(recordIds: List<String>) {
        if (recordIds.distinct().size != recordIds.size) {
            throw ClientException(ErrorCode.BAD_REQUEST, "STATION_LEGACY_ID_DUPLICATED")
        }
    }

    /** 计算稳定 SHA-256 摘要：Station 链路；保持显式输入上限、敏感信息边界和失败关闭策略。 */
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

/** ParsedRows 链路组件；职责边界、外部 I/O 和失败策略由公开方法契约约束。 */
    private data class ParsedRows(
        val sheet: com.feedback.service.imports.ParsedSheet,
        val rows: List<Map<String, String>>,
        val sha256: String
    )

}
