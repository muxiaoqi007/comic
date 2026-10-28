# Comic

A native Android comic reader built with Kotlin and Jetpack Compose.

## Features

- **Sources**
  - Edit existing sources without losing reading progress or downloads
  - Local folders via Storage Access Framework (CBZ/ZIP archives and image folders)
  - WebDAV (remote CBZ reading over HTTP range requests)
  - Komga (supports both the legacy GET API and the current `POST /series/list`, `POST /books/list` API)
  - Koobone (email login, volume grouping, on-demand EPUB reading)
- **Library**
  - Physical bookshelf layout with wooden planks and book shadows
  - Continue-reading card
  - Filters: all / reading / unread / finished, by source
  - Series read count (`read 1/3 volumes`)
- **Reader**
  - Left-to-right, right-to-left, and vertical (top-to-bottom) modes
  - Page slider and tap zones
  - Reading progress saved locally; Komga progress synced to the server
- **Downloads**
  - Koobone EPUBs are saved directly to the system `Download/Comic` folder on Android 10+, so they are visible to file managers and system search
  - Per-book download progress, retry, cancel, delete; deleting also removes the public file
  - Older/private downloads can be exported to a user-selected folder through the system file picker

## Build

Requirements: JDK 17, Android SDK 35.

```bash
# Point local.properties at your SDK
echo "sdk.dir=/path/to/android-sdk" > local.properties

./gradlew :app:assembleDebug
```

Release builds read signing settings from a local, git-ignored `keystore.properties`:

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

```bash
./gradlew :app:assembleRelease
```

## Version

Current version: **0.3.6** (versionCode 9). The version is also shown on the Sources screen.

## Notes

- This project is a personal client for sources you are authorized to access. Respect the terms of service of any third-party service you connect.
- Koobone is a third-party subscription service; this app is not affiliated with it.
- The local mock server under `tools/smoke/` is only for development and uses fake credentials.
- No open-source license has been chosen yet.
