# NC Collectives — Android

An unofficial native Android client for the [Nextcloud Collectives](https://github.com/nextcloud/collectives) app — wiki-style markdown notebooks hosted on your own Nextcloud server.

> **100 % AI-written.** Every line of source, every test, every CI workflow, this README, and almost every commit message in this repository was written by [Claude Code](https://www.anthropic.com/claude-code) under direction from a human reviewer. No code in this repository was hand-typed.

> **Unofficial.** This project is not affiliated with, endorsed by, or supported by Nextcloud GmbH or the Nextcloud Collectives team. "Nextcloud" and "Collectives" are trademarks of their respective owners.

## Status

**v0.2.1** — hotfix on top of v0.2.0. `PageDto.tags` is now deserialised as a list of tag IDs (the server's actual shape) and the names are resolved client-side from the per-collective tag list. Without this, opening a collective whose pages have any tags applied failed with a JSON-parse error. v0.2.0 itself shipped settings, backlinks, tag creation, folder rename/move, undo on trash, OCS migration of all page CRUD (create / rename / move / list-attachments / delete-attachment / tag creation), real Room migrations replacing the destructive fallback, and the full Batch 17 audit fix-up list. APK is attached to the [Releases](https://github.com/megamaced/nc_collectives_android/releases) page.

Releases are currently **debug builds only**: in-place upgrades from v0.1.x require an uninstall first (signing-key change). Subsequent v0.2.x debug builds upgrade in place. Signed release builds land in a later milestone.

Tested only by the human reviewer against a personal Nextcloud instance. Expect rough edges; please file issues for anything that breaks.

## Goals

A focused, mobile-first companion to the Collectives web app:

- **Read** your collectives and pages comfortably on a phone, online or offline.
- **Quick capture** via Android's share sheet — send a URL, snippet, or image to a new or existing page.
- **Light editing** with a raw markdown editor and live preview toggle. No realtime collaborative editing, no rich WYSIWYG.

Explicitly **not** a clone of the web experience. If you need full editor parity, use the browser.

## Planned features

- Browse collectives and nested page trees
- Render markdown pages (with images, links, task lists, tables)
- View-first by default; per-page edit toggle into a raw markdown editor
- Offline read cache and offline edit queue (last-write-wins on conflict; local edits preserved as drafts)
- Full-text search via Nextcloud unified search
- Favorites, recent pages, tags, emoji, rename, move
- Attachments: view embedded images; upload from camera or gallery
- Trash & restore
- Share-intent quick capture (text, URLs, images)
- Material 3 / Material You theming with dynamic colour on Android 12+

## Requirements (planned)

- Android 10 (API 29) or newer
- A Nextcloud instance with the [Collectives app](https://apps.nextcloud.com/apps/collectives) installed and accessible to your account

## Authentication

Login uses the standard Nextcloud [Login Flow v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2). You provide your server URL and authorise the app from your browser. The app stores only the device-scoped app password returned by your server — your account password is never seen, transmitted, or stored.

## Privacy & security

- The app talks **only** to the Nextcloud server you configure. There are no analytics endpoints, no telemetry, no crash reporters, no third-party SDKs that phone home.
- No Google Play Services dependencies; no Firebase; no advertising IDs.
- The device-scoped app password is stored in encrypted shared preferences.
- The app password is revocable from your Nextcloud security settings at any time.
- Network requests trust the system certificate store. There is no certificate pinning — if your Nextcloud server is behind a self-signed CA you'll need to install that CA on your device.

## Distribution

F-Droid + sideload only. The build intentionally avoids any Google Play Services dependencies so it can be published through F-Droid and installed on de-Googled devices.

## Tech stack (planned)

- Kotlin 2.x + Jetpack Compose + Material 3
- Hilt for dependency injection
- Retrofit 2 + OkHttp 5 + kotlinx.serialization
- Room 2.7 for offline cache and edit queue
- Coil 3 for image loading (sharing the authenticated OkHttp client)
- CameraX for in-app photo capture
- WorkManager for background sync and queued-edit flush
- Tink for encrypted credential storage

## Building

```
./gradlew assembleDebug
```

Debug APK will be written to `app/build/outputs/apk/debug/` once the project is scaffolded.

## Contributing

This project is a personal AI-driven experiment. Issues are welcome for discussion, but pull requests are unlikely to be merged — the value of the experiment is in shipping a working app entirely through AI authorship.

## License

[AGPL-3.0-or-later](LICENSE) — same family as Nextcloud server and the Collectives app.
