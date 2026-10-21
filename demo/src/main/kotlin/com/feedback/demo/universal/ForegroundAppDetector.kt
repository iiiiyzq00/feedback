package com.feedback.demo.universal

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

class ForegroundAppDetector(private val context: Context) {
    fun detect(
        excludedPackages: Set<String> = setOf(context.packageName),
        triggerType: String = "FLOAT_BUTTON"
    ): UniversalFeedbackContext? {
        val now = System.currentTimeMillis()
        val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val events = usage.queryEvents(now - LOOKBACK_MS, now)
        val event = UsageEvents.Event()
        var packageName: String? = null
        var activityName: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.packageName !in excludedPackages &&
                event.packageName !in IGNORED_PACKAGES
            ) {
                packageName = event.packageName
                if (Build.VERSION.SDK_INT >= 29) activityName = event.className
            }
        }
        val sourcePackage = packageName ?: return null
        return contextForPackage(
            sourcePackage = sourcePackage,
            sourceActivity = activityName,
            triggerType = triggerType,
            detectionMethod = "USAGE_EVENTS",
            trustedSource = true,
            detectedAt = now
        )
    }

    fun untrustedHint(sourcePackage: String?): UniversalFeedbackContext? {
        val safePackage = sourcePackage?.takeIf(PACKAGE_PATTERN::matches) ?: return null
        return contextForPackage(
            sourcePackage = safePackage,
            sourceActivity = null,
            triggerType = "DEEPLINK",
            detectionMethod = "CALLER_HINT",
            trustedSource = false,
            detectedAt = System.currentTimeMillis()
        )
    }

    fun selectedApp(sourcePackage: String): UniversalFeedbackContext = contextForPackage(
        sourcePackage = sourcePackage,
        sourceActivity = null,
        triggerType = "LAUNCHER",
        detectionMethod = "USER_SELECTION",
        trustedSource = true,
        detectedAt = System.currentTimeMillis()
    )

    private fun contextForPackage(
        sourcePackage: String,
        sourceActivity: String?,
        triggerType: String,
        detectionMethod: String,
        trustedSource: Boolean,
        detectedAt: Long
    ): UniversalFeedbackContext {
        val packageInfo = runCatching { context.packageManager.getPackageInfo(sourcePackage, 0) }.getOrNull()
        val appInfo = packageInfo?.applicationInfo
        return UniversalFeedbackContext(
            sourcePackage = sourcePackage,
            sourceAppName = appInfo?.let { context.packageManager.getApplicationLabel(it).toString() },
            sourceVersionName = packageInfo?.versionName,
            sourceVersionCode = packageInfo?.let {
                if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong()
            },
            sourceActivity = sourceActivity,
            triggerType = triggerType,
            detectedAt = detectedAt,
            detectionMethod = detectionMethod,
            trustedSource = trustedSource
        )
    }

    companion object {
        private const val LOOKBACK_MS = 60_000L
        private val PACKAGE_PATTERN = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        private val IGNORED_PACKAGES = setOf("com.android.systemui", "com.android.launcher3")

        fun hasUsageAccess(context: Context): Boolean {
            val usage = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            return usage.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000, now)
                .orEmpty().isNotEmpty()
        }
    }
}
