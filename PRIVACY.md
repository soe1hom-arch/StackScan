# Kebijakan Privasi — StackScan

Terakhir diperbarui: 2026-08-11

StackScan memproses foto **100% offline di perangkat Anda**:

- **Tidak ada data yang dikumpulkan.** Aplikasi tidak mengirim foto, statistik,
  log, atau informasi pribadi apa pun ke server eksternal.
- **Tidak ada jaringan.** Aplikasi tidak meminta izin `INTERNET` dan tidak
  melakukan komunikasi jaringan sama sekali.
- **Foto hanya diproses & disimpan lokal.** Foto yang Anda pilih dibaca dari
  galeri, diproses di perangkat, dan hasilnya disimpan ke folder
  `Pictures/StackScan` di galeri Anda.
- **Tidak ada akun, telemetri, atau iklan.** StackScan tidak memakai layanan
  analitik, pelacak, atau SDK iklan.

## Data di perangkat

Setelan aplikasi (preset, riwayat hasil) disimpan secara lokal di perangkat
(SharedPreferences / penyimpanan aplikasi). Anda dapat menghapusnya kapan saja
dengan menghapus data aplikasi dari Setelan Android. `android:allowBackup`
mengizinkan cadangan Android standar; data cadangan tetap berada dalam kendali
sistem operasi dan akun Google Anda.

## Perubahan kebijakan

Jika kebijakan ini berubah, versi terbaru akan selalu tersedia di sini
(`PRIVACY.md` di repositori proyek).
