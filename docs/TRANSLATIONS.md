# Translations and Weblate setup

The Android translation groundwork is complete: production UI text is in `app/src/main/res/values/strings.xml`, counts use plural resources, and view models carry translatable UI messages. CI checks for hard-coded UI text, verifies the generated language lists for debug and release, and runs Android lint, unit tests and both builds. The default locale is `en-US`.

As of 1 November 2026, a Hosted Weblate project has not been created. Creating `nc-collectives-android` in Crate's existing workspace with the account's API token returned HTTP 403: **No valid billing found or limit exceeded.** This response does not distinguish missing hosting, a capacity limit or an account configuration issue. No subscription or paid plan was started.

## Account setup

Check the existing workspace's billing/hosting settings first. Weblate assigns billing plans to workspaces, and projects in the workspace share that plan; do not assume each project needs a separate application. Resolve the reported blocker, then create a project through [Hosted Weblate](https://hosted.weblate.org/hosting/) or retry project creation through the API:

| Field | Value |
| --- | --- |
| Project name | NC Collectives Android |
| URL slug | `nc-collectives-android` |
| Project website | `https://github.com/megamaced/nc_collectives_android` |
| Translation licence | `AGPL-3.0-or-later` |
| Hosting | The existing workspace's Libre hosting, if its approval and available capacity allow this project |
| Workspace | The workspace connected to the `megamaced` GitHub account, also used by Crate |

The Hosted Weblate GitHub app also needs access to `megamaced/nc_collectives_android`. Check it under GitHub **Settings → Applications → Installed GitHub Apps → Hosted Weblate → Configure**. This app is an unofficial client; keep that distinction from the upstream Nextcloud Collectives project in its public description.

Suggested translation instructions:

> NC Collectives is an unofficial Android client for Nextcloud Collectives. Keep placeholders such as `%1$s` and `%1$d` exactly as written; they may move within a sentence but must not be dropped or renamed. Translate whole sentences and fill in the plural forms your language needs. Keep product names such as Nextcloud and Collectives unchanged. Read translator comments for context and leave a comment if a string is unclear. French uses “vous”; German uses informal “du”, matching Nextcloud. Partial translations are welcome; missing strings fall back to English. New languages need a readiness and fluent-speaker review before being enabled for release. Any future AI drafts will be labelled suggestions for human review, not accepted automatically.

## Component setup

Once the project exists, create its Android component using [`weblate-component.json`](weblate-component.json):

```text
POST https://hosted.weblate.org/api/projects/nc-collectives-android/components/
Content-Type: application/json
```

Authenticate using the existing keyring credential, never a token stored in this repository. Poll the returned `task_url` until the initial repository scan finishes. Install `weblate.cleanup.generic` with an empty configuration, so obsolete translations are removed when source strings disappear.

The file mask covers language catalogues under `src/main` only. It excludes the Polish translation fixtures in `src/debug` and cannot match a night-mode resource file: production currently has no `values-night/strings.xml`.

Start requested languages through the component's `translations/` endpoint. Verify their string counts against the source catalogue; if a newly created language has zero strings, run the component repository's `file-scan` operation. Check that the GitHub app can open a translation PR before advertising the project as ready for contributors.

When the project is live, add a Weblate acknowledgement, project badge and contribution link to the README to meet the Libre attribution requirement. Do not announce a working translation link before the project exists.

## Releases

`localeFilters` in `app/build.gradle.kts` currently contains only `en`. A new catalogue can be merged while work continues, but it will enter the APK and Android's per-app language list only after its qualifier is explicitly added. The debug-only Polish fixtures remain tests, not a supported language.

Use Crate Android's readiness policy as the starting point: at least 80% accepted translations, core screens covered, normal lint/build checks passing, and a fluent speaker's review recorded in an enabling PR. Do not count Weblate suggestions or fixed strings as translated. Keep enabled languages enabled when new source text lowers their coverage; missing strings fall back to English.

The policy/report automation has not been added to this repository. No language was enabled and no release was created as part of this setup preparation.
