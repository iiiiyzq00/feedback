package com.feedback.sdk.diagnostics

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

data class DiagnosticItem(val name: String, val ok: Boolean, val detail: String)

/** 面向 ROM/MDM/网络/电源约束的只读预检。 */
object CompatibilityDiagnostics {
    suspend fun run(context: Context, healthUrl: String): List<DiagnosticItem> = withContext(Dispatchers.IO) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val validated = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val power = context.getSystemService(PowerManager::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val endpoint = runCatching {
            (URL(healthUrl).openConnection() as HttpURLConnection).run {
                connectTimeout = 5_000
                readTimeout = 5_000
                requestMethod = "GET"
                responseCode.also { disconnect() }
            }
        }.getOrNull()
        listOf(
            DiagnosticItem("ROM", true, "${Build.MANUFACTURER}/${Build.MODEL} Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}"),
            DiagnosticItem("MDM", true, if (!dpm.activeAdmins.isNullOrEmpty()) "检测到设备管理组件，需核对 MediaProjection 策略" else "未检测到本应用可见的设备管理组件"),
            DiagnosticItem("网络能力", internet && validated, "internet=$internet validated=$validated"),
            DiagnosticItem("反馈端点", endpoint in 200..299, "HTTP ${endpoint ?: "连接失败"}"),
            DiagnosticItem("省电白名单", power.isIgnoringBatteryOptimizations(context.packageName),
                if (power.isIgnoringBatteryOptimizations(context.packageName)) "不受电池优化限制" else "附件后台重试可能被延迟")
        )
    }
}
