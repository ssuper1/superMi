package com.example.supermi.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

data class PlatformRule(
    val keyword: String,
    val links: List<String>,
    val app: String,
    val apps: List<String> = emptyList(),
    val deepLink: Boolean = true
)

data class PlatformMatch(val app: String, val apps: List<String>, val deepLink: Boolean = true)

object PlatformRuleStore {

    const val KEY_PLATFORM_RULES = "platform_rules"

    @Volatile
    private var cachedJson: String? = null

    @Volatile
    private var cachedRules: List<PlatformRule>? = null

    val DEFAULT_RULES = listOf(
        PlatformRule("【淘宝】", listOf("e.tb.cn", "taobao.com", "tmall.com"), "com.taobao.taobao"),
        PlatformRule("【闲鱼】", listOf("m.tb.cn", "goofish.com"), "com.taobao.idlefish", deepLink = false),
        PlatformRule("【京东】", listOf("3.cn", "jd.com", "jd.hk"), "com.jingdong.app.mall", deepLink = false),
        PlatformRule("【拼多多】", listOf("yangkeduo.com", "pinduoduo.com"), "com.xunmeng.pinduoduo"),
        PlatformRule("【小红书】", listOf("xhslink.com", "xhslink.cn"), "com.xingin.xhs", deepLink = false),
        PlatformRule("", listOf("douyin.com"), "com.ss.android.ugc.aweme"),
        PlatformRule("", listOf("b23.tv"), "tv.danmaku.bili"),
        PlatformRule("「百度网盘APP 即可获取」", listOf("pan.baidu.com"), "com.baidu.netdisk", deepLink = false),
        PlatformRule("「中国移动云盘APP」", listOf("yun.139.com"), "com.chinamobile.mcloud", deepLink = false),
        PlatformRule("", listOf("www.coolapk.com"), "com.coolapk.market")
    )

    fun parse(json: String?): List<PlatformRule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val linksArr = o.optJSONArray("links")
                val links = if (linksArr == null) {
                    emptyList()
                } else {
                    (0 until linksArr.length()).map { linksArr.getString(it) }
                }
                val appsArr = o.optJSONArray("apps")
                val apps = if (appsArr == null) {
                    emptyList()
                } else {
                    (0 until appsArr.length()).map { appsArr.getString(it) }
                }
                PlatformRule(
                    o.optString("kw", ""),
                    links,
                    o.optString("app", ""),
                    apps,
                    o.optBoolean("deepLink", true)
                )
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun toJson(rules: List<PlatformRule>): String {
        val arr = JSONArray()
        for (r in rules) {
            val o = JSONObject()
            o.put("kw", r.keyword)
            o.put("links", JSONArray(r.links))
            o.put("app", r.app)
            o.put("apps", JSONArray(r.apps))
            o.put("deepLink", r.deepLink)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun rules(ctx: Context?): List<PlatformRule> {
        val json = BubblePrefs.platformRulesJson(ctx)
        if (cachedJson == json && cachedRules != null) return cachedRules!!
        val r = if (json.isNullOrBlank()) DEFAULT_RULES else parse(json)
        cachedJson = json
        cachedRules = r
        return r
    }

    private fun keywordMatches(rule: PlatformRule, text: String): Boolean {
        val kw = rule.keyword
        if (kw.isEmpty()) return false
        if (kw.startsWith("【") && kw.endsWith("】") && kw.length >= 3) {
            val inner = Regex.escape(kw.substring(1, kw.length - 1))
            return Regex("【$inner[^】]*】").containsMatchIn(text)
        }
        if (kw.startsWith("「") && kw.endsWith("」") && kw.length >= 3) {
            val inner = Regex.escape(kw.substring(1, kw.length - 1))
            return Regex("「$inner[^」]*」").containsMatchIn(text)
        }
        return text.contains(kw)
    }

    fun match(ctx: Context?, text: String): PlatformMatch? {
        for (r in rules(ctx)) {
            if (keywordMatches(r, text)) {
                XposedBridge.log("SuperMi: PLAT keyword matched -> ${r.app} kw='${r.keyword}'")
                return PlatformMatch(r.app, r.apps, r.deepLink)
            }
        }
        for (r in rules(ctx)) {
            for (l in r.links) {
                if (l.isNotEmpty() && text.contains(l)) {
                    XposedBridge.log("SuperMi: PLAT link matched -> ${r.app} link='$l' text='$text'")
                    return PlatformMatch(r.app, r.apps, r.deepLink)
                }
            }
        }
        return null
    }
}
