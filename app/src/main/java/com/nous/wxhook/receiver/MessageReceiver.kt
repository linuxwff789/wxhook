package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val talker = intent.getStringExtra("talker") ?: return
        val content = intent.getStringExtra("content") ?: ""
        val type = intent.getIntExtra("type", 0)
        val createTime = intent.getLongExtra("createTime", 0)
        val isSend = intent.getBooleanExtra("isSend", false)
        val msgSvrId = intent.getLongExtra("msgSvrId", 0)

        // TODO: save to Room database
        android.util.Log.d("wxhook", "Message from $talker: ${content.take(50)} (type=$type)")
    }
}
