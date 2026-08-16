package com.example.supermi

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.supermi.RecommendedApps

class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_TYPE = "type"
        const val EXTRA_MULTI = "multi"
        const val EXTRA_PKGS = "pkgs"
        const val EXTRA_CURRENT = "current"
        const val MAX_SELECT = 3
    }

    data class AppInfo(
        val label: String,
        val pkg: String,
        val icon: Drawable,
        val isSystem: Boolean
    )

    private lateinit var allApps: List<AppInfo>
    private val shownApps = mutableListOf<AppInfo>()
    private lateinit var adapter: ArrayAdapter<AppInfo>
    private var includeSystem = false
    private var type: String = BubblePosProvider.KEY_ADDR_APP
    private var multi = false
    private val selected = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        applySystemBarsInsets()

        type = intent.getStringExtra(EXTRA_TYPE) ?: BubblePosProvider.KEY_ADDR_APP
        multi = intent.getBooleanExtra(EXTRA_MULTI, false)
        intent.getStringArrayExtra(EXTRA_CURRENT)
            ?.filter { it.isNotEmpty() }
            ?.let { selected.addAll(it) }

        val list = findViewById<ListView>(R.id.list_apps)
        adapter = object : ArrayAdapter<AppInfo>(this, R.layout.item_app, shownApps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val info = getItem(position)!!
                val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
                view.findViewById<ImageView>(R.id.iv_icon).setImageDrawable(info.icon)
                view.findViewById<TextView>(R.id.tv_label).text = info.label
                view.findViewById<TextView>(R.id.tv_pkg).text = info.pkg
                val cb = view.findViewById<ImageView>(R.id.iv_check)
                if (multi) {
                    cb.visibility = View.VISIBLE
                    cb.setImageResource(
                        if (selected.contains(info.pkg)) R.drawable.ic_check_on
                        else R.drawable.ic_check_off
                    )
                } else {
                    cb.visibility = View.GONE
                }
                return view
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> onItemTap(shownApps[position]) }

        findViewById<EditText>(R.id.et_search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                applyFilter(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })

        findViewById<SwitchCompat>(R.id.sw_system).setOnCheckedChangeListener { _, checked ->
            includeSystem = checked
            applyFilter(findViewById<EditText>(R.id.et_search).text.toString())
        }

        val confirm = findViewById<Button>(R.id.btn_confirm)
        if (multi) {
            confirm.visibility = View.VISIBLE
            confirm.setOnClickListener { confirmSelection() }
        }

        findViewById<View>(R.id.btn_picker_refresh).setOnClickListener {
            AppListCache.invalidate(this)
            reloadApps()
        }

        reloadApps()
    }

    private fun reloadApps() {
        findViewById<View>(R.id.progress_load).visibility = View.VISIBLE
        Thread {
            val apps = loadApps().sortedWith(
                compareBy(
                    { !selected.contains(it.pkg) },
                    { !RecommendedApps.isRecommended(this, type, it.label, it.pkg) },
                    { it.label }
                )
            )
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                allApps = apps
                findViewById<View>(R.id.progress_load).visibility = View.GONE
                applyFilter(findViewById<EditText>(R.id.et_search).text.toString())
                renderHistory()
                updateConfirm()
            }
        }.start()
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

    private fun onItemTap(info: AppInfo) {
        if (multi) {
            if (selected.contains(info.pkg)) {
                selected.remove(info.pkg)
            } else {
                if (selected.size >= MAX_SELECT) {
                    Toast.makeText(this, "最多选择 $MAX_SELECT 个", Toast.LENGTH_SHORT).show()
                    return
                }
                selected.add(info.pkg)
            }
            updateConfirm()
            adapter.notifyDataSetChanged()
        } else {
            AppConfig.addHistory(this, info.pkg)
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_PKG, info.pkg).putExtra(EXTRA_LABEL, info.label)
            )
            finish()
        }
    }

    private fun confirmSelection() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "请至少选择一个", Toast.LENGTH_SHORT).show()
            return
        }
        for (pkg in selected) AppConfig.addHistory(this, pkg)
        setResult(RESULT_OK, Intent().putExtra(EXTRA_PKGS, selected.toTypedArray()))
        finish()
    }

    private fun updateConfirm() {
        if (multi) {
            findViewById<Button>(R.id.btn_confirm).text = "确定 (${selected.size}/$MAX_SELECT)"
        }
    }

    private fun applyFilter(query: String) {
        val q = query.lowercase()
        shownApps.clear()
        shownApps.addAll(
            allApps.filter {
                (includeSystem || !it.isSystem) &&
                    (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
            }
        )
        adapter.notifyDataSetChanged()
    }

    private fun loadApps(): List<AppInfo> {
        val cached = AppListCache.cached(this)?.takeIf { it.isNotEmpty() }
        val src = cached ?: AppListCache.rebuild(this)
        return src.map { c ->
            val icon = AppListCache.loadIcon(this, c.pkg)
                ?: runCatching {
                    val ai = packageManager.getApplicationInfo(c.pkg, 0)
                    val bmp = AppListCache.roundIcon(resources, packageManager.getApplicationIcon(ai))
                    AppListCache.saveIcon(this, c.pkg, bmp)
                    BitmapDrawable(resources, bmp)
                }.getOrElse { packageManager.defaultActivityIcon }
            AppInfo(c.label, c.pkg, icon, c.isSystem)
        }
    }

    private fun renderHistory() {
        val row = findViewById<LinearLayout>(R.id.history_row)
        row.removeAllViews()
        val pm = packageManager
        for (pkg in AppConfig.history(this)) {
            val label = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (_: Throwable) {
                pkg
            }
            val chip = Button(this).apply {
                text = label
                isAllCaps = false
                minWidth = 0
                minHeight = 0
                textSize = 12f
                setTextColor(resources.getColor(R.color.blue_text, theme))
                background = getDrawable(R.drawable.bg_chip)
                backgroundTintList = null
                stateListAnimator = null
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    val icon = try {
                        pm.getApplicationIcon(pkg)
                    } catch (_: Throwable) {
                        pm.defaultActivityIcon
                    }
                    onItemTap(AppInfo(label, pkg, icon, false))
                }
                setOnLongClickListener {
                    AppConfig.removeHistory(this@AppPickerActivity, pkg)
                    renderHistory()
                    true
                }
            }
            row.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
