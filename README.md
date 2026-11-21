# Meteora

A YouTube Music app for Android that I made for myself.

It started as a remote for [YTMDesktop](https://github.com/ytmdesktop/ytmdesktop) on my PC. Then it learned to play music on the phone too)

Not affiliated with YTMDesktop, YouTube or Google. It's a personal project.

## What it does

**Remote for the PC.** Pair it once with YTMDesktop (companion server on, same network or Tailscale) and you get play, pause, seek, volume, queue, likes. Volume keys on the phone change the volume on the PC.

**Plays on the phone.** Same screen, one switch at the top: PC or phone. Home, search, albums, artists, playlists, radio, your library if you sign in.

**Sound quality.** Low, Normal or High, separately for Wi‑Fi and mobile data. High is 256 kbps and needs Premium, same as in the official app.

**Music videos.** Song / Video switch in the player, full screen, quality up to 1080p. There are stats for nerds too, because why not.

**Looks.** Not just colours. Five styles that change fonts, shapes and controls: Expressive, Terminal, Flat, Retro, Soft. Terminal is all monospace with `[ next ]` buttons and a `████░░░░` seek bar. The Mono theme was asked for by N7Art, thanks for the idea.

English and Ukrainian.

## Things to know before you install

- Sideload only. Playing on the phone is against YouTube's terms, so this will never be on Google Play. Use it at your own risk.
- arm64 phones, Android 12 or newer.
- No downloads and no lyrics yet. Both are planned.
- It's a debug build of a hobby app. Something will break. Tell me when it does.

## Build

You need the Android SDK (compileSdk 37.2), JDK 25, the NDK and Rust with the `aarch64-linux-android` target. A small part of the app is written in Rust and Gradle builds it for you.

On Windows, from PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

The APK is in `app/build/outputs/apk/debug/`.

The toolchain is very new on purpose (AGP 9.5, Kotlin 2.4, Compose alpha). If it doesn't build on your machine, that's probably why.

## How it was made

Almost all the code was written by AI agents, with me deciding what to build and testing it on my phones. I kept the whole story, including what went wrong:

- [docs/devlog.md](docs/devlog.md) is the diary
- [PLAN.md](PLAN.md) has the decisions
- [docs/api-notes.md](docs/api-notes.md) is what we learned about the YTMDesktop and YouTube Music APIs
- [CLAUDE.md](CLAUDE.md) is the note for the agents

## License

GPL‑3.0.
