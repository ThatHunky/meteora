# Meteora

An Android app that remote-controls [YTMDesktop](https://github.com/ytmdesktop/ytmdesktop) on a PC and can also play YouTube Music on the phone itself. Kotlin and Compose with Material 3 Expressive.

This is a temporary README. Personal project, sideload only, GPL‑3.0.

Meteora is an independent app. It is not affiliated with YTMDesktop, YouTube or Google.

## Build

Android SDK with compileSdk 37.2 and JDK 25. On Windows, from PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/`. arm64 phones only, Android 12 or newer.

## More

- [PLAN.md](PLAN.md): decisions and milestones
- [docs/devlog.md](docs/devlog.md): how it was built, in order
- [docs/api-notes.md](docs/api-notes.md): what was verified about the YTMDesktop and YouTube Music APIs
- [CLAUDE.md](CLAUDE.md): notes for AI coding agents
