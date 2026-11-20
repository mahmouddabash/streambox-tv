# StreamBox TV

An IPTV **player** for Android TV (also runs on phones, tablets and Fire TV), written in Kotlin with Media3/ExoPlayer.
It contains no channels or content. You add the Xtream Codes login or M3U playlist URL from your own IPTV provider.

## Features

| Area | What it does |
|---|---|
| Profiles | **Add from your phone by scanning a QR code** (phone and TV on the same Wi-Fi), multiple profiles, Xtream Codes API login or M3U URL (+ optional XMLTV EPG URL), account status / expiry / connections |
| Live TV | Categories, channel list with logos, search, favourites, recently watched, in-list preview, full-screen zapping (▲▼ / CH±), auto-reconnect |
| Programme info | Now/next in the player and on the home screen, per-channel schedule (used for catch-up); Xtream short EPG or XMLTV (plain or .gz) for M3U |
| Catch-up | Past programmes of archive channels play through the Xtream timeshift API |
| Movies | Poster grid, search, favourites, details (plot, cast, rating), resume from where you stopped |
| Series | Seasons and episodes, resume markers, auto-play next episode |
| Picture | **Picture modes** (Enhanced, Vivid, Cinema, Sharp, Original): real-time GPU sharpening, contrast, colour and vibrance; plays the highest-quality stream variant from the start, optional "always highest" |
| Player | HLS / MPEG-TS / DASH / MP4 / MKV, **auto-detects the real format** when a link sends something else (e.g. HLS behind a .ts/.mp4 link) and shows the server's message if it sends an error page, audio-track and subtitle selection, aspect ratio, playback speed, optional external player (VLC, MX…) |
| Parental control | PIN; adult-named categories lock automatically, any category can be locked (hold OK on it) |
| Home | Streaming-service style: animated splash, full-screen hero that follows the focused title (backdrop, rating, year, plot), rows for Continue watching (with progress), recently watched channels, new movies, new series, your series, favourites |
| Look | Dark glass theme with violet → cyan accents, cinematic movie/series pages with backdrops, zoom-on-focus everywhere, rounded dialogs |
| Startup | **Resumes where you left off**: reopens the last profile and the same screen (Live TV, Catch-up, Movies or Series), category and channel / title |
| Settings | Stream format (.ts / .m3u8), open-last-profile toggle, time format, EPG time shift, refresh interval, player identity (User-Agent), clear cache |

## Build

1. Install **Android Studio** (Koala 2024.1 or newer; it bundles JDK 17).
2. *File → Open* and pick this folder. Let Gradle sync (first sync downloads Gradle 8.7 and the libraries).
3. *Build → Build App Bundle(s) / APK(s) → Build APK(s)*. The file lands in `app/build/outputs/apk/debug/app-debug.apk`.

Command line alternative: `./gradlew assembleDebug` (needs JDK 17 and the Android SDK; set `sdk.dir` in `local.properties`).

## Download a ready-made APK

Every push builds the APK and publishes it as a GitHub Release. The newest build is always at:

**https://github.com/mahmouddabash/streambox-tv/releases/latest/download/StreamBoxTV.apk**

Open that link on the TV's browser (or the download bar of a sideload app) to get the APK directly — no unzipping.

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

## What's new

| Feature | How to use it |
|---|---|
| **Update from inside the app** | Checked once a day on the home screen; also **Settings → Check for updates**. One click downloads and installs the new build |
| **Search everything** | 🔍 on the home screen (or the remote's search/mic key): channels, movies and series at once, with **voice search** |
| **Channel numbers** | Type digits on the remote while watching live TV; the **LAST** key (or player menu) returns to the previous channel |
| **Pause & rewind live TV** | ⏯ pauses; on channels with catch-up (⟲) it continues from where you paused, ⏪/⏩ rewind or skip a minute, the menu has *Rewind 5 minutes*, *Watch from the start* and *Back to live* |
| **Series in Continue watching** | Unfinished episodes now appear on the home row next to movies |
| **Multi-screen** | ▦ on the home screen or the player menu: 2 or 4 channels at once, the selected screen plays its sound, OK changes its channel, long-press OK opens it full screen |
| **Reminders** | In a channel's guide, choose an upcoming programme → *Remind me*; a pop-up (or notification) offers *Watch now* when it starts |
| **Recording** | Player menu → *Record*, or a programme in the guide → *Record*. Saves .ts files to the TV or a USB drive (**Settings → Recording location**); ⏺ on the home screen lists, plays and deletes recordings |
| **Trailers & TMDB** | *▶ Trailer* on movie/series pages (opens YouTube). Add a free TMDB key in Settings for HD backdrops, cast photos and more trailers |
| **Subtitles** | Player menu → *Subtitle style* (size, colour, background) and *Find subtitles online* (free OpenSubtitles key in Settings; languages default to Arabic + English) |
| **Arabic interface** | **Settings → Language → العربية** switches the whole interface to Arabic, right-to-left |
| **Theme colours** | **Settings → Theme colour**: Violet, Ocean, Sunset, Emerald, Gold, Rose |
| **Sleep timer** | Player menu → *Sleep timer* (15 min – 2 h) |
| **Picture-in-picture** | Press Home while watching (or player menu → *Picture-in-picture*) to keep it playing in a small window |

## iPhone & iPad version

A native SwiftUI version lives in [`ios/`](ios/) with the same look and features: profiles (Xtream / M3U), home with hero banner and Continue watching, Live TV with now-playing and catch-up, Movies, Series, parental PIN and resume-where-you-left-off. It uses Apple's player (AirPlay and Picture-in-Picture for movies); live channels are played as HLS (.m3u8). Titles in MKV/AVI that the provider can't serve as HLS show an **Open in VLC** button.

Every push to `ios/` builds an unsigned app on GitHub's Mac runners:

**https://github.com/mahmouddabash/streambox-tv/releases/download/ios/StreamBox.ipa**

Install it with [Sideloadly](https://sideloadly.io) or AltStore using your Apple ID (free accounts must re-sign every 7 days).
