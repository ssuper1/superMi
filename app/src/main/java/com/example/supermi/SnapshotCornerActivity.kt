package com.example.supermi

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.supermi.xposed.BubblePrefs

class SnapshotCornerActivity : AppCompatActivity() {

    private var cornerDp = BubblePrefs.DEFAULT_SNAPSHOT_CORNER_DP
    private lateinit var preview: CornerPreviewView
    private lateinit var seek: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snapshot_corner)
        applySystemBarsInsets()

        cornerDp = BubblePrefs.snapshotCornerDp(this)
        preview = findViewById(R.id.preview_corner)
        preview.setCornerDp(cornerDp)
        seek = findViewById(R.id.seek_corner)
        seek.max = BubblePrefs.SNAPSHOT_CORNER_MAX - BubblePrefs.SNAPSHOT_CORNER_MIN
        seek.progress = (cornerDp - BubblePrefs.SNAPSHOT_CORNER_MIN).coerceIn(0, seek.max)
        updateLabel()

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                setCornerDp(BubblePrefs.SNAPSHOT_CORNER_MIN + progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                BubblePrefs.refreshInBackground(this@SnapshotCornerActivity)
            }
        })

        findViewById<Button>(R.id.btn_reset_corner).setOnClickListener {
            setCornerDp(BubblePrefs.DEFAULT_SNAPSHOT_CORNER_DP)
            seek.progress = BubblePrefs.DEFAULT_SNAPSHOT_CORNER_DP - BubblePrefs.SNAPSHOT_CORNER_MIN
            BubblePrefs.refreshInBackground(this)
        }
    }

    override fun onPause() {
        super.onPause()
        BubblePrefs.refreshInBackground(this)
    }

    private fun setCornerDp(v: Int) {
        val value = v.coerceIn(BubblePrefs.SNAPSHOT_CORNER_MIN, BubblePrefs.SNAPSHOT_CORNER_MAX)
        if (value == cornerDp) return
        cornerDp = value
        BubblePosProvider.snapshotCornerDp = value
        val m = AppConfig.read(this)
        m[BubblePosProvider.KEY_SNAPSHOT_CORNER_DP] = "$value"
        AppConfig.write(this, m)
        preview.setCornerDp(value)
        updateLabel()
    }

    private fun updateLabel() {
        findViewById<TextView>(R.id.tv_corner_value).text = "$cornerDp dp"
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
}
