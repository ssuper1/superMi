package com.example.supermi.xposed

import android.content.ClipData
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "android") return
        XposedBridge.log("SuperMi: HookEntry v4 loaded")
        SystemContextHolder.init(lpparam.classLoader)
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
}
