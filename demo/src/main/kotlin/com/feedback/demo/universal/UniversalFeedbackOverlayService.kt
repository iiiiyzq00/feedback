package com.feedback.demo.universal

import android.app.PendingIntent
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class UniversalFeedbackOverlayService : Service() {
    private lateinit var windowManager: WindowManager
    private var overlay: TextView? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var downWindowX = 0
    private var downWindowY = 0
    private var dragged = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val restoreIfLaunchStalled = Runnable { overlay?.visibility = android.view.View.VISIBLE }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startAsForegroundService()
    }

    private fun startAsForegroundService() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "通用反馈SDK入口",
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = "在所有学习应用上提供统一问题反馈入口" }
            )
        }
        val openHost = PendingIntent.getActivity(
            this,
            40,
            Intent(this, UniversalTriggerActivity::class.java).apply {
                putExtra(UniversalTriggerActivity.EXTRA_TRIGGER_TYPE, "NOTIFICATION")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("通用反馈SDK入口已开启")
                .setContentText("点击屏幕右侧“反馈”可反馈当前应用")
                .setContentIntent(openHost)
                .setOngoing(true)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (overlay == null) showOverlay()
        when (intent?.action) {
            ACTION_HIDE -> {
                mainHandler.removeCallbacks(restoreIfLaunchStalled)
                overlay?.visibility = android.view.View.INVISIBLE
            }
            ACTION_SHOW -> {
                mainHandler.removeCallbacks(restoreIfLaunchStalled)
                overlay?.visibility = android.view.View.VISIBLE
            }
            else -> overlay?.visibility = android.view.View.VISIBLE
        }
        return START_STICKY
    }

    private fun showOverlay() {
        val button = TextView(this).apply {
            text = "反馈"
            textSize = 13f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = false
            letterSpacing = 0.04f
            setTextColor(Color.rgb(25, 103, 163))
            elevation = dp(5).toFloat()
            alpha = 0.97f
            background = GradientDrawable().apply {
                colors = intArrayOf(Color.rgb(238, 248, 255), Color.rgb(203, 233, 253))
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.rgb(146, 205, 242))
            }
        }
        val displayWidth = resources.displayMetrics.widthPixels
        val displayHeight = resources.displayMetrics.heightPixels
        val overlayWidth = dp(80)
        val overlayHeight = dp(40)
        val preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedSide = preferences.getString(PREF_SIDE, SIDE_RIGHT) ?: SIDE_RIGHT
        val savedY = preferences.getInt(PREF_Y, (displayHeight - overlayHeight) / 2)
        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedSide == SIDE_LEFT) 0 else displayWidth - overlayWidth
            y = savedY.coerceIn(0, (displayHeight - overlayHeight).coerceAtLeast(0))
        }
        applyHalfHidden(button, savedSide)
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = params.x
                    downWindowY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragged && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragged = true
                        button.translationX = 0f
                        button.gravity = Gravity.CENTER
                        button.setPadding(0, 0, 0, 0)
                    }
                    if (dragged) {
                        params.x = (downWindowX + dx.toInt()).coerceIn(0, (displayWidth - overlayWidth).coerceAtLeast(0))
                        params.y = (downWindowY + dy.toInt()).coerceIn(0, (displayHeight - overlayHeight).coerceAtLeast(0))
                        runCatching { windowManager.updateViewLayout(button, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (dragged) {
                        val side = if (params.x + overlayWidth / 2 < displayWidth / 2) SIDE_LEFT else SIDE_RIGHT
                        params.x = if (side == SIDE_LEFT) 0 else displayWidth - overlayWidth
                        runCatching { windowManager.updateViewLayout(button, params) }
                        applyHalfHidden(button, side)
                        preferences.edit().putString(PREF_SIDE, side).putInt(PREF_Y, params.y).apply()
                    } else {
                        val accepted = UniversalFeedbackLauncher.trigger(
                            this@UniversalFeedbackOverlayService,
                            "FLOAT_BUTTON",
                            beforeCapture = {
                                button.visibility = android.view.View.INVISIBLE
                                mainHandler.removeCallbacks(restoreIfLaunchStalled)
                                mainHandler.postDelayed(restoreIfLaunchStalled, LAUNCH_WATCHDOG_MS)
                            },
                            onLaunchFailure = {
                                mainHandler.removeCallbacks(restoreIfLaunchStalled)
                                button.visibility = android.view.View.VISIBLE
                            }
                        )
                        if (!accepted) {
                            button.visibility = android.view.View.VISIBLE
                            Toast.makeText(
                                this@UniversalFeedbackOverlayService,
                                "暂时无法识别当前应用，请稍后重试",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    val side = if (params.x + overlayWidth / 2 < displayWidth / 2) SIDE_LEFT else SIDE_RIGHT
                    params.x = if (side == SIDE_LEFT) 0 else displayWidth - overlayWidth
                    runCatching { windowManager.updateViewLayout(button, params) }
                    applyHalfHidden(button, side)
                    true
                }
                else -> false
            }
        }
        overlay = button
        overlayParams = params
        windowManager.addView(button, params)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(restoreIfLaunchStalled)
        overlay?.let { windowManager.removeView(it) }
        overlay = null
        overlayParams = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private val touchSlop: Int
        get() = ViewConfiguration.get(this).scaledTouchSlop

    private fun applyHalfHidden(button: TextView, side: String) {
        val horizontalPadding = dp(9)
        if (side == SIDE_LEFT) {
            // 靠左停靠时，可见区域是胶囊的右半部分。
            button.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            button.setPadding(0, 0, horizontalPadding, 0)
            button.translationX = -dp(40).toFloat()
        } else {
            // 靠右停靠时，可见区域是胶囊的左半部分。
            button.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            button.setPadding(horizontalPadding, 0, 0, 0)
            button.translationX = dp(40).toFloat()
        }
    }

    companion object {
        const val ACTION_HIDE = "com.feedback.universal.overlay.HIDE"
        const val ACTION_SHOW = "com.feedback.universal.overlay.SHOW"
        private const val CHANNEL_ID = "universal_feedback_entry"
        private const val NOTIFICATION_ID = 18463
        private const val PREFS_NAME = "universal_feedback_overlay"
        private const val PREF_SIDE = "side"
        private const val PREF_Y = "y"
        private const val SIDE_LEFT = "LEFT"
        private const val SIDE_RIGHT = "RIGHT"
        private const val LAUNCH_WATCHDOG_MS = 8_000L
    }
}
