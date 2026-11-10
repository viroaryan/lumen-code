# Third-party software

Lumen is built on open-source work by others. Thank you to everyone behind these projects.

| Component | Used for | License |
|---|---|---|
| [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor) | Reading YouTube and SoundCloud pages | GPL-3.0 |
| [AndroidX Media3 / ExoPlayer](https://github.com/androidx/media) | Playback, media session, notification | Apache-2.0 |
| [Jetpack Compose](https://developer.android.com/jetpack/compose), AndroidX Core, Activity, Lifecycle, Navigation | User interface | Apache-2.0 |
| [OkHttp](https://github.com/square/okhttp) | Networking | Apache-2.0 |
| [Coil](https://github.com/coil-kt/coil) | Loading artwork | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines), [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) | Background work, saving data | Apache-2.0 |
| [Desugar JDK libs](https://github.com/google/desugar_jdk_libs) | Newer Java APIs on older Android | GPL-2.0 with Classpath Exception |
| [Inter](https://github.com/rsms/inter) font | Interface text | SIL OFL 1.1 (see `LICENSES/OFL-1.1-fonts.txt`) |
| [Source Serif 4](https://github.com/adobe-fonts/source-serif) font | Titles | SIL OFL 1.1 (see `LICENSES/OFL-1.1-fonts.txt`) |
| [SongRec](https://github.com/marin-m/SongRec) (algorithm, ported to Kotlin in `identify/Signature.kt`) | Audio fingerprint for the song finder | GPL-3.0 |
| [JUnit 4](https://junit.org/junit4/) (tests only) | Unit tests | EPL-1.0 |

Lyrics come from [LRCLIB](https://lrclib.net), and from NetEase Cloud Music when LRCLIB has none. Music comes from YouTube, YouTube Music, JioSaavn
and SoundCloud and belongs to its owners; Lumen is not affiliated with any of them. The song finder sends an audio fingerprint to the
recognition service SongRec uses (Shazam); Lumen is not affiliated with it either.
