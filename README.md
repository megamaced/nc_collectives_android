# NC Collectives — Android

An Android app for [Nextcloud Collectives](https://apps.nextcloud.com/apps/collectives), the wiki-style notebooks on your own Nextcloud server. Read, write and organise your collectives' pages on your phone, and keep reading and writing when you're offline.

> **Unofficial.** Not affiliated with, endorsed by or supported by Nextcloud GmbH or the Collectives team. "Nextcloud" and "Collectives" are trademarks of their respective owners.

> **100 % AI-written.** Every line of code, every test and this README were written by [Claude Code](https://www.anthropic.com/claude-code), directed and reviewed by a human.

## Screenshots

The app's screens, rendered with sample data.

<p align="center">
  <img src="docs/screenshots/01-home.png" alt="Home: new alerts, then cards for pages to jump back into, pages updated since you read them, and recently updated pages filtered by collective" width="260" />
  <img src="docs/screenshots/02-collective.png" alt="Inside a collective: its members, landing page and nested page tree" width="260" />
  <img src="docs/screenshots/03-page-view.png" alt="A rendered page with a task list, a table and a callout, and the floating Edit button" width="260" />
</p>
<p align="center">
  <img src="docs/screenshots/04-page-edit.png" alt="The native markdown editor with its formatting toolbar" width="260" />
  <img src="docs/screenshots/05-alerts.png" alt="Alerts: mentions, changed pages and deleted pages" width="260" />
  <img src="docs/screenshots/06-favorites.png" alt="Favorites grouped by collective" width="260" />
</p>

## What it does

- **Home** shows what's new: pages you opened recently, pages changed since you last read them, recently updated pages, your own edits, and edits still to sync or resolve.
- **Read** pages with images, tables, task lists, code, callouts and highlights. Links to other pages open in the app, and each page lists its subpages, the pages linking to it, and its headings.
- **Edit** in the built-in markdown editor, which works offline, or in Nextcloud Text's collaborative editor to write alongside others (beta). Pages open for reading, or straight into the editor if you prefer; choose both under **Settings → Editor**.
- **Work offline.** Read cached pages and queue edits, which sync when you're back online. When a conflict is detected, your edits are kept as a draft for you to resolve.
- **Organise** pages: create, rename, move, duplicate, tag and star them, and trash and restore them, with Undo for trash. Sort them, or drag them into the web app's order. Create, trash and restore collectives too.
- **Search** every collective or just one, with your recent searches and tags ready before you type.
- **Alerts** for mentions of you and for changes to pages, from Nextcloud's Notifications app. Choose per collective: Off, Mentions or All.
- **Share** text, links or images from any app into a new or existing page, and attach photos from your camera or gallery.
- Several Nextcloud accounts, light and dark themes, a page text size, and background sync on a schedule you choose.

## What you need

- Android 10 or newer
- A Nextcloud server reached over HTTPS, with the [Collectives app](https://apps.nextcloud.com/apps/collectives) available to your account

## Install

### F-Droid (recommended)

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="80" />](https://f-droid.org/packages/com.megamaced.nccollectives/)

Search for **NC Collectives** in the [F-Droid](https://f-droid.org) app. F-Droid keeps it up to date.

### From GitHub

Download `app-release.apk` from the [latest release](https://github.com/megamaced/nc_collectives_android/releases/latest) and open it, allowing your browser or file manager to install apps when Android asks. **Settings → About → Check for updates** tells you when there's a newer one, which installs over the old.

The GitHub and F-Droid apps are the same signed build, so either can update the other.

## Signing in

Enter your server's address, such as `https://cloud.example.com`, and approve the app in the browser tab that opens. The app gets its own app password from your server, never your account password, and you can revoke it at any time in your Nextcloud security settings.

## Privacy

- The app talks only to your Nextcloud server. The exception is **Check for updates**, which asks GitHub when you tap it and never sends your Nextcloud credentials.
- No analytics, tracking, crash reporting, ads or Google services.
- It asks for no notification or camera permission: alerts stay inside the app, and photos are taken with your camera app.
- Your app password is stored encrypted. Signing out deletes it, along with every cached page and setting.
- HTTPS only. A private or self-signed certificate works only if its authority is in Android's system store, not installed as a user certificate.

## For developers

Built with Kotlin, Jetpack Compose (Material 3), Hilt, Retrofit and OkHttp, Room for the offline cache and edit queue, WorkManager for sync, Coil, and Markwon with Prism4j. The collaborative editor is Nextcloud Text's `directEditing` in a WebView, credentials are encrypted with Tink, and sign-in uses Nextcloud's [Login Flow v2](https://docs.nextcloud.com/server/latest/developer_manual/client_apis/LoginFlow/index.html#login-flow-v2).

```bash
./gradlew assembleDebug    # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease  # unsigned without the signing variables
```

[`docs/SIGNING.md`](docs/SIGNING.md) covers release signing. Release builds are reproducible, so F-Droid can check its own build against the signed release; its recipe is mirrored in [`docs/fdroid/com.megamaced.nccollectives.yml`](docs/fdroid/com.megamaced.nccollectives.yml).

Issues and pull requests are welcome. For larger changes, please open an issue first.

## License

[AGPL-3.0-or-later](LICENSE), the same family as Nextcloud server and the Collectives app.
