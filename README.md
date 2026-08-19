<div align="center">

<img src=".github/assets/logo.jpg" alt="Yaku Manga" width="160">

# Yaku Manga

### A manga reader that translates on your device, not on someone's server

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](/LICENSE)
[![Fork of Mihon](https://img.shields.io/badge/fork%20of-Mihon-27303D?labelColor=27303D)](https://github.com/mihonapp/mihon)

*Requires Android 8.0 or higher.*

</div>

## What this is

Yaku Manga is a fork of [Mihon](https://github.com/mihonapp/mihon) built around one addition:
**machine translation that runs entirely on the phone.** Pages are detected, read and translated
by ONNX models stored in the app's private directory. No page image, no recognised text and no
translation ever leaves the device.

The rest of the app is Mihon — its library, sources, downloads, trackers and reader are unchanged
in behaviour, and the extension API is deliberately untouched so existing extensions keep working.

## How translation works

```
page bitmap ─▶ text detection ─▶ text recognition ─▶ translation ─▶ text drawn onto the page
               (DBNet-class)      (manga-ocr-class)   (NLLB / opus-MT)
```

Every stage is an ONNX graph executed locally by ONNX Runtime. The translated text is composited
into the page bitmap before the viewer ever sees it, so zoom, pan, double-page splitting and
border cropping all keep working without knowing translation happened.

### Models are not bundled

The APK ships with no weights. A usable pack is a few hundred megabytes, and which one is right
depends on the language pair you read, so packs are downloaded on request and verified by SHA-256
before use. Point the app at a manifest URL in settings, or drop a pack directly into the app's
files directory to keep it fully offline. See [`docs/translation-packs.md`](docs/translation-packs.md)
for the manifest format.

### Privacy posture

* No telemetry in a default build. Crash reporting is a compile-time opt-in (`-Pinclude-telemetry`)
  and is additionally gated on a signing certificate, so unofficial builds never report.
* No network access for translation. Model downloads are the only requests the feature ever makes,
  and only when you ask for one.
* Models live in app-private storage and are removed when the app is uninstalled.

## Features inherited from Mihon

* Local reading of content.
* A configurable reader with multiple viewers, reading directions and other settings.
* Tracker support: [MangaBaka](https://mangabaka.org), [MyAnimeList](https://myanimelist.net/),
  [AniList](https://anilist.co/), [Kitsu](https://kitsu.app/), [MangaUpdates](https://mangaupdates.com),
  [Shikimori](https://shikimori.one), [Bangumi](https://bgm.tv/), and [Hikka](https://hikka.io/).
* Categories to organize your library.
* Light and dark themes.
* Scheduled library updates.
* Local and cloud backups.

## Relationship to Mihon

This is an independent fork and is **not affiliated with, endorsed by, or supported by the Mihon
project**. Please do not report Yaku Manga issues to Mihon, and do not ask for support in Mihon's
Discord.

Yaku Manga is distributed under the Apache License 2.0, the same licence as its upstream. As
required by section 4(b) of that licence, the notable changes made to the original work are:

* All application package namespaces renamed from `eu.kanade.*`, `mihon.*` and `tachiyomi.*` to
  `yaku.*`; application id changed to `app.yaku`.
* Application name, branding and update endpoints changed.
* Added the `:translation` module and its integration into the reader page pipeline.
* Telemetry package/certificate allowlist retargeted away from Mihon's.

**Not renamed, on purpose:** `eu.kanade.tachiyomi.source`, `eu.kanade.tachiyomi.network`,
`eu.kanade.tachiyomi.util` and the `tachiyomi.extension*` manifest metadata keys. Extensions are
separate APKs compiled against those exact names and resolve them from the host app's classloader —
renaming them would break every existing extension.

## Building

```bash
./gradlew assembleDebug
```

Requires JDK 21 and the Android SDK (compileSdk 37). Optional Gradle properties:

| Property | Effect |
|---|---|
| `-Pinclude-telemetry` | Compiles in Firebase Crashlytics. Off by default. |
| `-Penable-updater` | Compiles in the in-app updater. Off by default. |

## Credits

Yaku Manga exists because of the work of the Mihon contributors and, before them, Tachiyomi's.

<a href="https://github.com/mihonapp/mihon/graphs/contributors">
    <img src="https://contrib.rocks/image?repo=mihonapp/mihon" alt="Mihon app contributors" title="Mihon app contributors" width="800"/>
</a>

## Disclaimer

The developers of this application have no affiliation with the content providers available, and
this application hosts zero content.

## License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project
Copyright © 2026 Yaku Manga contributors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
