package com.nous.wxhook.ui.merge

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

class MergeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        layout.addView(TextView(this).apply { text = "数据合并"; textSize = 20f })
        layout.addView(TextView(this).apply {
            text = """
                功能规划:
                - 消息去重 (msgSvrId)
                - 合并策略: 并集/最新优先/基础优先/交集
                - 多快照合并
                - 冲突处理

                待实现...
            """.trimIndent(); textSize = 14f; setPadding(0, 32, 0, 0)
        })
        setContentView(layout)
    }
}
