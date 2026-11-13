# StreamBox TV

An IPTV **player** for Android TV (also runs on phones, tablets and Fire TV), written in Kotlin with Media3/ExoPlayer.
It contains no channels or content. You add the Xtream Codes login or M3U playlist URL from your own IPTV provider.

## Features

| Area | What it does |
|---|---|
| Profiles | **Add from your phone by scanning a QR code** (phone and TV on the same Wi-Fi), multiple profiles, Xtream Codes API login or M3U URL (+ optional XMLTV EPG URL), account status / expiry / connections |
| Live TV | Categories, channel list with logos, search, favourites, recently watched, in-list preview, full-screen zapping (▲▼ / CH±), auto-reconnect |
| TV guide | Now/next overlay in the player, per-channel guide; Xtream short EPG or XMLTV (plain or .gz) for M3U |
| Catch-up | Past programmes of archive channels play through the Xtream timeshift API |
| Movies | Poster grid, search, favourites, details (plot, cast, rating), resume from where you stopped |
| Series | Seasons and episodes, resume markers, auto-play next episode |
| Player | HLS / MPEG-TS / DASH / MP4 / MKV, audio-track and subtitle selection, aspect ratio, playback speed, optional external player (VLC, MX…) |
| Parental control | PIN; adult-named categories lock automatically, any category can be locked (hold OK on it) |
| Settings | Stream format (.ts / .m3u8), time format, EPG time shift, refresh interval, custom User-Agent, clear cache |

## Build

1. Install **Android Studio** (Koala 2024.1 or newer; it bundles JDK 17).
2. *File → Open* and pick this folder. Let Gradle sync (first sync downloads Gradle 8.7 and the libraries).
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The file lands in `app/build/outputs/apk/debug/app-debug.apk`.

Command line alternative: `./gradlew assembleDebug` (needs JDK 17 and the Android SDK; set `sdk.dir` in `local.properties`).

## Install on a TV

- Enable *Developer options → USB/Network debugging* on the TV, then `adb connect <tv-ip>` and `adb install app-debug.apk`.
- Or copy the APK to a USB stick / use the "Send files to TV" app and open it with a file manager.

## Remote control

| Screen | Keys |
|---|---|
| Lists | OK = open. **Hold OK** = menu (favourite, guide, lock category, delete profile) |
| Live TV list | First OK previews the channel, second OK goes full screen (can be turned off in Settings) |
| Live player | ▲▼ or CH± change channel, OK shows info, ◀ ▶ or MENU open audio / subtitles / aspect ratio |
| Movie player | OK shows the transport bar (seek, pause, gear icon), MENU opens options, Back hides the bar / exits |

## Project layout

```
app/src/main/java/com/streambox/tv/
  data/   Models, Prefs (settings, favourites, PIN), Net (OkHttp), Repo (Xtream API, catalogue, EPG, catch-up),
          M3uParser, XmltvParser, JsonStream (streams huge channel lists without loading them in memory)
  ui/     Profiles, AddProfile, Home, Live, Browse (movies/series), MovieDetail, SeriesDetail, Epg, Settings, Player
```

Rename the app or package: change `app_name` in `res/values/strings.xml`, and `applicationId` in `app/build.gradle.kts`.

## Known limits of this first version

- M3U playlists give Live TV and Movies; series folders, catch-up and account info need an Xtream login.
- The guide is per channel (no multi-channel grid yet).
- No recording, no multi-screen, no Chromecast, no cloud sync of profiles.
- Before publishing on Google Play: add your own icon/banner artwork, a release signing key, and a privacy policy.
