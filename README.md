# OpenPlay

A free, open-source, ad-free Android video player built on Jetpack Media3 (ExoPlayer) and Jetpack Compose.

## Features (v0.6)
- Plays almost any video: Media3 with FFmpeg software decoders (AC3, E-AC3, DTS, TrueHD audio; HEVC, VP9, ... as backup)
- Decoder modes in Settings: HW (phone only), HW+ (default, software if needed), SW (FFmpeg first)
- Modern home screen: bottom navigation, search, "Continue watching", thumbnails, sorting, list/grid
- Player: thin timeline, controls under it, double-tap ±10 s, hold for 2x, brightness/volume/seek gestures,
  audio and subtitle menus, playlist, orientation lock, Picture-in-Picture, background play
- Long-press menus for videos and folders (rename, details, share, move, copy, private, delete)
- Private folder with 5-digit PIN (stored inside the app; deleted if the app is uninstalled)
- 12 languages: English, Spanish, Arabic, Hindi, Bengali, Portuguese, Russian, Japanese, French, German, Indonesian, Chinese
- Light/dark/system theme and saved playback defaults

## Not supported
AVI, WMV and RMVB containers (ExoPlayer cannot read them).

## Build
Open in Android Studio (Koala or newer), or push to GitHub: the "Build APK" workflow produces release APKs
(arm64-v8a and armeabi-v7a) as the `OpenPlay-release-apk` artifact. Install the arm64-v8a file on modern phones.

`openplay.keystore` is a **test signing key committed on purpose** so builds update over each other.
Create your own private key before publishing to any store.

## License
GPL-3.0-or-later (required because the FFmpeg decoder library NextLib is GPL-3.0). See LICENSE.
