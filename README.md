# 🐳 DeepSeek Widget — One-Tap AI Access

> Android home screen widget for instant access to DeepSeek — chat, voice, and camera.

[![GitHub Release](https://img.shields.io/github/v/release/Haidegger22/deepseek-widget-build?style=for-the-badge&logo=android&color=00D4AA)](https://github.com/Haidegger22/deepseek-widget-build/releases)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?style=flat&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple?style=flat&logo=kotlin)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/License-MIT-yellow?style=flat)](LICENSE)

---

## 📱 Download & Install

[![Download APK](https://img.shields.io/badge/Download-Latest%20APK-blue?style=for-the-badge&logo=github)](https://github.com/Haidegger22/deepseek-widget-build/releases/latest)

1. Download `app-release.apk` from the [Releases page](https://github.com/Haidegger22/deepseek-widget-build/releases/latest)
2. Enable **Install unknown apps** for your browser/file manager in device settings
3. Open the APK and install
4. Long-press your home screen → **Widgets** → **DeepSeek Widget**

> ✅ Latest version includes a permanent signing key — future updates install
> over the previous version without uninstalling.

---

## 🚀 Features

| Feature | Description |
|---|---|
| 🏠 **Instant Chat** | One tap opens DeepSeek directly to chat — no unlock, no navigation. |
| 🎤 **Voice** | Mic button opens the DeepSeek app with its **built-in voice recording** — no system speech recognizer required. |
| 📷 **Camera-to-Chat** | Snap a photo from the home screen and send it straight to DeepSeek for visual analysis. |
| 🎨 **Custom Design** | 40% transparent background with the DeepSeek whale logo, black icons with teal neon outline. |
| 📦 **Ultra-Lightweight** | R8-optimized. ~1.6 MB APK, zero background services, zero battery drain. |
| 🕊️ **Privacy First** | No data collected. Widget is a pure router to the official DeepSeek app. |

---

## 🧠 How It Works

The widget is a **"Capture and Route"** architecture — it opens the official
DeepSeek app directly or shares content into it from a home-screen widget:

```
Widget tap
    ↓
PendingIntent → VoiceInputActivity (transparent, no UI)
    ↓
  [Chat tap]          [Mic tap]                    [Camera tap]
  Open DeepSeek       Open DeepSeek voice          Camera intent + FileProvider
  (deep link)         (built-in recording)         → JPEG saved to scoped storage
                           ↓                               ↓
                      DeepSeek voice UI             ACTION_SEND (image/jpeg)
                           ↓                               ↓
                      DeepSeek chat composer ←────────────┘
```

- **Voice** uses a deep link (`chat.deepseek.com/chat?action=voice`) into the
  DeepSeek app — its built-in voice recording works without Google services
  or any external speech-to-text.
- `FileProvider` ensures camera images are shared securely without exposing raw file paths
- `PendingIntent` flags are set to `FLAG_IMMUTABLE` for Android 12+ compliance
- Each widget instance uses unique request codes so multiple placed widgets never conflict

---

## 🛠️ Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin 2.0 |
| Architecture | Trampoline Activity + Intent Routing |
| Security | FileProvider (Scoped Storage) |
| UI | XML RemoteViews + Material Components |
| Minimum SDK | Android 8.0 (API 26) |
| Target SDK | Android 15 (API 35) |
| Build | Gradle KTS + R8 shrinking, CI via GitHub Actions |

---

## 📂 Project Structure

> **Note:** The package name `com.yourdomain.deepseekwidget` is intentionally
> generic to make forking straightforward.

```
deepseek-widget-build/
├── .github/workflows/
│   └── build-apk.yml        ← CI: builds debug+release APK on push to build-apk
├── app/src/main/
│   ├── java/com/yourdomain/deepseekwidget/
│   │   ├── Constants.kt              ← Package IDs, deep link URIs, intent extras
│   │   ├── DeepSeekWidgetProvider.kt ← Widget lifecycle, RemoteViews, PendingIntents
│   │   └── VoiceInputActivity.kt     ← Camera capture + voice routing logic
│   └── res/
│       ├── drawable/                 ← Neon icons, background shape, whale vector
│       ├── drawable-nodpi/           ← Official DeepSeek logo (transparent PNG)
│       ├── layout/deepseek_widget.xml
│       ├── values/                   ← colors, strings, themes
│       └── xml/
│           ├── deepseek_widget_info.xml  ← Widget metadata (size, update period)
│           └── file_paths.xml            ← FileProvider path config
├── keystore/                ← Permanent signing key (same for all CI builds)
├── build.gradle.kts
└── README.md
```

---

## ⚡ Build Locally

```bash
git clone https://github.com/Haidegger22/deepseek-widget-build.git
cd deepseek-widget-build
git checkout build-apk
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

---

## 📄 License

MIT — free to use, modify, and distribute with attribution.

> *Independent open-source project. Not affiliated with or endorsed by DeepSeek.*
> *Forked from [rajit2004/DeepSeekWidget](https://github.com/rajit2004/DeepSeekWidget).*
