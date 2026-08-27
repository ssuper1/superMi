package com.example.supermi.xposed

import android.content.Context

object ContentClassifier {

    enum class ContentType { URL, ADDRESS, PHONE, PLATFORM }

    data class Recognized(
        val type: ContentType,
        val query: String,
        val customApps: List<String> = emptyList(),
        val deepLink: Boolean = true
    )

    private val URL_FIND =
        Regex("""https?://[^\s"'<>]+|www\.[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private val PHONE_FIND = Regex("""(?<![0-9A-Za-z])1[3-9]\d{9}(?![0-9A-Za-z])""")

    fun classifyAll(ctx: Context?, text: String): List<Recognized> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        if (t.length > BubblePrefs.maxLen(ctx)) return emptyList()
        val result = mutableListOf<Recognized>()

        val urlMatch = URL_FIND.find(t)
        val platform = PlatformRuleStore.match(ctx, t)
        val platformApps = when {
            platform == null -> emptyList()
            platform.apps.isNotEmpty() -> platform.apps
            else -> listOfNotNull(platform.app)
        }

        if (urlMatch != null) {
            result.add(
                Recognized(
                    ContentType.URL,
                    urlMatch.value,
                    platformApps,
                    platform?.deepLink ?: true
                )
            )
        } else if (platform != null) {
            result.add(Recognized(ContentType.PLATFORM, t, platformApps))
        }

        val num = NumberRuleStore.match(ctx, t)
        if (num != null) {
            result.add(Recognized(ContentType.PHONE, num.first, num.second))
        } else if (NumberRuleStore.hasPhoneRule(ctx)) {
            PHONE_FIND.find(t)?.let { result.add(Recognized(ContentType.PHONE, it.value)) }
        }

        val addrCandidate = when {
            platform != null -> ""
            urlMatch != null -> t.replace(urlMatch.value, "")
            else -> t
        }
        if (addrCandidate.any { it in '\u4e00'..'\u9fa5' }) {
            val addr = AddressMatcher.analyze(ctx, t)
            if (addr.isAddress) {
                result.add(Recognized(ContentType.ADDRESS, addr.query))
            }
        }

        return result
    }
}
