# Building, testing and releasing the Android app

The app is a separate Gradle build in `android/` that includes the root build (modules `core` and
`client`). It has not yet been built into an APK: it was written where Google Maven is unreachable and
was only type-checked (see the README). The steps below are the first real build.

## Requirements

- JDK 17 or newer (Android Studio's bundled JDK works).
- Android SDK with platform 35 and build tools (install through Android Studio, or `sdkmanager
  "platforms;android-35" "build-tools;35.0.0"`).
- Network access to Google Maven (`maven.google.com`) and Maven Central for the Android Gradle Plugin,
  AndroidX, Jetpack Compose and Room. Inside Iran this usually needs whatever access your team already
  uses for Android development; the Gradle files reference only the official repositories.

Versions are in `gradle/libs.versions.toml` (Kotlin 2.4.20, AGP 8.13.0, KSP 2.3.12, Compose BOM 2025.06.00,
Room 2.7.1). The Gradle wrapper (8.14.3) downloads itself.

## First build

```bash
cd android
cp local.properties.example local.properties     # set sdk.dir (Android Studio does this for you)
./gradlew :app:testDebugUnitTest                  # unit tests, including the Room contract tests (Robolectric)
./gradlew :app:assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug                       # onto a connected device or emulator
```

Or open the `android/` folder in Android Studio and run the `app` configuration.

Things to check on this first build, because they could not be checked without Google Maven:

1. **Compilation against the real AndroidX libraries.** The type check used signature stubs for
   lifecycle, navigation, Room, WorkManager, DataStore, activity and Coil. Small API differences may show
   up as compile errors; fix them in place.
2. **Room's annotation processing** (KSP). It writes the schema to `android/app/schemas/…/1.json`. Commit
   that file: future schema versions need it to write migrations. There is intentionally no destructive
   migration fallback.
3. **The unit tests** (`RoomLocalStoreTest` runs the shared storage contract on Room and SQLite FTS,
   including Persian search).
4. **A run-through on a device**, in Persian and English: load the sample data (Settings), add a customer,
   a requirement and a property with a map pin, open Matching, record an offer and a counteroffer, export a
   report as an admin, and sign in to a local backend (`http://10.0.2.2:8080` from the emulator, see
   [BACKEND.md](BACKEND.md)) to check sync and conflicts with two devices.
5. **A release build with R8** (below), installed and opened, since shrinking can remove classes used only
   through serialization. The keep rules are in `android/app/proguard-rules.pro`.

## Release signing

1. Create a keystore once and keep it, with its passwords, somewhere safe and outside the repository.
   Losing it means installed copies can never be updated.
   ```bash
   keytool -genkeypair -v -keystore melkcrm-release.jks -alias melkcrm -keyalg RSA -keysize 4096 -validity 10000
   ```
2. `cp android/keystore.properties.example android/keystore.properties` and fill in `storeFile`,
   `storePassword`, `keyAlias`, `keyPassword`. The file and `*.jks` are git-ignored. On a CI server, write
   this file from the CI's secret store at build time instead of storing it.
3. Set `versionCode` (increase it for every release) and `versionName` in `android/app/build.gradle.kts`.
4. Build:
   ```bash
   cd android
   ./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk (signed)
   ./gradlew :app:bundleRelease       # .aab, if you publish through a store that accepts bundles
   ```
   Without `keystore.properties` the release APK is built unsigned and cannot be installed.
5. Verify the signature: `$ANDROID_HOME/build-tools/35.0.0/apksigner verify --print-certs app-release.apk`.

## Distribution

The app is for agency staff, so it does not need a public store listing. Options:

- **Direct install**: share the signed APK through the agency's own channel; staff allow installing from
  that source once. Keep the same keystore so updates install over the old version without losing data.
- **Iranian stores** such as Cafe Bazaar or Myket, with a private or unlisted listing if they offer one.
- **Google Play** internal testing, where it is reachable.

Every agent signs in with their own account on the agency's server; there are no shared credentials or
API keys inside the APK. The Neshan key (optional, for address search) is entered by an administrator in
Settings on each device and stored encrypted.

## Configuration defaults

`android/local.properties` can pre-fill two values for development builds:

- `melkcrm.serverUrl`: shown as the default server address on the sign-in screen.
- `melkcrm.tileUrl`: the map tile template; the default is the public OpenStreetMap server, whose usage
  policy does not allow heavy production use. Agencies should set their own provider in Settings → Map.

Cleartext HTTP is allowed only for `10.0.2.2` and `localhost` (`res/xml/network_security_config.xml`).

## Resources and checks

- Strings for both languages come from one table: edit `android/tools/strings.py`, then run
  `python3 android/tools/strings.py` to regenerate `values/strings.xml` and `values-fa/strings.xml`.
- `python3 android/tools/check_resources.py` fails if the code references a missing string, if an error
  code from the server or the validators has no message, or if the two languages use different format
  arguments.
- `./gradlew -p tools/android-typecheck compileTestKotlin` (from the repository root) type-checks the app
  and its unit tests on a machine without Google Maven. It is a compile check only.
- Vazirmatn is bundled under the SIL Open Font License ([VAZIRMATN-OFL.txt](VAZIRMATN-OFL.txt)).
