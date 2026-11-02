# Translations and Weblate setup

The Android translation groundwork is complete: production UI text is in `app/src/main/res/values/strings.xml`, counts use plural resources, and view models carry translatable UI messages. CI checks for hard-coded UI text, verifies the generated language lists for debug and release, and runs Android lint, unit tests and both builds. The default locale is `en-US`.

The [Weblate project](https://hosted.weblate.org/projects/nc-collectives-android/) and its [Android app component](https://hosted.weblate.org/projects/nc-collectives-android/android-app/) are created. The initial import contains 425 resources: 424 translatable resources and the fixed app name. The component has the AGPL-3.0-or-later licence, translator instructions and cleanup add-ons.

GitHub App authorization for the new workspace is still pending. The component currently clones the public repository with the Git backend; automatic pushes are disabled until it can be switched to the GitHub App backend. Translators' work remains in Weblate during this setup.

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

Repository selection in GitHub is only one part of the connection. In Weblate, open this project's component-import page and use **Connect GitHub account** for its own workspace. Complete the GitHub authorization and the redirect back to Weblate. The existing GitHub App installation can be connected to more than one Weblate workspace; do not remove Crate's connection. If the account is listed but the repository is missing, refresh the connected account's repository list. See [Connecting a workspace](https://docs.weblate.org/en/latest/admin/code-hosting.html#connecting-a-workspace).

Suggested translation instructions:

> NC Collectives is an unofficial Android client for Nextcloud Collectives. Keep placeholders such as `%1$s` and `%1$d` exactly as written; they may move within a sentence but must not be dropped or renamed. Translate whole sentences and fill in the plural forms your language needs. Keep product names such as Nextcloud and Collectives unchanged. Read translator comments for context and leave a comment if a string is unclear. French uses “vous”; German uses informal “du”, matching Nextcloud. Partial translations are welcome; missing strings fall back to English. New languages need a readiness and fluent-speaker review before being enabled for release. Any future AI drafts will be labelled suggestions for human review, not accepted automatically.

## Component setup

For a fresh setup, create the Android component using [`weblate-component.json`](weblate-component.json):

```text
POST https://hosted.weblate.org/api/projects/nc-collectives-android/components/
Content-Type: application/json
```

Authenticate using the existing keyring credential, never a token stored in this repository. Poll the returned `task_url` until the initial repository scan finishes. Install `weblate.cleanup.generic` and `weblate.cleanup.blank`, both with empty configurations, so obsolete and empty translations are removed and missing strings fall back to English.

If GitHub App authorization is not yet available, a public repository can be imported with `vcs` set to `git` and both `push_on_commit` and `push_on_update` set to `false`. This creates the component needed by the Libre eligibility checklist without claiming that translation PRs are configured. Once the workspace connection sees the repository, update this same component to `github-app` and restore the push settings. Do not create a duplicate component.

**Contains 0 components** means the project container exists but has no translation component yet. Import the Android component before requesting Libre hosting; refresh the hosting page after its background task completes. Weblate's staff still need to approve the request.

The file mask covers language catalogues under `src/main` only. It excludes the Polish translation fixtures in `src/debug` and cannot match a night-mode resource file: production currently has no `values-night/strings.xml`.

Start requested languages through the component's `translations/` endpoint. Verify their string counts against the source catalogue; if a newly created language has zero strings, run the component repository's `file-scan` operation. Check that the GitHub app can open a translation PR before advertising the project as ready for contributors.

The README includes a Weblate acknowledgement, component badge and contribution link for the Libre attribution requirement.

## Releases

`localeFilters` in `app/build.gradle.kts` currently contains only `en`. A new catalogue can be merged while work continues, but it will enter the APK and Android's per-app language list only after its qualifier is explicitly added. The debug-only Polish fixtures remain tests, not a supported language.

Use Crate Android's readiness policy as the starting point: at least 80% accepted translations, core screens covered, normal lint/build checks passing, and a fluent speaker's review recorded in an enabling PR. Do not count Weblate suggestions or fixed strings as translated. Keep enabled languages enabled when new source text lowers their coverage; missing strings fall back to English.

The policy/report automation has not been added to this repository. No language was enabled and no release was created as part of this setup preparation.
