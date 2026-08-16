package com.example.supermi

import android.content.Context
import java.io.File

object AppConfig {

    private fun file(ctx: Context): File = File(ctx.filesDir, BubblePosProvider.CONFIG_FILE)

    fun read(ctx: Context): MutableMap<String, String> {
        val m = mutableMapOf<String, String>()
        try {
            for (line in file(ctx).readLines()) {
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }
        } catch (_: Throwable) {
        }
        return m
    }

    fun write(ctx: Context, map: Map<String, String>) {
        try {
            file(ctx).writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        } catch (_: Throwable) {
        }
    }

    fun history(ctx: Context): List<String> =
        read(ctx)["history"]?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()

    fun addHistory(ctx: Context, pkg: String) {
        val h = history(ctx).toMutableList()
        h.remove(pkg)
        h.add(0, pkg)
        val m = read(ctx).toMutableMap()
        m["history"] = h.take(10).joinToString(",")
        write(ctx, m)
    }

    fun removeHistory(ctx: Context, pkg: String) {
        val h = history(ctx).filter { it != pkg }
        val m = read(ctx).toMutableMap()
        m["history"] = h.joinToString(",")
        write(ctx, m)
    }
}
