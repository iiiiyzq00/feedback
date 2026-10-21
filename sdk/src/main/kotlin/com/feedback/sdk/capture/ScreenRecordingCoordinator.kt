package com.feedback.sdk.capture

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Intent
import java.lang.ref.WeakReference

/** 一次进程内的录制会话。授权 Activity 与前台服务都向它汇报。 */
internal object ScreenRecordingCoordinator {
    private var onStarted: (() -> Unit)? = null
    private var onCompleted: ((ByteArray) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    @Volatile private var active = false
    private var hostActivityRef: WeakReference<Activity>? = null
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    @Synchronized
    fun request(
        activity: Activity,
        started: () -> Unit,
        completed: (ByteArray) -> Unit,
        error: (String) -> Unit
    ): Boolean {
        if (active) return false
        active = true
        hostActivityRef = WeakReference(activity)
        onStarted = started
        onCompleted = completed
        onError = error
        activity.startActivity(Intent(activity, ScreenRecordingPermissionActivity::class.java))
        return true
    }

    @Synchronized
    fun started() {
        onStarted?.invoke()
        val host = hostActivityRef?.get() ?: return
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(activity: Activity) {
                // 宿主 Activity 一旦退到后台就停止。这样全屏
                // MediaProjection 的作用范围就被限制在被反馈的应用上。
                if (activity === host && active) {
                    host.applicationContext.startService(
                        Intent(host, ScreenRecordingService::class.java).apply {
                            action = ScreenRecordingService.ACTION_STOP
                        }
                    )
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
            override fun onActivityStarted(a: Activity) = Unit
            override fun onActivityResumed(a: Activity) = Unit
            override fun onActivityStopped(a: Activity) = Unit
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
            override fun onActivityDestroyed(a: Activity) = Unit
        }
        lifecycleCallbacks = callbacks
        host.application.registerActivityLifecycleCallbacks(callbacks)
    }

    @Synchronized
    fun completed(bytes: ByteArray) {
        val callback = onCompleted
        clear()
        callback?.invoke(bytes)
    }

    @Synchronized
    fun failed(message: String) {
        val callback = onError
        clear()
        callback?.invoke(message)
    }

    private fun clear() {
        val host = hostActivityRef?.get()
        val callbacks = lifecycleCallbacks
        if (host != null && callbacks != null) {
            host.application.unregisterActivityLifecycleCallbacks(callbacks)
        }
        hostActivityRef?.clear()
        hostActivityRef = null
        lifecycleCallbacks = null
        active = false
        onStarted = null
        onCompleted = null
        onError = null
    }
}
