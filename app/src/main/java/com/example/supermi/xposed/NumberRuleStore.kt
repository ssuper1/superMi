package com.example.supermi.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

data class NumberRule(
    val prefixes: List<String>,
    val lengths: List<Int>,
    val apps: List<String>
)

object NumberRuleStore {

    const val KEY_NUMBER_RULES = "number_rules"

    private const val CAINIAO = "com.cainiao.wireless"
    private const val JD = "com.jingdong.app.mall"

    val DEFAULT_RULES = listOf(
        NumberRule(listOf("1"), listOf(11), emptyList()),
        NumberRule(listOf("YT"), listOf(15), listOf(CAINIAO)),
        NumberRule(listOf("EMS"), emptyList(), listOf(CAINIAO)),
        NumberRule(listOf("JD"), emptyList(), listOf(CAINIAO, JD))
    )

    private val PHONE_RE = Regex("""1[3-9]\d{9}""")

    fun parse(json: String?): List<NumberRule> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val prefixes = o.optString("prefix", "")
                    .split(',', '，')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val lenArr = o.optJSONArray("lengths")
                val lengths = if (lenArr != null) {
                    (0 until lenArr.length()).map { lenArr.getInt(it) }
                } else {
                    val min = o.optInt("min", 0)
                    val max = o.optInt("max", 0)
                    if (min > 0 && max >= min) (min..max).toList() else emptyList()
                }
                val appsArr = o.optJSONArray("apps")
                val apps = if (appsArr == null) {
                    emptyList()
                } else {
                    (0 until appsArr.length()).map { appsArr.getString(it) }
                }
                NumberRule(prefixes, lengths, apps)
            }
        } catch (t: Throwable) {
            emptyList()
        }
    }

    fun toJson(rules: List<NumberRule>): String {
        val arr = JSONArray()
        for (r in rules) {
            val o = JSONObject()
            o.put("prefix", r.prefixes.joinToString(","))
            o.put("lengths", JSONArray(r.lengths))
            o.put("apps", JSONArray(r.apps))
            arr.put(o)
        }
        return arr.toString()
    }

    private fun buildRegex(rule: NumberRule): Regex? {
        val parts = rule.prefixes.mapNotNull { p ->
            val base = Regex.escape(p)
            if (rule.lengths.isEmpty()) {
                "$base\\d+"
            } else {
                val alts = rule.lengths
                    .mapNotNull { len ->
                        val dc = len - p.length
                        if (dc >= 0) "\\d{$dc}" else null
                    }
                    .distinct()
                if (alts.isEmpty()) null else "$base(${alts.joinToString("|")})"
            }
        }
        if (parts.isEmpty()) return null
        return Regex("(?<![0-9A-Za-z])(${parts.joinToString("|")})(?![0-9])")
    }

    fun match(ctx: Context?, text: String): Pair<String, List<String>>? {
        val json = BubblePrefs.numberRulesJson(ctx)
        val rules = if (json.isNullOrBlank()) DEFAULT_RULES else parse(json)
        for (rule in rules) {
            val re = try {
                buildRegex(rule)
            } catch (t: Throwable) {
                XposedBridge.log("SuperMi: number regex err: $t")
                null
            } ?: continue
            val m = re.find(text) ?: continue
            val number = m.value
            if (rule.apps.isNotEmpty()) return number to rule.apps
            if (PHONE_RE.matches(number)) return number to emptyList()
        }
        return null
    }
}
