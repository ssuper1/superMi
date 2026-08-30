package com.example.supermi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** system_server 定时关闭到期后通知 app 清理本次暂存截图（缓存 + 按开关删相册原图）。 */
class SnapshotCleanReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            SnapshotStore.deleteAll(context.applicationContext)
        } catch (_: Throwable) {
        }
    }
}
