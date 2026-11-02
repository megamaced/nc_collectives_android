# Translations and Weblate setup

The Android translation groundwork is complete: production UI text is in `app/src/main/res/values/strings.xml`, counts use plural resources, and view models carry translatable UI messages. CI checks for hard-coded UI text, verifies the generated language lists for debug and release, and runs Android lint, unit tests and both builds. The default locale is `en-US`.

The [Weblate project](https://hosted.weblate.org/projects/nc-collectives-android/) and its [Android app component](https://hosted.weblate.org/projects/nc-collectives-android/android-app/) are live. The source catalogue has 425 resources: 424 translatable resources and the fixed app name. The component has the AGPL-3.0-or-later licence, translator instructions and cleanup add-ons.

French and German are started. Each has 424 suggestions from `megamaced` that are AI drafts written by Claude, awaiting human review; neither has accepted translations yet.

The component uses Weblate's GitHub pull request backend (`vcs: github`). Weblate pushes to its own fork, `weblate/nc_collectives_android`, and opens pull requests from the `weblate-nc-collectives-android-android-app` branch. Fork runs get no repository secrets, so the release build is unsigned, as on any fork PR. Until a Weblate PR has been merged, GitHub treats the bot as a first-time contributor and its workflow runs wait for approval. Merge translation PRs with a merge commit once CI passes.

Hosted Weblate's Libre hosting is approved per project. Its [Libre eligibility check](https://github.com/WeblateOrg/weblate/blob/main/weblate/billing/models.py#L1074-L1081) requires exactly one project per hosting plan, and each hosting plan belongs to one workspace. NC Collectives Android therefore needs its own Libre hosting and workspace under the same user account. Crate's existing Libre approval cannot be shared by putting this second project in its workspace.

Trying to create `nc-collectives-android` in Crate's workspace returned HTTP 403: **No valid billing found or limit exceeded.** The one-project Libre requirement explains why a shared-workspace setup is unsuitable, although the API error alone does not expose the account's exact plan settings.

## Account setup

Create a project through [Hosted Weblate](https://hosted.weblate.org/hosting/) and request Libre hosting for NC Collectives Android. The separate workspace created by that process is expected. Both workspaces remain managed through the same `megamaced` account.

For hosting plans that allow additional projects, the existing workspace's **Operations → Add new translation project** menu is the normal creation route. Weblate hides that action when its billing or project-capacity checks fail. This route does not bypass the Libre one-project requirement.

| Field | Value |
| --- | --- |
| Project name | NC Collectives Android |
| URL slug | `nc-collectives-android` |
| Project website | `https://github.com/megamaced/nc_collectives_android` |
| Translation licence | `AGPL-3.0-or-later` |
| Hosting | Libre, requested specifically for NC Collectives Android |
| Workspace | A separate workspace for this project, owned by the same `megamaced` account |

The Hosted Weblate GitHub app also needs access to `megamaced/nc_collectives_android`. Check it under GitHub **Settings → Applications → Installed GitHub Apps → Hosted Weblate → Configure**. This app is an unofficial client; keep that distinction from the upstream Nextcloud Collectives project in its public description.

Repository selection in GitHub is only one part of the connection. Weblate records a workspace's GitHub connection only when GitHub redirects back to it after **Connect GitHub account**. See [Connecting a workspace](https://docs.weblate.org/en/latest/admin/code-hosting.html#connecting-a-workspace). The connection must belong to the component's own workspace; otherwise Weblate rejects `github-app` with **Repository is not available from a connected GitHub account for this workspace.**

This workspace has no GitHub connection. The Hosted Weblate app is already installed for `megamaced` and is connected to Crate's workspace only. Starting **Connect GitHub account** for this workspace opens that installation's GitHub settings page with **Save** disabled, and GitHub does not return to Weblate. Changing the repository selection and saving did not create the connection either. Do not remove Crate's connection: removing the last workspace connection to an installation uninstalls the GitHub App. When the integrations page lists a connection for this workspace that includes the repository, switch the component with its **Migrate to GitHub App** action, or set `vcs` to `github-app` with both push settings enabled.

Translation instructions:

> NC Collectives is an unofficial Android client for Nextcloud Collectives. Keep placeholders such as `%1$s` and `%1$d` exactly as written; they may move within a sentence but must not be dropped or renamed. Translate whole sentences and fill in the plural forms your language needs. Keep product names such as Nextcloud and Collectives unchanged. Read translator comments for context and leave a comment if a string is unclear. French uses “vous”; German uses informal “du”, matching Nextcloud. Partial translations are welcome; missing strings fall back to English. New languages need a readiness and fluent-speaker review before being enabled for release. The suggestions from megamaced are AI drafts written by Claude, an AI model, as a starting point. Please check each one — accept it, edit it, or replace it — rather than trusting it.

## Component setup

For a fresh setup, create the Android component using [`weblate-component.json`](weblate-component.json):

```text
POST https://hosted.weblate.org/api/projects/nc-collectives-android/components/
Content-Type: application/json
```

Authenticate using the existing keyring credential, never a token stored in this repository. Poll the returned `task_url` until the initial repository scan finishes. Install `weblate.cleanup.generic` and `weblate.cleanup.blank`, both with empty configurations, so obsolete and empty translations are removed and missing strings fall back to English.

`weblate-component.json` uses the GitHub App backend, which needs the workspace connection described above. Without that connection, set `vcs` to `github` and keep both `push_on_commit` and `push_on_update` set to `true`; Weblate then opens pull requests from its own fork. Switch this same component to `github-app` later. Do not create a duplicate component.

**Contains 0 components** means the project container exists but has no translation component yet. Import the Android component before requesting Libre hosting; refresh the hosting page after its background task completes. Weblate's staff still need to approve the request.

The file mask covers language catalogues under `src/main` only. It excludes the Polish translation fixtures in `src/debug` and cannot match a night-mode resource file: production currently has no `values-night/strings.xml`.

Start requested languages through the component's `translations/` endpoint. Verify their string counts against the source catalogue; if a newly created language has zero strings, run the component repository's `file-scan` operation. Adding a language commits an empty catalogue, and Weblate pushes it as a translation PR.

AI drafts go in as suggestions, never as translations. Upload ordinary strings in a catalogue with its `<plurals>` entries removed, through the translation's `file/` endpoint with `method` set to `suggest`; that upload would store each plural as one string with its forms run together. Add plural suggestions through `POST /api/units/<id>/suggestions/` with one `target` entry per plural form, in Weblate's order (French: one, many, other; German: one, other), and check them through `GET /api/units/<id>/suggestions/`. Outside quoted sections, Weblate reads no-break spaces in Android resources back as plain spaces; its French punctuation autofix then puts the correct space before `:`, `;`, `?` and `!`.

The README includes a Weblate acknowledgement, component badge and contribution link for the Libre attribution requirement.

## Releases

`localeFilters` in `app/build.gradle.kts` currently contains only `en`. A new catalogue can be merged while work continues, but it will enter the APK and Android's per-app language list only after its qualifier is explicitly added. The debug-only Polish fixtures remain tests, not a supported language.

Use Crate Android's readiness policy as the starting point: at least 80% accepted translations, core screens covered, normal lint/build checks passing, and a fluent speaker's review recorded in an enabling PR. Do not count Weblate suggestions or fixed strings as translated. Keep enabled languages enabled when new source text lowers their coverage; missing strings fall back to English.

The policy/report automation has not been added to this repository. The French and German catalogues are not in `localeFilters`, and no release includes them.
