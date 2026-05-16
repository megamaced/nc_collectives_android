# Release signing

Release-signing keys never live in this repository. Local signed builds and CI signed builds both pick up the keystore from environment variables (or generated env entries in CI). This document records how to create the upload key, store it as a GitHub Actions secret, and verify the signed APK.

The signing setup is **optional**: if the env vars / CI secrets are absent, `assembleRelease` still produces an APK — it's just unsigned. Tag-driven releases without secrets will publish an unsigned APK to the GitHub release and emit a workflow warning.

## One-off keystore generation

Pick a strong passphrase and keep it somewhere safe (password manager). The keystore + passphrase **cannot be recovered** — losing either means the application can never be in-place upgraded again and a new key has to be issued (which forces users to uninstall and reinstall).

```bash
keytool -genkeypair -v \
  -keystore release.keystore \
  -keyalg RSA -keysize 4096 -validity 36500 \
  -alias nc_collectives \
  -dname "CN=NC Collectives Release, O=megamaced, C=GB"
```

`-validity 36500` ≈ 100 years; the Android signing scheme has no real upper bound on key lifetime and rotating keys is painful, so we go long.

`keytool` will prompt twice — once for the keystore password and once for the key password. Use the **same value** for both unless you have a specific reason not to; the env-var wiring assumes a single passphrase.

## GitHub Actions secrets

The CI workflow (`.github/workflows/build.yml`) reads four secrets when it runs on a tag matching `v*`:

| Secret name                          | Value                                                                  |
|--------------------------------------|------------------------------------------------------------------------|
| `ANDROID_RELEASE_KEYSTORE_BASE64`    | `base64 -w0 release.keystore` (the whole keystore as a base64 string)  |
| `ANDROID_RELEASE_STORE_PASSWORD`     | The keystore password                                                  |
| `ANDROID_RELEASE_KEY_ALIAS`          | `nc_collectives` (the alias passed to `keytool -alias`)                |
| `ANDROID_RELEASE_KEY_PASSWORD`       | The key password (same as the store password if you used one passphrase) |

Set them under **Repo Settings → Secrets and variables → Actions → New repository secret**.

To produce the base64 string, on Linux/macOS:

```bash
base64 -w0 release.keystore | pbcopy        # macOS
base64 -w0 release.keystore | xclip -sel c  # Linux (xclip)
base64 -w0 release.keystore                 # then paste manually
```

The CI step `Decode release keystore` writes the keystore back to `$RUNNER_TEMP/release.keystore` and exports the four `ANDROID_RELEASE_*` env vars that the Gradle script consumes.

## Local signed builds

Export the same env vars from your shell-rc / direnv / `.envrc` (don't commit any of these):

```bash
export ANDROID_RELEASE_KEYSTORE_FILE=/abs/path/to/release.keystore
export ANDROID_RELEASE_STORE_PASSWORD='…'
export ANDROID_RELEASE_KEY_ALIAS='nc_collectives'
export ANDROID_RELEASE_KEY_PASSWORD='…'
```

Then `./gradlew assembleRelease` produces a signed APK at `app/build/outputs/apk/release/app-release.apk`. Without the env vars present, the same task produces `app/build/outputs/apk/release/app-release-unsigned.apk` (which can't be installed on a device without `apksigner` post-processing).

## Verifying a signed APK

```bash
# Confirm the APK is signed and report the signing-certificate fingerprint:
$ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs app-release.apk
```

The certificate fingerprint reported here is what Android's PackageManager uses to gate in-place upgrades. Once the key is in use, every subsequent release must be signed with the same key — otherwise the install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Cutting a tagged release

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Commit, push `main`.
3. Tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
4. CI builds, signs with the secrets, and attaches `app-release.apk` to the GitHub release that the workflow auto-creates from the tag.
