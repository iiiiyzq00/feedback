package com.feedback.controller.auth

import com.feedback.security.LoginProtection
import com.feedback.security.WebSessionCookieService
import com.feedback.model.auth.FeishuCallbackResult
import com.feedback.service.auth.AuthRegistrationService
import com.feedback.service.auth.FeishuSsoService
import com.feedback.service.auth.AuthSessionLifecycleService
import com.feedback.service.auth.LocalAuthenticationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/** 冻结飞书上游失败时的安全重定向行为，避免内部异常或一次性凭证进入响应正文。 */
class AuthControllerFeishuCallbackTest {
    private val feishu = mockk<FeishuSsoService>()
    private val controller = AuthController(
        localAuthentication = mockk<LocalAuthenticationService>(),
        registrations = mockk<AuthRegistrationService>(),
        sessions = mockk<AuthSessionLifecycleService>(),
        loginProtection = mockk<LoginProtection>(),
        feishu = feishu,
        sessionCookies = mockk<WebSessionCookieService>()
    )
    private val mvc = MockMvcBuilders.standaloneSetup(controller).build()

    @Test
    fun `provider timeout redirects to retryable login page instead of returning json 500`() {
        every { feishu.callback("authorization-code", "valid-state") } returns FeishuCallbackResult(
            "http://192.168.1.129:8088/login?feishu_error=provider_timeout"
        )

        mvc.perform(
            get("/api/v1/auth/feishu/callback")
                .param("code", "authorization-code")
                .param("state", "valid-state")
        )
            .andExpect(status().isFound)
            .andExpect(header().string(
                "Location",
                "http://192.168.1.129:8088/login?feishu_error=provider_timeout"
            ))
    }
}
