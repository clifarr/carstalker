# Releasing CarStalker

## Versioning

- `versionCode` must increase for every release.
- `versionName` is semantic. Beta tags use `vX.Y.Z-betaN` (for example `v0.3.0-beta1`).
- `RELEASE_CHANNEL` is `beta` in `app/build.gradle.kts`; change it to `stable` when the beta ends.

## Before tagging

```bash
./gradlew :core:test :app:testDebugUnitTest :app:lintRelease
./gradlew :app:assembleRelease
```

- Install the release APK on a device or emulator.
- Test demo mode and, if possible, a real adapter.
- Update the beta notes in [store-listing.md](store-listing.md) if needed.
- Commit and push.

## GitHub Actions secrets

The release workflow signs the APK with the upload key. Add these repository secrets under **Settings → Secrets and variables → Actions**:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | Base64 of the release keystore. On macOS: `base64 -i ~/.carstalker/carstalker-release.jks \| pbcopy` |
| `STORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias |
| `KEY_PASSWORD` | Key password |

Never commit the keystore or `keystore.properties`. The workflow writes `keystore.properties` from the secrets, builds, and deletes it afterward.

## Release

> The first release can be created manually with `gh release create` before the signing secrets are configured. Subsequent releases use the workflow below.

1. Tag and push:

   ```bash
   git tag v0.3.0-beta1
   git push origin v0.3.0-beta1
   ```

2. The **Release APK** workflow runs tests and lint, builds the signed APK, generates `SHA256SUMS`, and creates a GitHub Release with both files.
3. Verify the release locally:

   ```bash
   apksigner verify --print-certs carstalker-v0.3.0-beta1.apk
   shasum -a 256 carstalker-v0.3.0-beta1.apk
   ```

4. Announce the release.

## Play Store later

- Upload `app/build/outputs/bundle/release/app-release.aab` to Play Console.
- Complete the app content forms using [store-listing.md](store-listing.md).
- Start with internal and closed testing. If the account is a new personal account created after 13 November 2023, keep at least 12 testers opted in for 14 continuous days before applying for production access.
- Use a staged rollout: 5% → 20% → 50% → 100%.

## Signing key

- Back up `~/.carstalker/carstalker-release.jks` and the passwords somewhere secure.
- Use the same key for every APK release. If the key is lost, users must uninstall before installing a build signed with a new key.
- Play App Signing can protect the Play distribution, but direct APK users still rely on the upload key.
