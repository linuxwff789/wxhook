package com.nous.wxhook.ui.settings

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "设置"; textSize = 20f })
        layout.addView(TextView(this).apply {
            text = """
                功能规划:
                - 密钥管理
                - 备份频率设置
                - 存储路径配置
                - 防撤回开关
                - 导出格式选择

                待实现...
            """.trimIndent(); textSize = 14f; setPadding(0, 32, 0, 0)
        })
        setContentView(layout)
    }
}
