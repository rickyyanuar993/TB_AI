# ⚡ TB Buatan Sendiri (Multi-Profile Backup & Restore)

Aplikasi Android berbasis **Root (Magisk)** untuk mengelola multi-profil data aplikasi secara dinamis (mirip dengan konsep kerja Swift Backup / Titanium Backup).

---

## ✨ Fitur Utama

- **Akses Root Bawaan**: Menjalankan perintah dengan hak akses penuh (`su -c`) untuk membackup direktori privat `/data/data/`.
- **Multi-Profile (Slot)**: Buat profil tanpa batas (misal: `01`, `02`, `03`, dll.). Setiap profil menyimpan status data tersendiri.
- **Auto-Switching dengan Backup/Restore**:
  - Saat berpindah dari **Profil 01** ke **Profil 02**: Aplikasi otomatis mem-backup data aplikasi aktif saat ini ke slot **Profil 01** -> membersihkan data aplikasi (`pm clear`) -> merestore data dari slot **Profil 02** (jika ada backup sebelumnya) atau membiarkannya bersih (jika profil baru).
- **Deteksi Aplikasi Pintar**:
  - Mendukung target default: **WhatsApp** (`com.whatsapp`) dan **WhatsApp Business** (`com.whatsapp.w4b`).
  - Pengguna dapat memilih aplikasi tambahan apa saja yang terinstal di perangkat melalui dialog pemilih aplikasi.
- **Konsol Perintah Root Real-time**: Menampilkan perintah shell yang dijalankan di latar belakang secara transparan.

---

## 🛠️ Logika & Perintah Root yang Digunakan

Aplikasi ini menggunakan Executor latar belakang untuk mengeksekusi perintah shell berikut melalui `su`:

1. **Memaketkan Data (Backup)**:
   Aplikasi menggunakan command `tar` bawaan Android untuk mengompres folder data privat aplikasi ke SD Card:
   ```bash
   tar -czf /sdcard/TB_Buatan_Sendiri/profiles/<profile_name>/<package_name>/data.tar.gz -C /data/data/<package_name> .
   ```

2. **Menghapus & Mereset Aplikasi (Clear)**:
   Aplikasi dihentikan paksa lalu datanya dibersihkan agar berada dalam status bersih (fresh):
   ```bash
   am force-stop <package_name>
   pm clear <package_name>
   rm -rf /data/data/<package_name>/*
   ```

3. **Mengekstrak Data (Restore)**:
   Mengekstrak kembali file backup `.tar.gz` ke dalam direktori aplikasi:
   ```bash
   tar -xzf /sdcard/TB_Buatan_Sendiri/profiles/<profile_name>/<package_name>/data.tar.gz -C /data/data/<package_name>/
   ```

4. **Memperbaiki Hak Akses & SELinux Context**:
   Ini adalah langkah **paling krusial** setelah ekstraksi agar aplikasi tidak mengalami crash. Konteks SELinux dan kepemilikan UID dikembalikan sesuai aslinya:
   ```bash
   chown -R <app_uid>:<app_uid> /data/data/<package_name>
   chmod -R 771 /data/data/<package_name>
   restorecon -R /data/data/<package_name>
   ```

---

## 🚀 Cara Build APK (Otomatis via GitHub Actions)

Sama seperti ContactSaver, repositori ini sudah dilengkapi alur kerja otomatis GitHub Actions.

1. Buat repositori baru di GitHub dengan nama `TBBuatanSendiri`.
2. Push seluruh folder proyek ini ke repositori tersebut.
3. Buka tab **Actions** di halaman repositori GitHub Anda.
4. Workflow `Build APK` akan berjalan secara otomatis. Setelah selesai (lingkaran berubah menjadi centang hijau ✅), Anda dapat mengunduh file `.apk` hasil build pada bagian **Artifacts** di bawah halaman run tersebut.

---

## ⚠️ Catatan Keamanan & Hak Akses
- Aplikasi ini **membutuhkan hak akses Root (Superuser)** untuk dapat bekerja. Pastikan Anda memberikan izin akses root pada aplikasi ini di manajer root Anda (seperti Magisk / KernelSU / APatch) saat pertama kali dijalankan.
- File backup disimpan di `/sdcard/TB_Buatan_Sendiri/profiles/`. Anda dapat memindahkan atau menyalin folder ini ke penyimpanan lain untuk cadangan eksternal.
