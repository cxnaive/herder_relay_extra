# Native build CI and Android signing

The `build-artifacts` workflow builds Relay/Gateway for Linux amd64, Linux arm64,
and macOS arm64, and Herdr Pocket for `armeabi-v7a`, `arm64-v8a`, and `x86_64`.
It does not create tags or publish/replace GitHub Releases. Actions artifacts
expire after **one day**, like the repository's other CI artifacts.

## Which APK can update an existing installation?

| Artifact | Contents | Existing Pocket installation |
| --- | --- | --- |
| `android-apks-ci` | Debug APKs ending in `-ci.apk`, Release APKs ending in `-unsigned.apk` | Not an upgrade package: Debug uses the runner's temporary key; Release is unsigned. |
| `android-apks-signed` | Debug and Release APKs, `BUILD-INFO.json`, `SHA256SUMS.txt` | Both build types use the existing Pocket certificate. Select the matching device ABI and do not downgrade the versionCode. |

The fixed signing job runs only for a **push or manual workflow dispatch on
`feat/android-file-companion` in `cxnaive/herder_relay_extra`**. This is the
existing default branch, not an additional signing branch. PRs, other branches,
tags, and forks do not run that job or receive its secrets. The full `check`
workflow also runs for pushes and PRs targeting this default branch, alongside
its existing `main` and `dev` triggers.

The expected certificate SHA-256 is recorded in `VERSION.json`:

```text
fe1ddf4d04f46d45168d53ca25dd2b379f242b6cd9b23169c9717258cf5232b9
```

This preserves the existing **debug certificate** used by released Pocket APKs;
a Release *build type* does not change that signing identity or turn it into a
Play Store release. Do not generate a replacement key to fix a CI failure, and
do not uninstall an existing app merely to install a differently signed build:
that can lose pairing and drafts.

## Secret storage and verification

The GitHub Actions environment **`android-signing`** must allow deployments only
from the branch `feat/android-file-companion` (no wildcard or tag policy). Its
four encrypted environment secrets are:

- `HERDR_ANDROID_KEYSTORE_BASE64`: base64 of the existing signing keystore.
- `HERDR_ANDROID_KEYSTORE_PASSWORD`: its store password.
- `HERDR_ANDROID_KEY_ALIAS`: the existing key alias.
- `HERDR_ANDROID_KEY_PASSWORD`: that key's password.

Never commit these values, put them in repository variables, or upload the
keystore as an artifact. Retain a private backup of the original key separately.
The job's branch/event restriction and the environment's deployment policy must
both remain in place; do not replace them with `pull_request_target` or sign
artifacts taken from another workflow run.

Gradle builds and tests **without** the fixed key. A separate, environment-bound
job downloads APKs from the same run and then:

1. Checks all six input APKs, their versions, ABIs, and recorded hashes.
2. Decodes the keystore in a private temporary directory outside the checkout.
3. Signs every Debug and Release APK with `apksigner`, using password environment
   variables rather than password command arguments.
4. Verifies each APK and requires exactly the expected signing certificate.
5. Publishes the complete directory only after every APK passes, then removes
   the temporary key. An error does not fall back to a new Debug key or publish
   a partially signed artifact set.

`BUILD-INFO.json` records the commit, APK hashes, build types, ABIs, and public
certificate fingerprint. `SHA256SUMS.txt` covers the APKs and that manifest.
Neither contains a private key or password.

## Local build and tests

The wrapper is pinned to **Gradle 8.14.6** with its official distribution SHA-256.
This keeps the build on the maintained 8.x patch line while retaining Android
Gradle Plugin 8.13.0, Kotlin Gradle Plugin 2.2.20, and JDK 21. The wrapper JAR and
launch scripts are generated together, not just the distribution URL.

`setup-gradle` classifies every previous-major release as “out of date”, including
maintained 8.14.x patches. Its informational notice can therefore remain with
8.14.6; this is not a build failure. Moving to Gradle 9.x is a separate plugin
compatibility migration, not a reason to disable that notice or blindly set
`gradle-version: current` in CI.

A normal local `assembleDebug` still produces the universal `app-debug.apk`.
CI opts into per-ABI builds explicitly:

```bash
cd herdr-android
./gradlew -PsplitApks=true testDebugUnitTest assembleDebug assembleRelease assembleDebugAndroidTest lintDebug
cd ..
python3 -m unittest discover -s scripts/tests -p test_android_artifacts.py
python3 scripts/android-artifacts.py collect herdr-android/app/build/outputs/apk dist/android-ci
```

The signing script accepts the same environment secrets locally; it refuses to
overwrite an existing output directory. Do not print, trace, or save secret
values in shell history when setting them. The offline packaging/signing tests
use synthetic files and a fake signer, not an actual personal keystore.
