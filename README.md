<p align="center"><img src="branding/lumen-logo.svg" width="96" alt="Lumen logo"></p>

<h1 align="center">Lumen</h1>

<p align="center">
A free Android music app: YouTube Music, JioSaavn and SoundCloud in one place.<br>
No ads · No account · No tracking · No server.
</p>

<p align="center">
<a href="https://lumen.arivna.com">Website</a> ·
<a href="https://github.com/viroaryan/lumen-releases/releases/latest">Download</a> ·
<a href="https://lumen.arivna.com/updates">Changelog</a> ·
<a href="docs/ARCHITECTURE.md">How the code works</a>
</p>

---

## Why this code is public

Lumen asks you to trust it with your music, your library and a few permissions. The honest way to earn
that is to let anyone read exactly what it does. Everything that runs on your phone is in this
repository: there is no hidden server, no analytics SDK, no ad library, and nothing is downloaded and
run later. If you can read Kotlin, you can check every claim on the website yourself.

## What Lumen does

- **One search, three catalogues.** YouTube Music, JioSaavn and SoundCloud, ranked together.
- **Offline downloads.** Any number of songs, resumed after the app closes, retried on failure.
- **A queue that works like you expect.** Play next, Add to queue (after your earlier queued songs,
  ahead of the rest), radio and autoplay.
- **Synced lyrics**, sleep timer, speed, picture-in-picture, skip silence.
- **Share a playlist as a code.** The whole playlist is packed into text you can send on WhatsApp.
- **Backup and restore** your library as a single file.
- **Studio karaoke.** Record over a song, mix with reverb and echo, export the result.
- **A home feed from your own taste**, built on the phone, plus two picks a day as notifications.
- **Podcasts, lectures and talks** from YouTube, resumable.

## Build it yourself

You need JDK 21 and the Android SDK (platform 37, build-tools 37).

```bash
git clone https://github.com/viroaryan/lumen-code.git
cd lumen-code
./gradlew :app:assembleDebug        # Windows: gradlew.bat :app:assembleDebug
./gradlew :app:testDebugUnitTest    # run the unit tests
```

The APK appears in `app/build/outputs/apk/debug/`. Install it with `adb install -r <apk>`.

Your own build is signed with your own debug key, so Android installs it as a separate app next
to (or instead of) the official one; it cannot update an official install. That is Android's
protection against anyone impersonating an app, and it is also how you can tell official builds apart.

### Is the official APK the same code?

Official releases are built from this repository and signed with Nirvana Team's release key. The key's
SHA-256 fingerprint is

```
45:54:74:af:16:61:e7:21:da:f1:2a:5f:a4:a6:89:d8:26:8b:60:84:5f:87:e9:57:c1:45:9c:23:cf:d9:c0:13
```

Check any APK with `apksigner verify --print-certs Lumen-x.y.apk`. Each release on
[lumen-releases](https://github.com/viroaryan/lumen-releases/releases) also lists the APK's own SHA-256.

## Project layout

```
app/src/main/java/app/lumen/
  data/        library, search, sources (YouTube, SoundCloud, JioSaavn, phone files, lyrics),
               playlist share codes, backups, crash-safe file writes
  playback/    the player service, queue rules, downloads
  engine/      taste profile, notifications, storage upkeep, crash guard, source health
  studio/      karaoke recording, audio effects, encoding
  ui/          Jetpack Compose screens and components
app/src/test/  unit tests
branding/      the Lumen logo (trademark, see ADDITIONAL-TERMS.md)
```

A guided tour of how these fit together is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Privacy, in code

| Claim | Where to check |
|---|---|
| Library stays on the phone | `data/LibraryStore.kt` (writes only to the app's own files) |
| No analytics or ads | `app/build.gradle.kts` (the complete dependency list) |
| Only music sites are contacted | `data/source/` (YouTube, SoundCloud, JioSaavn, LRCLIB) |
| Backups include only the library | `res/xml/backup_rules.xml`, `res/xml/data_extraction_rules.xml` |
| Permissions and why | `AndroidManifest.xml` and [lumen.arivna.com/privacy](https://lumen.arivna.com/privacy) |

## License

Copyright (C) 2026 Nirvana Team.

Lumen is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License version 3** or (at your option) any later version, with the
**additional terms in [ADDITIONAL-TERMS.md](ADDITIONAL-TERMS.md)**. In short:

- You may use, study, change and share Lumen.
- If you share a version of it, you must share its full source code under the same license.
- You must keep the copyright notices and credit **"Based on Lumen by Nirvana Team"**.
- You must use your own name and icon: **Lumen** and **Nirvana Team** names and the logo are trademarks.

Lumen is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the
implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See [LICENSE](LICENSE).

Third-party components and their licenses: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
Lumen is not affiliated with YouTube, Google, JioSaavn or SoundCloud.

## Contributing and security

Bug reports and pull requests are welcome; see [CONTRIBUTING.md](CONTRIBUTING.md).
To report a security problem privately, see [SECURITY.md](SECURITY.md).
