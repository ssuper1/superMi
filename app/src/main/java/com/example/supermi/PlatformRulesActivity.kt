package com.example.supermi

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.supermi.xposed.PlatformRule
import com.example.supermi.xposed.PlatformRuleStore

class PlatformRulesActivity : AppCompatActivity() {

    private val rules = mutableListOf<PlatformRule>()
    private lateinit var adapter: ArrayAdapter<PlatformRule>
    private var pendingApps: List<String> = emptyList()
    private var pendingDeepLink = true
    private lateinit var pendingKw: EditText
    private lateinit var pendingLinks: EditText
    private lateinit var pendingAppBtn: Button
    private lateinit var pendingDeepLinkCb: android.widget.CheckBox
    private var editingIndex = -1

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingApps =
                result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_PKGS)?.toList() ?: emptyList()
            if (::pendingAppBtn.isInitialized) {
                pendingAppBtn.text = "打开App: ${appsLabel(pendingApps)}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_platform_rules)

        applySystemBarsInsets()

        loadRules()
        setupTooltip()

        val list = findViewById<ListView>(R.id.list_rules)
        adapter = object : ArrayAdapter<PlatformRule>(this, R.layout.item_platform_rule, rules) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val r = getItem(position)!!
                val v = convertView ?: layoutInflater.inflate(R.layout.item_platform_rule, parent, false)
                v.findViewById<TextView>(R.id.tv_kw).text =
                    if (r.keyword.isEmpty()) "无" else r.keyword
                v.findViewById<TextView>(R.id.tv_app).text =
                    "→ ${appsLabel(displayApps(r))}${if (r.deepLink) " ·深链" else " ·仅打开"}"
                v.findViewById<TextView>(R.id.tv_links).text = r.links.joinToString(", ")
                return v
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> showEditDialog(position) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            rules.removeAt(position)
            save()
            true
        }
        findViewById<Button>(R.id.btn_add).setOnClickListener { showEditDialog(-1) }
    }

    private fun applySystemBarsInsets() {
        val root = findViewById<View>(R.id.root)
        val baseLeft = root.paddingLeft
        val baseTop = root.paddingTop
        val baseRight = root.paddingRight
        val baseBottom = root.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                baseLeft + bars.left,
                baseTop + bars.top,
                baseRight + bars.right,
                baseBottom + bars.bottom
            )
            insets
        }
    }

    private fun setupTooltip() {
        val tv = findViewById<TextView>(R.id.tv_usage)
        val full = "1.关键字：强烈建议仅将如“【抖音】”（带有“【】”或“「」”符号）作为关键字，而非纯文本如“抖音”作为关键字，否则极易误判。支持模糊匹配。\n" +
            "2.深链打开：勾选=点击直接跳到链接对应页面（部分app不支持，勾选会导致无法点击气泡无响应）；不勾=仅打开该App，由App用剪贴板口令自己识别。\n" +
            "3.匹配优先级：① 带符号关键字 ② 链接片段 ③ 系统解析。"
        val redTexts = listOf(
            "强烈建议仅将如“【抖音】”（带有“【】”或“「」”符号）作为关键字",
            "部分app不支持，勾选会导致无法点击气泡无响应"
        )
        val ss = android.text.SpannableString(full)
        for (red in redTexts) {
            val start = full.indexOf(red)
            if (start >= 0) {
                ss.setSpan(
                    android.text.style.ForegroundColorSpan(Color.RED),
                    start, start + red.length,
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        tv.text = ss

        val panel = findViewById<View>(R.id.panel_info)
        findViewById<View>(R.id.btn_info).setOnClickListener {
            panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.btn_close).setOnClickListener { panel.visibility = View.GONE }

        val prefs = getSharedPreferences("ui_state", android.content.Context.MODE_PRIVATE)
        if (!prefs.getBoolean("platform_tooltip_shown", false)) {
            panel.visibility = View.VISIBLE
            prefs.edit().putBoolean("platform_tooltip_shown", true).apply()
        }
    }

    private fun loadRules() {
        rules.clear()
        val json = AppConfig.read(this)[PlatformRuleStore.KEY_PLATFORM_RULES]
        rules.addAll(
            if (json.isNullOrBlank()) PlatformRuleStore.DEFAULT_RULES else PlatformRuleStore.parse(json)
        )
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        null
    }

    private fun appsLabel(apps: List<String>): String =
        if (apps.isEmpty()) "自动" else apps.map { appLabel(it) ?: it }.joinToString(",")

    private fun displayApps(r: PlatformRule): List<String> =
        if (r.apps.isNotEmpty()) r.apps else listOfNotNull(r.app)

    private fun showEditDialog(index: Int) {
        editingIndex = index
        pendingApps = when {
            index >= 0 && rules[index].apps.isNotEmpty() -> rules[index].apps
            index >= 0 -> listOfNotNull(rules[index].app)
            else -> emptyList()
        }
        pendingDeepLink = index >= 0 && rules[index].deepLink

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_rule)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = (resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(280))
        dlg.window?.attributes = dlg.window?.attributes?.apply { width = w }

        dlg.findViewById<TextView>(R.id.dlg_title).text = if (index >= 0) "编辑规则" else "新增规则"
        dlg.findViewById<TextView>(R.id.dlg_field1_label).text = "关键字（建议带【】/「」）"
        dlg.findViewById<TextView>(R.id.dlg_field2_label).text = "链接匹配（逗号分隔）"

        pendingKw = dlg.findViewById<EditText>(R.id.dlg_field1).apply {
            hint = "如【闲鱼】/「百度网盘」，留空则仅靠链接"
            if (index >= 0) setText(rules[index].keyword)
        }
        pendingLinks = dlg.findViewById<EditText>(R.id.dlg_field2).apply {
            hint = "如 m.tb.cn, goofish.com"
            if (index >= 0) setText(rules[index].links.joinToString(", "))
        }
        pendingAppBtn = dlg.findViewById<Button>(R.id.dlg_app_btn).apply {
            text = "打开App（最多3个）: ${appsLabel(pendingApps)}"
            setOnClickListener {
                pickerLauncher.launch(
                    Intent(this@PlatformRulesActivity, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_MULTI, true)
                        .putExtra(AppPickerActivity.EXTRA_TYPE, BubblePosProvider.KEY_URL_APP)
                        .putExtra(AppPickerActivity.EXTRA_CURRENT, pendingApps.toTypedArray())
                )
            }
        }
        dlg.findViewById<android.widget.CheckBox>(R.id.dlg_deeplink_cb).isChecked = pendingDeepLink

        dlg.findViewById<Button>(R.id.dlg_cancel).setOnClickListener { dlg.dismiss() }
        dlg.findViewById<Button>(R.id.dlg_save).setOnClickListener {
            pendingDeepLink = dlg.findViewById<android.widget.CheckBox>(R.id.dlg_deeplink_cb).isChecked
            if (saveRule()) dlg.dismiss()
        }
        dlg.show()
    }

    private fun saveRule(): Boolean {
        val kwRaw = pendingKw.text.toString().trim()
        val kw = if (kwRaw == "【】") "" else kwRaw
        val links = pendingLinks.text.toString()
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val apps = pendingApps.distinct().take(3)
        if (apps.isEmpty()) {
            Toast.makeText(this, "请至少选择一个App", Toast.LENGTH_SHORT).show()
            return false
        }
        if (kw.isEmpty() && links.isEmpty()) {
            Toast.makeText(this, "关键字和链接至少填一个", Toast.LENGTH_SHORT).show()
            return false
        }
        val app = apps.first()
        val rule = PlatformRule(kw, links, app, apps, pendingDeepLink)
        if (editingIndex >= 0) {
            rules[editingIndex] = rule
        } else {
            rules.add(rule)
        }
        save()
        return true
    }

    private fun save() {
        val m = AppConfig.read(this)
        m[PlatformRuleStore.KEY_PLATFORM_RULES] = PlatformRuleStore.toJson(rules)
        AppConfig.write(this, m)
        BubblePosProvider.platformRulesJson = PlatformRuleStore.toJson(rules)
        adapter.notifyDataSetChanged()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
