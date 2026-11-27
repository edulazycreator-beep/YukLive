# YukLive

Aplikasi Android native untuk live streaming dari kamera HP, capture card USB, atau jaringan Wi-Fi, lengkap dengan overlay grafis dan subtitle live otomatis yang "terbakar" ke stream. Desain hitam monokrom (gaya Kirimly).

- Package: `com.yuklive.app` (build debug: `com.yuklive.app.debug`, supaya bisa terpasang berdampingan)
- minSdk 26 · targetSdk/compileSdk 37 · ABI: arm64-v8a, armeabi-v7a
- Kotlin 2.4 · AGP 9.5 (Kotlin bawaan) · Gradle 9.6 · Compose BOM 2026.09 · Hilt 2.60.1 · KSP 2.3.10

## Status tahap

| Tahap | Isi | Status |
|---|---|---|
| 1 | Setup proyek, tema Kirimly, navigasi, GitHub Actions | Selesai |
| 2 | Kamera HP → preview → live RTMP/RTMPS/SRT/RTSP | Selesai, belum di-build |
| 3 | Pipeline OpenGL + overlay & scene | Selesai, belum di-build |
| 4 | Subtitle live (Android STT + Vosk) | Selesai, belum di-build |
| 5 | Capture card USB (UVC) | Selesai, belum di-build |
| 6 | Input Wi-Fi (RTSP, SRT caller/listener, RTMP pull, HTTP-FLV) | Selesai, belum di-build |
| 7 | Statistik, rekam lokal, ketahanan & polish | Selesai, belum di-build |

## Arsitektur

```
Sumber video ─┐                       ┌─> Encoder H.264/H.265 ─> RTMP/RTMPS/SRT/RTSP
 Kamera HP     │   SurfaceTexture      │                        └> Rekam MP4 (opsional)
 Capture USB   ├─> (OpenGL ES) ─> Filter overlay ─┤
 Wi-Fi         │   RootEncoder         (bitmap overlay + subtitle + fade)
               ┘                       └─> Preview (SurfaceView)

Audio: Mic / USB / Wi-Fi ─> AudioMixer (gain, mute, VU) ─> AAC encoder
                                         └─> Speech-to-text (Android / Vosk)
```

| Paket | Isi |
|---|---|
| `stream/` | `StreamEngine` (inti: prepare, preview, live, reconnect, ABR, rekam, ganti sumber, throttle panas), `StreamCompositor` (overlay+subtitle ~30 fps), `StreamService` (foreground service + notifikasi "Akhiri") |
| `video/` | `CameraController` (lensa & kontrol manual), `UvcVideoSource` (capture card), `network/NetworkVideoSource` (ExoPlayer), `network/SrtDataSource` (srtdroid) |
| `audio/` | `AudioMixer` (AudioSource RootEncoder + mixer + tap PCM), `PcmUtils` |
| `overlay/` | Model layer & scene, `OverlayRepository` (JSON), `OverlayRenderer` (Canvas), `OverlayFilterRender` (GL), `FontRegistry` |
| `subtitle/` | `SubtitleEngine` (potongan kata, auto-clear, transkrip, ekspor), `SubtitleRenderer` (animasi kinetik), `engine/` (Android STT, Vosk, unduh model) |
| `core/` | Pengaturan (DataStore), tujuan stream + enkripsi Keystore, status perangkat (baterai/suhu/termal/internet) |
| `feature/` | Layar Compose: Beranda, Studio, Overlay, Pengaturan, Sumber, Subtitle, Tujuan Stream |

## Library

| Kebutuhan | Library |
|---|---|
| Encoder + RTMP/RTMPS/SRT/RTSP + pipeline GL | RootEncoder 2.8.2 (pedroSG94, JitPack) |
| Capture card UVC | UVCAndroid 1.0.13 (libuvc) |
| Input jaringan | Media3 ExoPlayer 1.11.0 (+ RTSP, RTMP) |
| Input SRT | srtdroid 1.10.1 |
| STT offline | Vosk 0.3.47 |
| QR code | ZXing core 3.5.3 |

## Catatan penting

- **Belum pernah di-build.** Kode Tahap 2–7 ditulis dan dicek sintaksnya, API library dicocokkan langsung dengan source code versinya, tetapi belum dikompilasi. Build pertama di GitHub Actions kemungkinan butuh beberapa perbaikan kecil.
- **Subtitle dari audio stream** memakai `RecognizerIntent.EXTRA_AUDIO_SOURCE`, hanya tersedia di Android 13+. Di Android 12 ke bawah, mesin Android hanya mendengar mic; pakai Vosk untuk menerjemahkan audio stream.
- **Model Vosk Bahasa Indonesia** belum ada di daftar resmi Vosk. Aplikasi menyediakan model Inggris + kolom URL .zip untuk model lain.
- **Foreground service** memakai tipe `camera|microphone`. Tipe `mediaProjection` sengaja tidak dipakai: YukLive tidak merekam layar, dan mendeklarasikannya tanpa izin proyeksi membuat service crash di Android 14+.
- **TikTok & Instagram** memberi server URL baru tiap sesi; salin dari Live Producer / Live Studio sebelum siaran.

## Build lewat GitHub Actions

Setiap push ke `main`, tag `v*`, pull request, atau tombol **Run workflow** menghasilkan artifact `YukLive-debug-N` dan `YukLive-release-N`.

### Signing release (GitHub Secrets)

1. Buat keystore sekali saja (jangan di-commit):
   ```bash
   keytool -genkeypair -v -keystore yuklive-release.jks -alias yuklive \
     -keyalg RSA -keysize 4096 -validity 10000
   ```
2. Ubah jadi base64:
   ```bash
   base64 -w 0 yuklive-release.jks > keystore.b64      # Linux
   base64 -i yuklive-release.jks -o keystore.b64       # macOS
   ```
3. GitHub → **Settings → Secrets and variables → Actions**, isi `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

Tanpa secret, APK release tetap dibangun tapi tidak ditandatangani.

## Build lokal

JDK 17 + Android SDK (platform 37): `./gradlew assembleDebug`.
Untuk release lokal, buat `keystore.properties` (sudah di `.gitignore`) berisi `storeFile`, `storePassword`, `keyAlias`, `keyPassword`.

## Lisensi pihak ketiga

Font Host Grotesk, Poppins, Montserrat, Bebas Neue, Anton: SIL Open Font License 1.1 (lihat `licenses/`). Library: Apache 2.0.
