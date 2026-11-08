# How Lumen works

A tour of the code for anyone who wants to read it, check it, or build on it. Paths are relative to
`app/src/main/java/app/lumen/`.

## The big picture

```
 You ──▶ UI (Compose)  ──▶ PlayerConnection ──▶ PlaybackService (Media3 / ExoPlayer)
            │                                         │
            ▼                                         ▼
      MusicRepository ──▶ sources: YouTube · SoundCloud · JioSaavn · phone · lyrics
            │
            ▼
      LibraryStore (your library, on the phone)   TasteEngine (your taste, on the phone)
```

Everything runs inside the app process. There is no Lumen server: the only network requests go to
the music services themselves (and LRCLIB for lyrics).

## Startup — `LumenApp.kt`

`LumenApp.onCreate` builds the app's singletons in `Graph`: the library store, taste engine, music
repository, downloads, player connection and home feed. `Guardian.install` comes first, so a crash
anywhere after it is recorded and a crash loop can heal itself on the next launch.

## Your library — `data/LibraryStore.kt`

Likes, playlists, saved albums, history, searches, downloads and settings live in one
`LibraryState`, saved as `library.json` in the app's private storage.

- Writes are debounced and go through `SafeFile` (`data/SafeFile.kt`): write a temp file, `fsync`,
  rename. A power cut leaves either the old file or the new one, never half of one.
- An hourly and a daily copy are kept; a file that fails to read is set aside and the newest good
  copy is used.
- The app saves immediately when it goes to the background.

## Finding music — `data/MusicRepository.kt` and `data/source/`

- `NewPipeSource` reads YouTube and SoundCloud through NewPipe Extractor.
- `SaavnSource` talks to JioSaavn's public web API. Its stream links arrive encrypted with a fixed
  public key; decrypting them is how every client reads them. Decrypted links must point at
  JioSaavn's own CDN or they are rejected.
- `LocalSource` reads songs on the phone through Android's MediaStore.
- `LyricsSource` fetches synced lyrics from LRCLIB.
- `MusicRepository.search` asks every source at once and shows results as each one answers;
  `merge` ranks them together and folds near-duplicates into the best copy.
- `resolve` turns a song into a playable stream link. Links are cached (on disk too, until shortly
  before they expire), lookups for the same song are shared, and a song that fails on one source is
  looked up on another.
- `engine/SourceHealth` is a circuit breaker: a source that keeps failing is skipped for a while.

## Playing — `playback/`

- `PlaybackService` owns the ExoPlayer and the media session (notification, lock screen,
  headphones, Bluetooth). Streams go through a resolving data source and a disk cache.
- Queue rules live in `PlaybackService.enqueue`: *Play next* inserts right after the current song;
  *Add to queue* inserts after the current song and any songs you queued earlier, ahead of the rest
  of the list. With shuffle on, the shuffle order is adjusted so queued songs keep their place.
- `extendWithRadio` adds similar songs when the queue runs out (autoplay) or for *Start radio*, and
  drops the result if you played something else in the meantime.
- `PlayerConnection` is the UI's side: a `MediaController` exposed as state flows.
- `MediaItems.kt` turns a song into a media item; downloaded songs play straight from their file.

## Downloads — `playback/Downloads.kt`, `playback/DownloadService.kt`

Three downloads run at a time, each in 2 MB range requests with three retries and a fresh link per
retry. The waiting list is saved to `downloads_pending.json`, so downloads continue after the app
is closed. `DownloadService` shows a progress notification while anything is downloading.

## Sharing and backups — `data/PlaylistShare.kt`, `data/LibraryBackup.kt`

- A share code (`lumen:pl1:…`) is the playlist as compact JSON, deflated and base64url-encoded.
  Decoding treats codes as hostile: size caps, inflation limit, strict checks on every id, and stream
  links rebuilt from ids rather than trusted.
- A backup is the library as JSON. Restoring merges into what is there and never deletes. Every
  song in a backup passes the same checks as a share code.

## Your taste and notifications — `engine/`

- `TasteEngine` keeps scores for artists, genres and topics from what you play fully, skip, like
  and search. Scores fade over three weeks. Saved as `taste.json`; never sent anywhere.
- `Notifier` posts at most two notifications a day (a day slot and an evening slot), each a
  different kind from the last few, and only songs you haven't played.
- `LumenJobService` runs this, plus storage upkeep, about every 90 minutes via JobScheduler.
- `StorageGuard` sizes the caches from free space and cleans leftovers.

## Studio — `studio/`

`StudioEngine` plays the backing track and records the microphone with its own AudioTrack and
AudioRecord pair, measures the round-trip delay, and mixes voice and music through the vocal chain
in `Dsp.kt` (EQ, compression, reverb, echo). `AudioCodec.kt` decodes songs to PCM and encodes the
final mix to AAC.

## Interface — `ui/`

Jetpack Compose, one screen per file in `ui/screens/`, the player in `ui/player/`, shared pieces in
`ui/components/`. `ui/theme/` holds the colours, the Inter and Source Serif type scale, and the icon set.

## Tests — `app/src/test/`

`PlaylistShareTest` covers share codes (round trip, size, hostile input) and backups (sanitising,
merging without deleting). Run them with `./gradlew :app:testDebugUnitTest`.
