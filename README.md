# Xiaomi TV Remote Control

Aplikasi remote control untuk Xiaomi TV yang berfungsi sebagai pengganti remote fisik. Mendukung koneksi **Bluetooth HID** dan **Wi-Fi (ADB)**.

## Fitur

- 📱 **Remote Lengkap** - Tombol power, volume, channel, D-pad, navigasi
- 🎤 **Voice Control** - Perintah suara untuk kontrol TV (Indonesia)
- 🎯 **App Launcher** - Buka Netflix, YouTube, Spotify, Disney+, dll
- 🔢 **Number Pad** - Input angka langsung (0-9)
- 🎨 **Modern UI** - Dark theme, responsive, enterprise style
- 📡 **Dual Connection** - Bluetooth HID dan Wi-Fi ADB
- 🔍 **Network Scan** - Cari TV otomatis di jaringan lokal
- 📋 **Activity Log** - Monitor semua perintah yang dikirim

## Arsitektur

```
xiaomi-tv-remote/
├── android/                    # Android Native Project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── java/com/tvremote/
│   │   │   │   └── MainActivity.kt      # WebView + JS Bridge
│   │   │   ├── assets/web/              # UI HTML/CSS/JS
│   │   │   │   ├── index.html
│   │   │   │   ├── css/style.css
│   │   │   │   └── js/remote.js
│   │   │   └── res/                     # Android Resources
│   │   └── build.gradle
│   └── build.gradle
└── .github/workflows/
    └── build.yml                         # CI/CD GitHub Actions
```

## Koneksi

### Mode Wi-Fi (ADB) - Direkomendasikan

1. **Aktifkan ADB Debugging** di Xiaomi TV:
   - Settings > Device Preferences > About > Klik "Build" 7x
   - Return > Developer options > ADB debugging = ON
   - Catat IP TV di: Settings > Network

2. **Connect dari Aplikasi**:
   - Masukkan IP TV (contoh: 192.168.1.100)
   - Tap "Hubungkan"
   - Atau tap "Scan Jaringan" untuk mencari otomatis

3. **Siap digunakan!**

### Mode Bluetooth HID

> ⚠️ **Catatan:** Xiaomi TV biasanya hanya menerima remote Bluetooth resmi. Mode ini mungkin tidak bekerja di semua model.

1. Buka pengaturan Bluetooth di TV
2. Pairing perangkat Bluetooth
3. Pilih "TV Remote" dari daftar perangkat
4. Aplikasi akan otomatis terhubung

## Build & Deploy

### Otomatis via GitHub Actions

1. Push ke branch `main`
2. GitHub Actions akan build APK otomatis
3. Download APK dari Actions > Artifacts

### Lokal (jika tersedia Android SDK)

```bash
cd android
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

## Permissions

| Permission | Keterangan |
|------------|------------|
| `INTERNET` | Koneksi Wi-Fi/ADB |
| `ACCESS_WIFI_STATE` | Baca status Wi-Fi |
| `BLUETOOTH` | Koneksi Bluetooth |
| `BLUETOOTH_CONNECT` | Bluetooth pairing (Android 12+) |

## Teknologi

- **Frontend:** HTML5, CSS3, Vanilla JavaScript
- **Native:** Kotlin, Android WebView
- **Bluetooth:** `BluetoothHidDevice` API (Android 9+)
- **Wi-Fi:** Socket ADB ke port 5555
- **CI/CD:** GitHub Actions

## Testing

Aplikasi ini telah diuji pada:
- ✅ Xiaomi TV 4A Series (Android TV 9)
- ✅ Xiaomi TV Stick 4K
- ✅ Redmi C (Android 10)
- ✅ Pixel 6 (Android 14)

## Troubleshooting

### Wi-Fi Connection
- **"Connection refused"**: Pastikan ADB debugging diaktifkan di TV
- **"Timeout"**: Pastikan HP dan TV di jaringan Wi-Fi yang sama
- **Port 5555**: Default ADB port, tidak perlu diubah

### Bluetooth
- **"Device not found"**: TV harus dalam mode discoverable
- **"Connection failed"**: Beberapa TV tidak mendukung HID third-party
- **Tombol tidak bekerja**: TV mungkin memerlukan remote resmi

## Lisensi

MIT License - Lihat file LICENSE untuk detail.

## Kontak

GitHub: [@ndrow](https://github.com/ndrow)
