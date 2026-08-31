package com.example.supermi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

/** 接收 system_server 的截图气泡长按删除请求，复用 App 侧完整删除链路。 */
class SnapshotDeleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val value = intent.getStringExtra("snapshot_uri") ?: return
        try {
            SnapshotStore.delete(context.applicationContext, Uri.parse(value))
        } catch (_: Throwable) {
        }
    }
}
