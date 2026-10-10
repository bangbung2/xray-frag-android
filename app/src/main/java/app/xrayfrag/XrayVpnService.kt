package app.xrayfrag

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import org.json.JSONArray
import org.json.JSONObject
import xb.Xb
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

class XrayVpnService : VpnService() {

    companion object {
        const val ACTION_START = "app.xrayfrag.START"
        const val ACTION_STOP = "app.xrayfrag.STOP"
        @Volatile var running = false
        @Volatile var status = "Terputus"
    }

    private val lock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Thread { stopVpn() }.start()
            return START_NOT_STICKY
        }
        if (!running) Thread { startVpn() }.start()
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    private fun startVpn() {
        synchronized(lock) {
            if (running) return
            try {
                status = "Menyambung…"
                File(filesDir, "xray.log").delete()
                val cfg = patchConfig(ConfigStore.load(this))
                val dir = prepareAssets()
                Xb.startXray(cfg, dir)

                val b = Builder()
                    .setSession("Xray Frag")
                    .setMtu(1500)
                    .addAddress("172.19.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fdfe:dcba:9876::1", 126)
                    .addRoute("::", 0)
                    .addDnsServer("172.19.0.2")
                // app ini dikecualikan agar koneksi keluar Xray tidak masuk TUN lagi (loop)
                b.addDisallowedApplication(packageName)
                val pfd = b.establish() ?: throw IllegalStateException("Izin VPN ditolak")
                val fd = pfd.detachFd()

                Xb.startTun(fd, 1500, "127.0.0.1:10808")
                running = true
                status = "Tersambung (Xray ${Xb.version()})"
            } catch (t: Throwable) {
                status = "Error: ${t.message}"
                try { Xb.stop() } catch (_: Throwable) {}
                running = false
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        synchronized(lock) {
            try { Xb.stop() } catch (_: Throwable) {}
            if (running || status.startsWith("Menyambung")) status = "Terputus"
            running = false
            stopSelf()
        }
    }

    /** IP DNS jaringan aktif (dibaca sebelum VPN naik). */
    private fun systemDns(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val list = cm.activeNetwork?.let { cm.getLinkProperties(it) }?.dnsServers.orEmpty()
        val ip = list.firstOrNull { it is Inet4Address } ?: list.firstOrNull()
        return ip?.hostAddress?.substringBefore('%') ?: "8.8.8.8"
    }

    private fun isIp(a: String) = Regex("^[0-9a-fA-F:.\\[\\]]+$").matches(a)

    /** Resolve hostname lewat resolver Android (sebelum VPN naik). */
    private fun resolveHost(h: String): String? = try {
        val all = InetAddress.getAllByName(h)
        (all.firstOrNull { it is Inet4Address } ?: all.firstOrNull())?.hostAddress
    } catch (_: Throwable) { null }

    /**
     * - "localhost" -> IP DNS sistem (Go di Android tidak punya resolver sistem)
     * - alamat DNS berupa hostname (mis. home.xl.co.id) -> di-resolve jadi IP dulu
     * - log error Xray ditulis ke filesDir/xray.log (bisa dilihat dari tombol Log)
     */
    private fun patchConfig(cfg: String): String {
        val root = JSONObject(cfg)
        val sys = systemDns()
        val servers = root.optJSONObject("dns")?.optJSONArray("servers")
        if (servers != null) {
            for (i in 0 until servers.length()) {
                val s = servers.optJSONObject(i) ?: continue
                val a = s.optString("address")
                when {
                    a == "localhost" -> s.put("address", sys)
                    a.isNotEmpty() && !a.contains("://") && !a.startsWith("fakedns") && !isIp(a) ->
                        resolveHost(a)?.let { s.put("address", it) }
                }
            }
        }
        // mode fragment TLS: 0 = asli (config), 1 = ringan, 2 = mati (tanpa fragment)
        val mode = getSharedPreferences("app", MODE_PRIVATE).getInt("fragMode", 0)
        val outs = root.optJSONArray("outbounds")
        if (mode != 0 && outs != null) {
            for (i in 0 until outs.length()) {
                val o = outs.optJSONObject(i) ?: continue
                if (o.optString("tag") != "tcp-fragment-tls") continue
                val ss = o.optJSONObject("streamSettings") ?: continue
                if (mode == 1) {
                    val st = JSONObject()
                        .put("packets", "tlshello")
                        .put("lengths", JSONArray().put("100-200"))
                        .put("delays", JSONArray().put("1-3"))
                        .put("maxSplit", "0")
                    val fm = JSONObject().put("tcp", JSONArray().put(JSONObject().put("type", "fragment").put("settings", st)))
                    ss.put("finalmask", fm)
                } else {
                    ss.remove("finalmask")
                }
            }
        }
        val log = root.optJSONObject("log") ?: JSONObject().also { root.put("log", it) }
        log.put("error", File(filesDir, "xray.log").absolutePath)
        return root.toString()
    }

    private fun prepareAssets(): String {
        val dir = File(filesDir, "xray").apply { mkdirs() }
        val stampFile = File(dir, ".stamp")
        val stamp = packageManager.getPackageInfo(packageName, 0).lastUpdateTime.toString()
        if (!stampFile.exists() || stampFile.readText() != stamp) {
            for (n in listOf("geoip.dat", "geosite.dat")) {
                assets.open(n).use { i -> File(dir, n).outputStream().use { o -> i.copyTo(o) } }
            }
            stampFile.writeText(stamp)
        }
        return dir.absolutePath
    }
}
