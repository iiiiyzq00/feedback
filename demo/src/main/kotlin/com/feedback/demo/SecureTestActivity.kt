package com.feedback.demo

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.feedback.sdk.FeedbackSDK
import com.feedback.sdk.PrefillData

/** XT11A 验收套件使用的受控 FLAG_SECURE 界面。 */
class SecureTestActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(32, 43, 68))
            addView(TextView(context).apply {
                text = "FLAG_SECURE 受保护测试页面"
                textSize = 28f
                setTextColor(Color.WHITE)
            })
            addView(Button(context).apply {
                text = "反馈受保护页面"
                setOnClickListener {
                    FeedbackSDK.launch(
                        this@SecureTestActivity,
                        PrefillData(
                            packageName = packageName,
                            appVersion = "1.1.0",
                            functionModule = "FLAG_SECURE_VALIDATION",
                            triggerType = "USER",
                            networkType = "WIFI"
                        )
                    )
                }
            })
        })
    }
}
