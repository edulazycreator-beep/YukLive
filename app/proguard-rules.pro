# Aturan R8 khusus YukLive.
# Compose, Hilt, dan AndroidX sudah membawa consumer rules sendiri.
# Aturan untuk RootEncoder, UVC, dan Vosk ditambahkan di tahap berikutnya.

# Pertahankan nomor baris di stack trace supaya laporan crash mudah dibaca.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Library dengan kode native / JNI: jangan di-obfuscate ---
-keep class com.pedro.** { *; }
-keep class com.serenegiant.** { *; }
-keep class com.herohan.** { *; }
-keep class io.github.thibaultbee.srtdroid.** { *; }
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class net.butterflytv.rtmp_client.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
-dontwarn org.vosk.**
