package com.feedback.service.station

import com.feedback.model.station.*

import com.feedback.rpc.station.*
import com.feedback.convert.station.*
import com.feedback.repository.remote.station.*
import com.feedback.manager.station.*
import com.feedback.manager.station.*
import com.feedback.security.station.*
import com.feedback.manager.station.*

import com.feedback.common.exception.ClientException
import com.feedback.service.imports.ImportFileParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class StationLegacyExportFileReaderTest {
    @TempDir lateinit var temp: Path
    private val reader = StationLegacyExportFileReader(ImportFileParser())

    @Test
    fun `validates complete csv then preserves legacy title and content contract`() {
        val title = "题".repeat(510)
        val content = "内容".repeat(900)
        val file = write("valid.csv", """
            反馈ID,产品ID,反馈标题,反馈内容,创建时间,更新时间,图片
            legacy-1,product-1,$title,$content,2024-01-02 03:04:05,2024/1/2 4:05:06,https://e.test/a.jpg;https://e.test/b.jpg
        """.trimIndent())

        val parsed = reader.readImport(file).rows.single()

        assertEquals(500, parsed.title.length)
        assertEquals(content, parsed.content)
        assertEquals(2, parsed.photoUrls.size)
    }

    @Test
    fun `rejects duplicate ids before returning any import rows`() {
        val file = write("duplicate.csv", """
            反馈ID,产品ID,反馈标题,反馈内容,创建时间,更新时间
            legacy-1,p,t,c,2024-01-02 03:04:05,2024-01-02 03:04:05
            legacy-1,p,t2,c2,2024-01-02 03:04:05,2024-01-02 03:04:05
        """.trimIndent())

        val error = assertThrows(ClientException::class.java) { reader.readImport(file) }

        assertEquals("STATION_LEGACY_ID_DUPLICATED", error.reasonCode)
    }

    @Test
    fun `rejects oversized optional field instead of silently truncating it`() {
        val file = write("oversized.csv", """
            反馈ID,产品ID,反馈标题,反馈内容,创建时间,更新时间,uid
            legacy-1,p,t,c,2024-01-02 03:04:05,2024-01-02 03:04:05,${"a".repeat(101)}
        """.trimIndent())

        val error = assertThrows(ClientException::class.java) { reader.readImport(file) }

        assertEquals("STATION_LEGACY_FIELD_TOO_LONG", error.reasonCode)
    }

    private fun write(name: String, text: String): Path = temp.resolve(name).also { Files.writeString(it, text) }
}
