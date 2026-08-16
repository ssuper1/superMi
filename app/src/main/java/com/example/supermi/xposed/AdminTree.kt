package com.example.supermi.xposed

import android.content.Context
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import org.json.JSONObject

class AdminNode(val name: String, val alias: String?) {
    val children: MutableList<AdminNode> = mutableListOf()
    val names: MutableList<String> = mutableListOf()
}

object AdminTree {

    data class ChainStep(val node: AdminNode, val start: Int, val end: Int)

    private val SUFFIXES = listOf(
        "特别行政区", "自治区", "自治州", "自治县",
        "省", "市", "区", "县", "盟", "旗", "州"
    )

    private var root: List<AdminNode>? = null
    private var trie: Trie? = null

    private class Trie {
        val children = HashMap<Char, Trie>()
        val nodes = ArrayList<AdminNode>()
    }

    fun ensureLoaded(ctx: Context?) {
        if (root != null) return
        synchronized(this) {
            if (root != null) return
            try {
                val appCtx = ctx?.createPackageContext("com.example.supermi", 0)
                val json = appCtx?.assets?.open("admin_tree.json")
                    ?.bufferedReader()
                    ?.use { it.readText() }
                val parsed = parse(json)
                fillNames(parsed)
                root = parsed
                trie = buildTrie(parsed)
                XposedBridge.log("SuperMi: AdminTree loaded, provinces=${parsed.size}")
            } catch (t: Throwable) {
                XposedBridge.log("SuperMi: AdminTree load failed: $t")
                root = emptyList()
                trie = Trie()
            }
        }
    }

    private fun parse(json: String?): List<AdminNode> {
        val arr = JSONArray(json ?: "[]")
        val list = mutableListOf<AdminNode>()
        for (i in 0 until arr.length()) {
            list.add(parseNode(arr.optJSONObject(i) ?: continue))
        }
        return list
    }

    private fun parseNode(o: JSONObject): AdminNode {
        val node = AdminNode(o.getString("n"), o.optString("a", "").ifEmpty { null })
        val ca = o.optJSONArray("c")
        if (ca != null) {
            for (j in 0 until ca.length()) {
                node.children.add(parseNode(ca.optJSONObject(j) ?: continue))
            }
        }
        return node
    }

    private fun normalize(fullName: String): String {
        var n = fullName
        for (suf in SUFFIXES) {
            if (n.endsWith(suf)) {
                n = n.dropLast(suf.length)
                break
            }
        }
        return n
    }

    private fun fillNames(nodes: List<AdminNode>) {
        fun walk(n: AdminNode) {
            val norm = normalize(n.name)
            if (norm.length >= 2 && !n.names.contains(norm)) n.names.add(norm)
            n.alias?.let { if (it.length >= 2 && !n.names.contains(it)) n.names.add(it) }
            for (c in n.children) walk(c)
        }
        for (n in nodes) walk(n)
    }

    private fun buildTrie(nodes: List<AdminNode>): Trie {
        val rootTrie = Trie()
        fun insert(t: Trie, s: String, node: AdminNode) {
            var cur = t
            for (ch in s) {
                cur = cur.children.getOrPut(ch) { Trie() }
            }
            cur.nodes.add(node)
        }
        fun walk(n: AdminNode) {
            for (name in n.names) insert(rootTrie, name, n)
            for (c in n.children) walk(c)
        }
        for (n in nodes) walk(n)
        return rootTrie
    }

    private fun skipSuffix(text: String, pos: Int): Int {
        for (suf in SUFFIXES) {
            if (text.startsWith(suf, pos)) return pos + suf.length
        }
        return pos
    }

    fun findChain(text: String): List<ChainStep> {
        val t = trie ?: return emptyList()
        var best: List<ChainStep> = emptyList()
        val n = text.length
        var i = 0
        while (i < n) {
            var cur = t
            var j = i
            while (j < n) {
                val child = cur.children[text[j]] ?: break
                cur = child
                j++
                for (node in cur.nodes) {
                    val chain = mutableListOf(ChainStep(node, i, j))
                    descend(node, text, skipSuffix(text, j), chain)
                    if (chain.size > best.size) best = chain.toList()
                    if (best.size >= 4) return best
                }
            }
            i++
        }
        return best
    }

    private fun descend(node: AdminNode, text: String, pos: Int, chain: MutableList<ChainStep>) {
        var p = pos
        while (p < text.length) {
            if (text[p].isWhitespace()) {
                p++
                continue
            }
            var bestChild: AdminNode? = null
            var bestLen = 0
            for (c in node.children) {
                for (mn in c.names) {
                    if (mn.length > bestLen && text.startsWith(mn, p)) {
                        bestLen = mn.length
                        bestChild = c
                    }
                }
            }
            val child = bestChild ?: break
            chain.add(ChainStep(child, p, p + bestLen))
            p = skipSuffix(text, p + bestLen)
        }
    }
}
