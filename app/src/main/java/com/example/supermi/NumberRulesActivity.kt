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
import com.example.supermi.xposed.NumberRule
import com.example.supermi.xposed.NumberRuleStore

class NumberRulesActivity : AppCompatActivity() {

    private val rules = mutableListOf<NumberRule>()
    private lateinit var adapter: ArrayAdapter<NumberRule>
    private var editingIndex = -1
    private var pendingApps: List<String> = emptyList()
    private lateinit var pendingPrefix: EditText
    private lateinit var pendingLen: EditText
    private lateinit var pendingAppBtn: Button

    private val pickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingApps =
                result.data?.getStringArrayExtra(AppPickerActivity.EXTRA_PKGS)?.toList() ?: emptyList()
            updateAppBtn()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_number_rules)

        loadRules()

        val list = findViewById<ListView>(R.id.list_rules)
        adapter = object : ArrayAdapter<NumberRule>(this, R.layout.item_number_rule, rules) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val r = getItem(position)!!
                val v = convertView ?: layoutInflater.inflate(R.layout.item_number_rule, parent, false)
                v.findViewById<TextView>(R.id.tv_prefix).text =
                    if (r.prefixes.isEmpty()) "(任意)" else r.prefixes.joinToString(",")
                v.findViewById<TextView>(R.id.tv_len).text = fmtLens(r)
                v.findViewById<TextView>(R.id.tv_apps).text =
                    if (r.apps.isEmpty()) "→ 拨号" else "→ ${r.apps.map { appLabel(it) ?: it }.joinToString(",")}"
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

    private fun loadRules() {
        rules.clear()
        val json = AppConfig.read(this)[NumberRuleStore.KEY_NUMBER_RULES]
        rules.addAll(
            if (json.isNullOrBlank()) NumberRuleStore.DEFAULT_RULES else NumberRuleStore.parse(json)
        )
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        null
    }

    private fun fmtLens(r: NumberRule): String {
        if (r.lengths.isEmpty()) return "任意长度"
        val sorted = r.lengths.sorted()
        val consec = sorted.zipWithNext().all { (a, b) -> b == a + 1 }
        return if (consec) {
            (if (sorted.size == 1) "${sorted[0]}" else "${sorted.first()}~${sorted.last()}") + " 位"
        } else {
            sorted.joinToString("/") + " 位"
        }
    }

    private fun updateAppBtn() {
        if (::pendingAppBtn.isInitialized) {
            pendingAppBtn.text =
                if (pendingApps.isEmpty()) "打开App: 未选择（拨号）"
                else "打开App: ${pendingApps.map { appLabel(it) ?: it }.joinToString(",")}"
        }
    }

    private fun showEditDialog(index: Int) {
        editingIndex = index
        pendingApps = if (index >= 0) rules[index].apps else emptyList()

        val dlg = Dialog(this)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_rule)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = (resources.displayMetrics.widthPixels - dp(48)).coerceAtLeast(dp(280))
        dlg.window?.attributes = dlg.window?.attributes?.apply { width = w }

        dlg.findViewById<TextView>(R.id.dlg_title).text = if (index >= 0) "编辑规则" else "新增规则"
        dlg.findViewById<TextView>(R.id.dlg_field1_label).text = "开头（留空=任意，多开头逗号分隔）"
        dlg.findViewById<TextView>(R.id.dlg_field2_label).text = "位数（固定总长，逗号分隔）"

        pendingPrefix = dlg.findViewById<EditText>(R.id.dlg_field1).apply {
            hint = "如 SF / 1 / YT"
            if (index >= 0) setText(rules[index].prefixes.joinToString(","))
        }
        pendingLen = dlg.findViewById<EditText>(R.id.dlg_field2).apply {
            hint = "如 7,8,9 → 7~9位；7,9 → 7或9位"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            if (index >= 0) setText(rules[index].lengths.sorted().joinToString(","))
        }
        pendingAppBtn = dlg.findViewById<Button>(R.id.dlg_app_btn).apply {
            text = "打开App: ${if (pendingApps.isEmpty()) "未选择（拨号）" else pendingApps.map { appLabel(it) ?: it }.joinToString(",")}"
            setOnClickListener {
                pickerLauncher.launch(
                    Intent(this@NumberRulesActivity, AppPickerActivity::class.java)
                        .putExtra(AppPickerActivity.EXTRA_MULTI, true)
                        .putExtra(AppPickerActivity.EXTRA_TYPE, "number")
                        .putExtra(AppPickerActivity.EXTRA_CURRENT, pendingApps.toTypedArray())
                )
            }
        }

        dlg.findViewById<Button>(R.id.dlg_cancel).setOnClickListener { dlg.dismiss() }
        dlg.findViewById<Button>(R.id.dlg_save).setOnClickListener {
            if (saveRule()) dlg.dismiss()
        }
        dlg.show()
    }

    private fun saveRule(): Boolean {
        val prefixes = pendingPrefix.text.toString()
            .split(',', '，')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val lengths = pendingLen.text.toString()
            .split(',', '，')
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it > 0 }
        if (prefixes.isEmpty() && lengths.isEmpty()) {
            Toast.makeText(this, "开头和位数至少填一个", Toast.LENGTH_SHORT).show()
            return false
        }
        val rule = NumberRule(prefixes, lengths.distinct(), pendingApps.distinct().take(3))
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
        m[NumberRuleStore.KEY_NUMBER_RULES] = NumberRuleStore.toJson(rules)
        AppConfig.write(this, m)
        BubblePosProvider.numberRulesJson = NumberRuleStore.toJson(rules)
        adapter.notifyDataSetChanged()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
