package app.xrayfrag

import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import org.json.JSONObject
import xb.Xb
import java.io.File
import java.net.Inet4Address

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
                val cfg = patchDns(ConfigStore.load(this))
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

    /**
     * Go di Android tidak punya resolver sistem untuk "localhost", jadi diganti
     * dengan IP DNS jaringan aktif (diambil sebelum VPN naik).
     */
    private fun patchDns(cfg: String): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val dns = cm.getLinkProperties(cm.activeNetwork)?.dnsServers
            ?.firstOrNull { it is Inet4Address }?.hostAddress ?: return cfg
        val root = JSONObject(cfg)
        val servers = root.optJSONObject("dns")?.optJSONArray("servers") ?: return cfg
        for (i in 0 until servers.length()) {
            val s = servers.optJSONObject(i) ?: continue
            if (s.optString("address") == "localhost") s.put("address", dns)
        }
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
