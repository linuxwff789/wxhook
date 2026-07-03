package com.nous.wxhook.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra("key") ?: return
        val keyLen = intent.getIntExtra("keyLen", 0)

        // Save key to shared preferences
        val prefs = context.getSharedPreferences("wxhook", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("last_key", key)
            .putInt("last_key_len", keyLen)
            .putLong("last_key_time", System.currentTimeMillis())
            .apply()

        android.util.Log.d("wxhook", "Key captured: $key (len=$keyLen)")
    }
}
