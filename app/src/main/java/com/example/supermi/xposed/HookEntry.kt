package com.example.supermi.xposed

import android.content.ClipData
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    @Volatile private var snapshotReceiverRegistered = false

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        XposedBridge.log("SuperMi: HookEntry v4 loaded")
        SystemContextHolder.init(lpparam.classLoader)
        registerSnapshotReceiver()
        registerSnapshotDeleteReceiver()
        registerSnapshotRestoreReceiver()
        Handler(Looper.getMainLooper()).postDelayed({ registerSnapshotReceiver() }, 3000L)
        Handler(Looper.getMainLooper()).postDelayed({ registerSnapshotDeleteReceiver() }, 3000L)
        Handler(Looper.getMainLooper()).postDelayed({ registerSnapshotRestoreReceiver() }, 3000L)
        try {
            val clazz = XposedHelpers.findClass(
                "com.android.server.clipboard.ClipboardService",
                lpparam.classLoader
            )
            XposedBridge.log("SuperMi: class=${clazz.name}")
            for (method in clazz.declaredMethods) {
                val name = method.name
                if (!name.startsWith("setPrimaryClip")) continue
                val params = method.parameterTypes
                if (params.isEmpty() || params[0] != ClipData::class.java) continue
                XposedBridge.log("SuperMi: hooking $method")
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            DebugToast.log("setPrimaryClip CALLED, args=${param.args.contentToString()}")
                            val clip = param.args.getOrNull(0) as? ClipData ?: return
                            val text = clip.getItemAt(0)?.text?.toString()?.trim()
                            if (text.isNullOrEmpty()) return
                            DebugToast.log("捕获剪贴板: $text")
                            DebugToast.systemContext()?.let { ctx ->
                                DebugToast.show(ctx, "SuperMi 捕获到: ${text.take(40)}")
                            }
                            OverlayBubble.show(text)
                        } catch (t: Throwable) {
                            XposedBridge.log(t)
                        }
                    }
                })
            }
            XposedBridge.log("SuperMi: ClipboardService hooks installed")
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: ClipboardService hook setup failed: $t")
        }
    }

    private fun registerSnapshotReceiver() {
        if (snapshotReceiverRegistered) return
        val ctx = SystemContextHolder.context() ?: return
        try {
            val filter = IntentFilter("com.example.supermi.SHOW_SNAPSHOT")
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val pending = goAsync()
                    try {
                        val uri = intent.getStringExtra("snapshot_uri")
                        if (uri == null) {
                            pending.finish()
                            return
                        }
                        val cachePath = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_CACHE_PATH)
                        val origPath = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_PATH)
                        val origName = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_NAME)
                        val takenMs = intent.getLongExtra(com.example.supermi.SnapshotStore.EXTRA_TAKEN_MS, 0L)
                            .takeIf { it > 0L }
                        XposedBridge.log("SuperMi: snapshot event received uri=$uri cachePath=$cachePath")
                        // 保持广播的临时 URI 授权，直到 OverlayBubble 完成读取；Android 16
                        // 会在 onReceive 返回后立即撤销异步线程尚未使用的 grant。
                        OverlayBubble.showSnapshot(
                            Uri.parse(uri),
                            origPath?.takeIf { it.isNotBlank() } ?: origName,
                            takenMs,
                            cachePath
                        ) {
                            try { pending.finish() } catch (_: Throwable) { }
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("SuperMi: snapshot receiver failed: $t")
                        try { pending.finish() } catch (_: Throwable) { }
                    }
                }
            }, filter, "com.example.supermi.permission.SHOW_SNAPSHOT", null, Context.RECEIVER_EXPORTED)
            snapshotReceiverRegistered = true
            XposedBridge.log("SuperMi: snapshot receiver registered")
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: snapshot receiver setup failed: $t")
        }
    }

    private fun registerSnapshotDeleteReceiver() {
        if (snapshotDeleteReceiverRegistered) return
        val ctx = SystemContextHolder.context() ?: return
        try {
            val filter = IntentFilter("com.example.supermi.DELETE_SNAPSHOT")
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try {
                        val uriStr = intent.getStringExtra("snapshot_uri")
                        val origPath = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_PATH)
                        val origUri = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_URI)?.let(Uri::parse)
                        val origTakenMs = intent
                            .getLongExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_TAKEN_MS, 0L)
                            .takeIf { it > 0L }
                        val origName = intent.getStringExtra(com.example.supermi.SnapshotStore.EXTRA_ORIG_NAME)
                        if (uriStr != null) {
                            // 正常删除：移除气泡 + 按开关删相册原图
                            OverlayBubble.deleteSnapshot(
                                Uri.parse(uriStr), origPath, origUri, origTakenMs, origName
                            )
                        } else if (origPath != null) {
                            // 仅删相册原图：不动气泡与 app 内缓存
                            OverlayBubble.deleteOriginalOnly(origPath, origUri, origTakenMs, origName)
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("SuperMi: snapshot delete receiver failed: $t")
                    }
                }
            }, filter, "com.example.supermi.permission.SHOW_SNAPSHOT", null, Context.RECEIVER_EXPORTED)
            snapshotDeleteReceiverRegistered = true
            XposedBridge.log("SuperMi: snapshot delete receiver registered")
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: snapshot delete receiver setup failed: $t")
        }
    }

    private fun registerSnapshotRestoreReceiver() {
        if (snapshotRestoreReceiverRegistered) return
        val ctx = SystemContextHolder.context() ?: return
        try {
            val filter = IntentFilter().apply {
                addAction("com.example.supermi.RESTORE_SNAPSHOT")
                addAction("com.example.supermi.SHOW_SNAPSHOT_FOR_SOURCE")
            }
            ctx.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == "com.example.supermi.SHOW_SNAPSHOT_FOR_SOURCE") {
                        OverlayBubble.showSnapshotBubbleWhileViewerOpen()
                    } else {
                        OverlayBubble.restoreSnapshotBubble()
                    }
                }
            }, filter, "com.example.supermi.permission.SHOW_SNAPSHOT", null, Context.RECEIVER_EXPORTED)
            snapshotRestoreReceiverRegistered = true
            XposedBridge.log("SuperMi: snapshot restore receiver registered")
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: snapshot restore receiver setup failed: $t")
        }
    }

    @Volatile private var snapshotDeleteReceiverRegistered = false
    @Volatile private var snapshotRestoreReceiverRegistered = false
}
