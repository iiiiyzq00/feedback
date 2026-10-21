package com.feedback.service.jira

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

class JiraIssueMapperTest {
    private val json = jacksonObjectMapper()
    private val mapper = JiraIssueMapper()

    @Test
    fun `maps Jira people as readable display names without exposing account ids`() {
        val fields = json.readTree("""{
          "summary":"课程页闪退",
          "description":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"点击课程后闪退"}]}]},
          "issuetype":{"name":"Bug"},
          "status":{"name":"Open"},"priority":{"name":"High"},
          "created":"2026-08-14T09:00:00.000+08:00","updated":"2026-08-14T10:00:00.000+08:00",
          "assignee":{"accountId":"real-account-id","displayName":"张三"},
          "reporter":{"accountId":"reporter-account-id","displayName":"报告人姓名"},
          "creator":{"accountId":"creator-account-id","displayName":"创建人姓名"},
          "project":{"key":"APP","name":"学习应用","lead":{"accountId":"lead-account-id","displayName":"项目负责人姓名"}},
          "components":[{"name":"旧模块"}],"customfield_10001":{"value":"课程"},"labels":["android","crash"],
          "comment":{"comments":[{"body":{"type":"doc","content":[{"type":"paragraph","content":[{"type":"text","text":"可以稳定复现"}]}]}}]}
        }""")
        val contract = JiraFieldMappingContract(1, "v2.0.0", mapOf(
            "source_record_id" to "问题关键字", "description" to "描述", "source_update_time" to "更新",
            "function_module" to "模块", "comment_summary" to "评论摘要", "assignee" to "经办人", "reporter" to "报告人",
            "creator" to "创建人", "project_lead" to "项目负责人"
        ))
        val result = mapper.toMappedSourceRow(JiraApiIssue("10001", "APP-1", fields), contract)
        assertEquals("APP-1", result["问题关键字"])
        assertEquals("点击课程后闪退", result["描述"])
        assertEquals("旧模块", result["模块"])
        assertEquals("可以稳定复现", result["评论摘要"])
        assertEquals("张三",result["经办人"])
        assertEquals("报告人姓名",result["报告人"])
        assertEquals("创建人姓名",result["创建人"])
        assertEquals("项目负责人姓名",result["项目负责人"])
        assert(result.values.none { it.contains("account-id") })
    }

    @Test
    fun `jira offset time represents the configured business-zone wall clock`() {
        val raw = "2026-08-18T09:25:55.000+08:00"
        val local = OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDateTime()

        assertEquals(LocalDateTime.parse("2026-08-18T09:25:55"), local)
    }

    @Test
    fun `controlled custom field is requested and mapped only through explicit contract`() {
        val fields = json.readTree("""{
          "summary":"safe title",
          "customfield_12345":{"value":"com.example.audited"}
        }""")
        val contract = JiraFieldMappingContract(
            2,
            "v3",
            mapOf("source_record_id" to "key", "package_name" to "application package"),
            controlledCustomFieldIds = mapOf("package_name" to "customfield_12345")
        )

        val result = mapper.toMappedSourceRow(JiraApiIssue("1", "SAFE-1", fields), contract)

        assertEquals(listOf("customfield_12345"), contract.requestedFields)
        assertEquals("com.example.audited", result["application package"])
    }

    @Test
    fun `custom field path and target fail closed unless explicitly allowed`() {
        assertThrows<IllegalArgumentException> {
            JiraFieldMappingContract(2, "v3", mapOf("package_name" to "application package"),
                controlledCustomFieldIds = mapOf("package_name" to "project.key"))
        }
        assertThrows<IllegalArgumentException> {
            JiraFieldMappingContract(2, "v3", mapOf("project_key" to "project"),
                controlledCustomFieldIds = mapOf("unmapped" to "customfield_12345"))
        }
    }
}
