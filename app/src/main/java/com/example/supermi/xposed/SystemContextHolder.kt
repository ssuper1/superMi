package com.example.supermi.xposed

import android.content.Context
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

object SystemContextHolder {

    @Volatile
    private var systemContext: Context? = null

    fun init(classLoader: ClassLoader) {
        try {
            val atClass = XposedHelpers.findClass("android.app.ActivityThread", classLoader)
            XposedBridge.hookMethod(atClass.getMethod("systemMain"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.result?.let {
                        XposedHelpers.callMethod(it, "getSystemContext") as? Context
                    }
                    storeCtx(ctx)
                }
            })
            XposedBridge.hookMethod(atClass.getMethod("getSystemContext"), object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    storeCtx(param.result as? Context)
                }
            })
            (XposedHelpers.callStaticMethod(atClass, "currentActivityThread") as? Any)?.let { at ->
                val ctx = XposedHelpers.callMethod(at, "getSystemContext") as? Context
                storeCtx(ctx)
            }
            XposedBridge.log("SuperMi: SystemContextHolder init done, ctx=${systemContext != null}")
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: SystemContextHolder init failed: $t")
        }
    }

    private fun storeCtx(ctx: Context?) {
        if (ctx != null && systemContext == null) {
            systemContext = ctx
        }
    }

    fun context(): Context? {
        systemContext?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getMethod("currentActivityThread").invoke(null)
            at?.javaClass?.getMethod("getSystemContext")?.invoke(at) as? Context
        } catch (t: Throwable) {
            XposedBridge.log("SuperMi: context() fallback failed: $t")
            null
        }
    }
}
