# Xposed 入口类：通过 xposed_init 字符串反射加载，R8 看不到引用，必须保留
-keep class com.example.supermi.xposed.** { *; }

# ContentProvider（manifest 声明，通常已被默认规则保留，这里再保险一层）
-keep class com.example.supermi.BubblePosProvider { *; }

# xposed api 是 compileOnly，运行时由框架提供，R8 阶段缺失，忽略告警
-dontwarn de.robv.android.xposed.**

# 保留注解与泛型签名，避免反射/序列化受影响
-keepattributes Signature
-keepattributes *Annotation*
