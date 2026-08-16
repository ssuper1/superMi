package com.example.supermi.xposed

import android.content.Context

object AddressMatcher {

    enum class AddressType { STRUCTURED_ADDRESS, ADMINISTRATIVE_AREA, ROAD_ADDRESS, POI, NONE }

    data class Result(
        val isAddress: Boolean,
        val type: AddressType,
        val confidence: Float,
        val query: String,
        val reason: String
    )

    private val VERB_CHARS = "我你他她它去在往从这那来下走跑看想很都把被对向为要会能"

    private fun badName(name: String): Boolean =
        name.any { it in VERB_CHARS }

    private val ROAD_RE =
        Regex("""([\u4e00-\u9fa5]{1,8}?)(路|街|巷|大道|国道|大街|环路|快速路|高速)""")

    private val NUMBER_RE =
        Regex("""((\d+|[一二三四五六七八九十百]+))\s*(号|栋|号楼|幢|单元|室)""")

    private val POI_SUFFIXES = listOf(
        "医院", "大学", "学院", "学校", "中学", "小学", "幼儿园", "超市", "商场", "大张",
        "酒店", "大厦", "广场", "小区", "银行", "车站", "机场", "餐厅", "饭店", "公园",
        "景区", "博物馆", "图书馆", "体育馆", "游泳馆", "电影院", "剧院", "剧场", "写字楼",
        "公寓", "别墅", "加油站", "派出所", "政务中心", "车管所", "邮局", "市场", "药店",
        "书店", "影院", "度假村", "山庄", "庄园", "工业园", "科技园", "创业园", "便利店", "食堂"
    )

    private val POI_RE =
        Regex("""([\u4e00-\u9fa5]{1,10}?)(医院|大学|学院|学校|中学|小学|幼儿园|超市|商场|大张|酒店|大厦|广场|小区|银行|车站|机场|餐厅|饭店|公园|景区|博物馆|图书馆|体育馆|游泳馆|电影院|剧院|剧场|写字楼|公寓|别墅|加油站|派出所|政务中心|车管所|邮局|市场|药店|书店|影院|度假村|山庄|庄园|工业园|科技园|创业园|便利店|食堂)""")

    private val COUNT_RE = Regex(
        """[一二三四五六七八九十百零\d]+(县|区|市|镇|乡|州)|(下辖|共有|包括|辖|拥有|含|分为)\s*[一二三四五六七八九十百零\d]+\s*个?\s*(县|区|市|镇|乡)"""
    )

    fun analyze(ctx: Context?, text: String): Result {
        AdminTree.ensureLoaded(ctx)
        val t = text.trim()
        if (t.isEmpty()) return Result(false, AddressType.NONE, 0f, t, "empty")
        val length = t.length

        val chain = AdminTree.findChain(t)
        val roadMatch = validRoad(t)
        val numberMatch = NUMBER_RE.find(t)
        val poiMatch = validPoi(t)

        val countExpr = COUNT_RE.containsMatchIn(t)
        val adminChainStrong = chain.size >= 2

        val reason = buildString {
            if (countExpr) append("count-expression ")
            if (chain.size >= 1) append("admin(${chain.size}) ")
            if (roadMatch != null) append("road ")
            if (numberMatch != null) append("number ")
            if (poiMatch != null) append("poi ")
        }.trim()

        val ranges = mutableListOf<IntRange>()
        chain.forEach { ranges.add(it.start..it.end) }
        roadMatch?.let { ranges.add(it.range) }
        numberMatch?.let { ranges.add(it.range) }
        poiMatch?.let { ranges.add(it.range) }
        val query = if (ranges.isEmpty()) {
            t
        } else {
            t.substring(ranges.minOf { it.first }, ranges.maxOf { it.last })
        }

        DebugToast.log(
            "SuperMi: ADDR-RAW '$t' chain=${chain.size}[${chain.joinToString(",") { it.node.name }}] " +
                "road=${roadMatch != null} num=${numberMatch != null} poi=${poiMatch != null} count=${countExpr}"
        )

        if (countExpr && !adminChainStrong) {
            return Result(false, AddressType.NONE, 0f, t, reason)
        }

        if (adminChainStrong) {
            val extra = roadMatch != null || numberMatch != null || poiMatch != null
            val type = if (extra) AddressType.STRUCTURED_ADDRESS else AddressType.ADMINISTRATIVE_AREA
            return Result(true, type, 0.95f, query, reason)
        }
        if (roadMatch != null && numberMatch != null) {
            return Result(true, AddressType.ROAD_ADDRESS, 0.9f, query, reason)
        }
        if (chain.size == 1 && (roadMatch != null || numberMatch != null)) {
            return Result(true, AddressType.STRUCTURED_ADDRESS, 0.85f, query, reason)
        }
        if (chain.size == 1 && poiMatch != null) {
            return Result(true, AddressType.STRUCTURED_ADDRESS, 0.8f, query, reason)
        }
        val finalResult = Result(false, AddressType.NONE, 0f, t, reason)
        DebugToast.log("SuperMi: ADDR '$t' -> is=${finalResult.isAddress} type=${finalResult.type} q='${finalResult.query}' r=[${finalResult.reason}]")
        return finalResult
    }

    private fun validRoad(text: String): MatchResult? {
        for (m in ROAD_RE.findAll(text)) {
            val name = m.groupValues[1]
            if (name.length >= 2 && !badName(name)) return m
        }
        return null
    }

    private fun validPoi(text: String): MatchResult? {
        for (m in POI_RE.findAll(text)) {
            val name = m.groupValues[1]
            if (name.length >= 2 && !badName(name)) return m
        }
        return null
    }
}
