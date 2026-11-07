# YT Player

A personal Android player for videos downloaded from YouTube (currently via
PipePipe). Built on Media3/ExoPlayer with the feature set aggressively stripped
down to one job: work through a daily queue of downloaded videos, often
listened to podcast-style.

Design notes:

- **One player, three modes.** A single ExoPlayer lives in a
  `MediaSessionService`. The video screen, picture-in-picture, and
  audio-background playback are the same session — position, speed, state and
  queue carry over with no re-prepare when switching modes. Audio-only mode
  just disables the video track on the same player.
- **Logarithmic speed control.** Speed is an integer *level*; the rate is
  `2^(level/12)` clamped to **0.8x–2.8x** (level −4…18, level 0 = 1.0x). Equal
  level steps are equal speed ratios. Buttons step one level, the slider is
  linear in level, the label shows the effective rate.
- **SQLite library.** All library and playback state lives in a plain SQLite
  database: title, channel, source (local file / stream), status
  (unplayed / in progress / played), resume position, play counts, dates.
  Deletion is always a manual, user-selected action — nothing is deleted
  automatically or by a pre-set rule. Deleted entries are tombstoned.
- **Sources are unified.** Local files and stream URLs are the same kind of
  row; only the "locator" differs, and resolution to a playable URI happens
  at play start behind a small `Resolver` class.

## Install

1. Download the `app-debug.apk` from the latest GitHub Actions run
   (artifact name `yt-player-debug-apk`).
2. Copy it to the phone and install it (allow "install unknown apps" for the
   file manager/browser you open it with).
3. Open **YT Player**. Allow the notification permission when asked (needed
   for the playback notification / lock-screen controls).

## Add the PipePipe download folder

1. Tap **Add folder** and pick PipePipe's download folder in the system
   picker. Grant access (read/write is requested so deleting a file from
   inside the app works later).
2. The app scans the folder (including subfolders) for
   `mp4 / mkv / webm / m4a / mp3 / opus` files and registers them. Titles come
   from file names; durations are probed where possible and otherwise learned
   on first playback.
3. Tap a row to play it: the queue becomes the currently filtered list in
   display order, starting at the tapped item. Playback auto-advances through
   the queue, marks items played at the end (or past 90%), and saves resume
   positions to the database every few seconds.

## Streams

**Add stream** registers a direct stream URL as a library item (badge:
STREAM). It plays through exactly the same queue, speed, PiP and background
machinery as local files. This is the slot the future yt-dlp backend app will
feed — for v0.1 the stored URL is used as-is.

## Player screen

Play/pause, seek bar, −10s / +30s, prev/next, speed − / + and level slider,
**PiP** button (PiP also engages automatically when you leave the app while a
video plays), and an **Audio only** toggle for podcast-style listening with
the screen off. Lock-screen and headset controls come from the media session
notification.

## Multi-select delete

Long-press a library row to enter selection mode, tap more rows, then
**Delete selected**. Local files are deleted via the document provider where
possible; entries are hidden (tombstoned) either way.

## NOT in v0.1

- yt-dlp backend integration (no resolve endpoint yet — stream items use
  their stored URL directly)
- Subtitles / captions, multiple audio tracks
- DASH / HLS / adaptive streaming of any kind (progressive playback only)
- Downloads of any kind (downloading stays in PipePipe / the future backend)
- Cast, DRM, ads, shuffle/repeat modes, accounts, sharing

## Build

Gradle cannot run on the maintainer's VM, so builds happen in GitHub
Actions: every push to `main` runs `./gradlew assembleDebug` and uploads the
APK as the `yt-player-debug-apk` artifact.
