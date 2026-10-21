package com.feedback.service.auth

import com.feedback.model.auth.*
import com.feedback.common.exception.AuthenticationSnapshotChangedException

import com.feedback.repository.remote.auth.*

import com.feedback.common.ErrorCode
import com.feedback.common.exception.RemoteException
import com.feedback.common.exception.ServiceException
import com.feedback.config.FeishuSsoProperties
import com.feedback.model.result.auth.FeishuIdentity
import com.feedback.repository.auth.FeishuPendingState
import com.feedback.repository.auth.FeishuSsoRepository
import com.feedback.repository.remote.auth.FeishuOAuthGateway
import com.feedback.security.WebUserDetailsService
import com.feedback.service.impl.auth.FeishuSsoServiceImpl
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 覆盖飞书授权、回调换票、同源重定向和远程超时降级的业务编排契约。 */
class FeishuSsoServiceTest {
    private val store = mockk<FeishuSsoRepository>()
    private val client = mockk<FeishuOAuthGateway>()
    private val users = mockk<WebUserDetailsService>()

    @Test
    fun `authorize stores one-time state and requests only login scopes`() {
        val returnPath = slot<String>()
        val redirectUri = slot<String>()
        every { store.createState(any(), capture(returnPath), capture(redirectUri), any()) } just runs

        val result = service(enabledProperties()).authorize("/monitor/overview", "http://172.20.210.59:8088")

        assertTrue(result.url.startsWith("https://accounts.feishu.cn/open-apis/authen/v1/authorize?"))
        assertTrue(result.url.contains("client_id=cli_test"))
        assertTrue(result.url.contains("offline_access"))
        assertEquals("/monitor/overview", returnPath.captured)
        assertEquals("http://172.20.210.59:8088/api/v1/auth/feishu/callback", redirectUri.captured)
    }

    @Test
    fun `authorize rejects use before SSO configuration is complete`() {
        assertThrows(ServiceException::class.java) { service(FeishuSsoProperties()).authorize("/") }
    }

    @Test
    fun `callback completes identity binding through store and returns same-origin login redirect`() {
        every { store.findPendingState(any()) } returns FeishuPendingState(
            "/monitor/overview", "http://172.20.210.59:8088/api/v1/auth/feishu/callback"
        )
        every { client.exchange(any(), any()) } returns FeishuIdentity(
            "ou_test", "on_test", "employee-test", "tenant-test", "测试用户", null
        )
        every { store.completeCallback(any(), any(), any(), any()) } just runs

        val result = service(enabledProperties()).callback("authorization-code", "valid-state")

        assertTrue(result.redirectUrl.startsWith("http://172.20.210.59:8088/login?feishu_code="))
        verify(exactly = 1) { store.completeCallback(any(), match { it.openId == "ou_test" }, any(), any()) }
    }

    @Test
    fun `provider timeout returns a same-origin retry redirect without completing callback`() {
        every { store.findPendingState(any()) } returns FeishuPendingState(
            "/", "http://192.168.1.129:8088/api/v1/auth/feishu/callback"
        )
        every { client.exchange(any(), any()) } throws RemoteException(
            ErrorCode.REMOTE_TIMEOUT, "FEISHU_REMOTE_TIMEOUT"
        )

        val result = service(enabledProperties()).callback("authorization-code", "valid-state")

        assertEquals("http://192.168.1.129:8088/login?feishu_error=provider_timeout", result.redirectUrl)
        verify(exactly = 0) { store.completeCallback(any(), any(), any(), any()) }
    }

    private fun service(properties: FeishuSsoProperties) = FeishuSsoServiceImpl(
        properties, client, store, users
    )

    private fun enabledProperties() = FeishuSsoProperties(
        enabled = true,
        appId = "cli_test",
        appSecret = "secret",
        redirectUri = "http://192.168.3.140:8088/api/v1/auth/feishu/callback",
        publicBaseUrl = "http://192.168.3.140:8088",
        bootstrapFirstUserAdmin = true
    )
}
