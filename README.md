# Xray Frag (Android)

APK VPN yang menjalankan config Xray serverless (fragment TLS) dengan core Xray terbaru.

Alur: Aplikasi -> TUN (VpnService) -> tun2socks -> SOCKS5 127.0.0.1:10808 (inbound mixed Xray) -> routing -> outbound.

## Build (GitHub Actions)
1. Buat repo GitHub baru, upload semua isi folder ini, branch `main`.
2. Buka tab Actions -> `build-apk` -> Run workflow (otomatis jalan saat push).
3. Unduh artifact `xray-frag-apk`, pasang APK-nya (izinkan install dari sumber tidak dikenal).

## Pakai
- Buka app, `Sambungkan`, setujui izin VPN.
- Config bisa diedit, `Simpan`, `Import`, `Export`, `Reset`.
- Kalau gagal, pesan error tampil di bagian status.

## Catatan
- Di config, `"address": "localhost"` otomatis diganti IP DNS jaringan aktif saat connect.
- App ini dikecualikan dari VPN agar tidak loop.
- geoip/geosite diunduh dari Loyalsoldier/v2ray-rules-dat. Kalau ada list yang tidak ada (mis. `geosite:xai`), Xray gagal start dan errornya tampil; ganti sumber di `build.yml`.
- Belum diuji build: kemungkinan perlu perbaikan kecil di versi dependency Go/Gradle.
