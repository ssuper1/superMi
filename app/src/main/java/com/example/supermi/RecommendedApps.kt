package com.example.supermi

import android.content.Context

object RecommendedApps {

    fun keywords(type: String): List<String> = when (type) {
        BubblePosProvider.KEY_ADDR_APP -> listOf("地图", "导航", "高德", "amap", "map")
        BubblePosProvider.KEY_URL_APP ->
            listOf("浏览器", "chrome", "夸克", "edge", "firefox", "browser", "via")
        "number" ->
            listOf(
                "电话", "拨号", "通讯录", "联系人", "快递", "菜鸟", "顺丰", "圆通", "中通",
                "韵达", "申通", "京东", "邮政", "菜鸟裹裹", "dialer", "phone", "express", "cainiao"
            )
        else -> listOf("拨号", "电话", "dialer", "phone")
    }

    fun extraPackages(ctx: Context, type: String): List<String> {
        val key = when (type) {
            BubblePosProvider.KEY_ADDR_APP -> "rec_addr_pkgs"
            BubblePosProvider.KEY_URL_APP -> "rec_url_pkgs"
            else -> "rec_phone_pkgs"
        }
        return AppConfig.read(ctx)[key]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    fun isRecommended(ctx: Context, type: String, label: String, pkg: String): Boolean {
        val s = "$label $pkg".lowercase()
        if (keywords(type).any { s.contains(it.lowercase()) }) return true
        if (extraPackages(ctx, type).any { it == pkg }) return true
        return false
    }
}
