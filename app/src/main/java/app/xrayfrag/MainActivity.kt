package app.xrayfrag

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var editor: EditText
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }
        status = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, dp(8)) }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, f: () -> Unit) = Button(this).apply {
            text = label; textSize = 12f; isAllCaps = false
            setOnClickListener { f() }
            row.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        small("Simpan") { save() }
        small("Import") { pick(101, Intent.ACTION_OPEN_DOCUMENT) }
        small("Export") { pick(102, Intent.ACTION_CREATE_DOCUMENT) }
        small("Reset") { ConfigStore.reset(this); editor.setText(ConfigStore.load(this)) }

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(ConfigStore.load(this@MainActivity))
        }

        root.addView(status)
        root.addView(toggle)
        root.addView(row)
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun refresh() {
        status.text = XrayVpnService.status
        toggle.text = if (XrayVpnService.running) "Putuskan" else "Sambungkan"
    }

    private fun save(): Boolean = try {
        ConfigStore.save(this, editor.text.toString())
        Toast.makeText(this, "Config tersimpan", Toast.LENGTH_SHORT).show()
        true
    } catch (e: Exception) {
        Toast.makeText(this, "JSON tidak valid: ${e.message}", Toast.LENGTH_LONG).show()
        false
    }

    private fun onToggle() {
        if (XrayVpnService.running) {
            startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_STOP))
            return
        }
        if (!save()) return
        val prep = VpnService.prepare(this)
        if (prep != null) startActivityForResult(prep, 100) else startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, XrayVpnService::class.java).setAction(XrayVpnService.ACTION_START))
    }

    private fun pick(code: Int, action: String) {
        val i = Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            if (action == Intent.ACTION_CREATE_DOCUMENT) putExtra(Intent.EXTRA_TITLE, "xray-config.json")
        }
        startActivityForResult(i, code)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data
        when (requestCode) {
            100 -> startVpn()
            101 -> uri?.let {
                try {
                    val t = contentResolver.openInputStream(it)!!.bufferedReader().use { r -> r.readText() }
                    org.json.JSONObject(t)
                    editor.setText(t)
                    ConfigStore.save(this, t)
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal import: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            102 -> uri?.let {
                try {
                    contentResolver.openOutputStream(it)!!.use { o -> o.write(editor.text.toString().toByteArray()) }
                    Toast.makeText(this, "Config diekspor", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Gagal export: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
