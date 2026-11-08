# Contributing to Lumen

Thanks for wanting to help.

- **Bugs and ideas:** open an issue with what you did, what you expected, what happened, and your
  Lumen version (Settings, at the bottom) and Android version.
- **Pull requests:** keep each one to a single change, explain why, and run
  `./gradlew :app:testDebugUnitTest` before sending it. Match the style of the surrounding code.
- **New source files** start with the same two-line header as the others:

  ```kotlin
  // SPDX-FileCopyrightText: 2026 Nirvana Team
  // SPDX-License-Identifier: GPL-3.0-or-later
  ```

By sending a contribution you agree that it is licensed under the GPL-3.0-or-later with the
additional terms in `ADDITIONAL-TERMS.md`, like the rest of Lumen.

Please never commit signing keys, `keystore.properties`, `local.properties` or APK files.
