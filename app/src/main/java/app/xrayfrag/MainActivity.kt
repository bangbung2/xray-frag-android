package app.xrayfrag

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.TrafficStats
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var pingView: TextView
    private lateinit var speedView: TextView
    private lateinit var toggle: Button
    private lateinit var cfgToggle: Button
    private lateinit var panel: LinearLayout
    private lateinit var editor: EditText

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refresh(); h.postDelayed(this, 1000) }
    }

    private var wasRunning = false
    private var baseTx = 0L
    private var baseRx = 0L
    private var lastTx = 0L
    private var lastRx = 0L

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(16))
        }

        status = TextView(this).apply { textSize = 16f }
        pingView = TextView(this).apply { textSize = 15f; text = "Ping: -" }
        speedView = TextView(this).apply { textSize = 14f; typeface = Typeface.MONOSPACE }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Button(this).apply {
            text = "Tes ms"; isAllCaps = false
            setOnClickListener { doPing() }
            row1.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        cfgToggle = Button(this).apply {
            text = "Config ▾"; isAllCaps = false
            setOnClickListener { togglePanel() }
            row1.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val modes = listOf("Asli", "Ringan", "Mati")
        Button(this).apply {
            isAllCaps = false; textSize = 12f
            text = "Frag: ${modes[this@MainActivity.getSharedPreferences("app", MODE_PRIVATE).getInt("fragMode", 0)]}"
            setOnClickListener {
                val sp = this@MainActivity.getSharedPreferences("app", MODE_PRIVATE)
                val n = (sp.getInt("fragMode", 0) + 1) % 3
                sp.edit().putInt("fragMode", n).apply()
                text = "Frag: ${modes[n]}"
                Toast.makeText(this@MainActivity, "Putuskan lalu sambungkan lagi", Toast.LENGTH_SHORT).show()
            }
            row1.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        // ---- panel config (tersembunyi secara default) ----
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun small(label: String, f: () -> Unit) = Button(this).apply {
            text = label; textSize = 11f; isAllCaps = false
            setOnClickListener { f() }
            row2.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        small("Simpan") { save() }
        small("Import") { pick(101, Intent.ACTION_OPEN_DOCUMENT) }
        small("Export") { pick(102, Intent.ACTION_CREATE_DOCUMENT) }
        small("Reset") { confirmReset() }
        small("Log") { showLog() }

        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(ConfigStore.load(this@MainActivity))
        }
        panel.addView(row2)
        panel.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(status)
        root.addView(pingView)
        root.addView(speedView)
        root.addView(toggle)
        root.addView(row1)
        root.addView(panel, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() { super.onResume(); h.post(tick) }
    override fun onPause() { super.onPause(); h.removeCallbacks(tick) }

    private fun togglePanel() {
        val show = panel.visibility != View.VISIBLE
        panel.visibility = if (show) View.VISIBLE else View.GONE
        cfgToggle.text = if (show) "Config ▴" else "Config ▾"
    }

    private fun fmt(b: Long): String {
        val v = if (b < 0) 0L else b
        return when {
            v < 1024 -> "$v B"
            v < 1024 * 1024 -> "%.1f KB".format(v / 1024.0)
            v < 1024L * 1024 * 1024 -> "%.1f MB".format(v / 1048576.0)
            else -> "%.2f GB".format(v / 1073741824.0)
        }
    }

    private fun refresh() {
        val run = XrayVpnService.running
        status.text = XrayVpnService.status
        toggle.text = if (run) "Putuskan" else "Sambungkan"

        if (!run) {
            wasRunning = false
            speedView.text = "↑ 0 B/s   ↓ 0 B/s"
            return
        }
        // trafik proses app ini = trafik upstream Xray (app dikecualikan dari VPN)
        val uid = android.os.Process.myUid()
        val tx = TrafficStats.getUidTxBytes(uid)
        val rx = TrafficStats.getUidRxBytes(uid)
        if (!wasRunning) {
            wasRunning = true
            baseTx = tx; baseRx = rx; lastTx = tx; lastRx = rx
            pingView.text = "Ping: menguji…"
            h.postDelayed({ if (XrayVpnService.running) doPing() }, 3000)
        }
        if (tx < 0 || rx < 0) {
            speedView.text = "Statistik trafik tidak didukung"
        } else {
            speedView.text = "↑ ${fmt(tx - lastTx)}/s   ↓ ${fmt(rx - lastRx)}/s\n" +
                "Total ↑ ${fmt(tx - baseTx)}   ↓ ${fmt(rx - baseRx)}"
            lastTx = tx; lastRx = rx
        }
    }

    /** Tes lewat proxy Xray (HTTP di 127.0.0.1:10808) -> menunjukkan apakah Xray sendiri tembus internet. */
    private fun doPing() {
        if (!XrayVpnService.running) { pingView.text = "Ping: belum tersambung"; return }
        pingView.text = "Ping: menguji…"
        Thread {
            val t0 = SystemClock.elapsedRealtime()
            val msg = try {
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 10808))
                val c = URL("https://cp.cloudflare.com/generate_204").openConnection(proxy) as HttpURLConnection
                c.connectTimeout = 10000; c.readTimeout = 10000
                c.instanceFollowRedirects = false; c.useCaches = false
                c.responseCode
                c.disconnect()
                "${SystemClock.elapsedRealtime() - t0} ms"
            } catch (e: Exception) {
                "GAGAL (${e.javaClass.simpleName}: ${e.message}) - cek Log"
            }
            runOnUiThread { pingView.text = "Ping: $msg" }
        }.start()
    }

    private fun showLog() {
        val f = File(filesDir, "xray.log")
        val text = if (f.exists() && f.length() > 0) f.readText().takeLast(8000) else "(log kosong)"
        val tv = TextView(this).apply {
            this.text = text; textSize = 10f; typeface = Typeface.MONOSPACE
            setTextIsSelectable(true); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        AlertDialog.Builder(this).setTitle("Log Xray")
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Tutup", null).show()
    }

    private fun confirmReset() {
        AlertDialog.Builder(this).setTitle("Reset config?")
            .setMessage("Config kembali ke bawaan app.")
            .setPositiveButton("Reset") { _, _ ->
                ConfigStore.reset(this); editor.setText(ConfigStore.load(this))
            }
            .setNegativeButton("Batal", null).show()
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
