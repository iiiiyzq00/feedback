package com.feedback.service.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JiraDescriptionDimensionsTest {
    @Test fun `extracts labeled system version without taking third party software version`() {
        val text="【软件名称、软件版本、下载渠道】:课堂[(应用商店)2.18.1]\\n9、【系统版本】:seewoOS_XPV11_V3.3.1.4"
        assertEquals("V3.3.1.4",JiraDescriptionDimensions.extractSystemVersion(text))
    }

    @Test fun `does not treat arbitrary dotted number as system version`() {
        assertNull(JiraDescriptionDimensions.extractSystemVersion("软件版本 2.18.1，未提供系统版本标签"))
    }

    @Test fun `extracts only important controlled identities`() {
        val text="""
            用户ID：teacher-2048
            设备SN：E10X-9988-ABCD
            联系方式：13812345678 / owner@example.com
            普通工单号：123456789
        """.trimIndent()
        assertEquals(listOf("USER_ID" to "teacher-2048","DEVICE_SN" to "E10X-9988-ABCD",
            "PHONE" to "13812345678", "EMAIL" to "owner@example.com"),
            JiraDescriptionDimensions.extractControlledIdentities(text))
        assertEquals(emptyList<Pair<String,String>>(),JiraDescriptionDimensions.extractControlledIdentities("普通工单号：123456789"))
    }

    @Test fun `parses Jira timezone offsets for attachment backfill`() {
        assertEquals("2026-08-27T17:23:58",
            JiraDescriptionDimensions.parseJiraDateTime("2026-08-27T17:23:58.000+0800", "Asia/Shanghai").toString())
    }
}
