package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.feedback.config.FeishuSsoProperties
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class OfficialFeishuOrganizationClientTest {
    private lateinit var server:MockWebServer
    private lateinit var client:OfficialFeishuOrganizationClient

    @BeforeEach fun setup(){
        server=MockWebServer();server.start()
        client=OfficialFeishuOrganizationClient(FeishuSsoProperties(
            enabled=true,appId="cli_mock",appSecret="mock-secret",organizationSyncEnabled=true,
            redirectUri="http://localhost/api/v1/auth/feishu/callback",publicBaseUrl="http://localhost",
            allowHttpForLoopback=true,organizationApiBaseUrl=server.url("/").toString().trimEnd('/')
        ),jacksonObjectMapper())
    }
    @AfterEach fun close(){server.shutdown()}

    @Test fun `reads groups departments and active employment using tenant identity`() {
        enqueue("""{"code":0,"tenant_access_token":"tenant-mock"}""")
        enqueue("""{"code":0,"data":{"user":{"department_ids":["dept-sdk"],"status":{"is_activated":true,"is_resigned":false}}}}""")
        enqueue("""{"code":0,"data":{"group_list":[{"group_id":"group-admin"},{"group_id":"group-ops"}]}}""")

        val facts=client.facts("ou_mock")

        assertEquals(setOf("group-admin","group-ops"),facts.groupIds)
        assertEquals(setOf("dept-sdk"),facts.departmentIds)
        assertTrue(facts.active)
        assertEquals("/open-apis/auth/v3/tenant_access_token/internal",server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/open-apis/contact/v3/users/ou_mock?"))
        assertTrue(server.takeRequest().path!!.startsWith("/open-apis/contact/v3/group/member_belong?"))
    }

    @Test fun `marks resigned mock user inactive`() {
        enqueue("""{"code":0,"tenant_access_token":"tenant-mock"}""")
        enqueue("""{"code":0,"data":{"user":{"department_ids":[],"status":{"is_activated":false,"is_resigned":true}}}}""")
        enqueue("""{"code":0,"data":{"group_list":[]}}""")
        assertFalse(client.facts("ou_resigned").active)
    }

    private fun enqueue(body:String)=server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type","application/json").setBody(body))
}
