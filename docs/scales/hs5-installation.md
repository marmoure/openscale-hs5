# Install or move openScale HS5 to another phone

## Install the working APK

1. Use an Android phone running Android 12 or newer (minimum API 31).
2. Open this repository's [Releases](https://github.com/marmoure/openscale-hs5/releases)
   on the phone and download `openScale-HS5.apk`. You can also copy that same APK
   from a computer with a USB cable or transfer it from another phone.
3. Open the downloaded APK. If Android requests it, allow that browser or file
   manager to install this app, then finish installation.
4. Open **openScale HS5 test**. There is one app icon. The package name is
   `com.health.openscale.hs5test`.

The APK includes the application, not anyone's measurements. The checksum file
in the same release identifies the exact APK that was tested.

## Automatic-sync builds

Builds marked `hs5-auto-sync` collect stored readings when you open openScale,
then check again 20 seconds after each attempt while the main app stays open.
Keep the scale awake and your phone on the same Wi-Fi. The switch and last-check
status are under **Settings → HS5 Wi-Fi setup → Collect HS5 measurements over
Wi-Fi**. Return to the main app to resume automatic collection after using the
manual controls. See [measurement usage and validation](hs5-measurements.md).

Install an automatic-sync APK over the existing **openScale HS5 test** using
Android's **Update** action. Do not uninstall the existing app: an in-place
update preserves its measurements and scale/profile link. The tested update
uses the same package and signing certificate as the original HS5 build.

## Keep existing measurements when changing phones

1. On the old phone, open **Settings → Data Management → Backup database**.
2. Save the backup ZIP somewhere you can access from the new phone, and copy it
   there. Keep this personal backup separate from this public source repository.
3. Install this APK on the new phone. Open **Settings → Data Management → Restore
   database**, choose the ZIP, and confirm. Restoring replaces the database on
   the destination phone, so use this on the new installation.
4. Reopen the app if requested. Your users and recorded measurements are in the
   database backup. App settings and the HS5 device/profile link are separate
   preferences, so link the scale again using the steps below.

The scale's own history is not a replacement for a database backup. Previously
synced records may no longer be available for another phone to retrieve.

## Reconnect the existing scale

1. Connect the new phone to the same Wi-Fi network as the scale.
2. Open **Settings → HS5 Wi-Fi setup → Collect HS5 measurements over Wi-Fi**.
3. Select your restored openScale user. Wake the scale, wait for a steady Wi-Fi
   symbol, and select **Find scale on Wi-Fi**. The direct-IP option is available
   if broadcast discovery does not work.
4. Choose **Connect my scale profile** and select your existing scale user
   number. This links the new phone to that scale profile.
5. Weigh yourself barefoot. Once the scale finishes, tap **Sync stored
   measurements** while the scale is awake. Wait for the saved confirmation.

Changing only the phone does not require resetting the scale's Wi-Fi or repeating
Bluetooth provisioning. Reconfigure Wi-Fi only if the network details change.
Use one phone at a time for a scale connection. Copies on different phones do
not automatically share their openScale databases.

## Preserve and rebuild the application

This repository retains the Android source, Gradle wrapper, protocol tests and
HS5 documentation. The release APK provides a reinstallable copy of the working
build. Personal database backups and signing keys are stored separately.

For a normal full build, use JDK 21 and Android SDK platform 37:

```sh
cd android_app
./gradlew :app:testDebugUnitTest --tests 'com.health.openscale.hs5.*' :app:assembleDebug
```

The APK output is `app/build/outputs/apk/debug/openScale-debug.apk`.
The initial release was built using the Android SDK and existing build outputs
because the validation environment restricted Gradle's file-lock service;
see the validation notes in [HS5 measurements](hs5-measurements.md).

Updates over an existing installation require the same package name and signing
key. Keep the original signing key in private storage for future builds; never
commit it to this public repository. A new phone can install the archived APK
without needing the key. The current build is experimental and updates are
installed manually.
