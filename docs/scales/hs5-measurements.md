# Experimental HS5 measurement collection

Open **openScale → Settings → HS5 Wi-Fi setup → Collect HS5 measurements over Wi-Fi**.
There is one launcher icon; both HS5 screens are accessed inside openScale.

## Automatic collection

The automatic-sync build checks for your linked HS5 whenever the main openScale
screen comes to the foreground, then checks again 20 seconds after each attempt
while you keep the app open. Weigh yourself, open openScale on the same Wi-Fi,
and keep the scale awake until the new reading appears. Opening the app before
weighing also allows a later check to collect the completed reading. A sleeping
scale cannot be woken by this connector.

**Sync automatically while openScale is open** is enabled by default in the HS5
measurement screen. Existing installations with one linked user use that link.
If several users are linked, select your user and choose **Connect my scale
profile** to select the automatic-sync owner. Deleted or ambiguous users are
never guessed. The connector remembers the scale identity and rediscovers its
address on the local Wi-Fi if its address changes.

Automatic collection pauses when you leave openScale, lock the phone, or open
the HS5 manual screens. Return to the main openScale screen to resume it. New
records use the same verified database/journal path as manual sync; duplicate
records are skipped. A brief message confirms new saves. The HS5 screen shows
the last automatic-check status and time, and retains the manual controls below.

This collects completed stored readings. The live weight/composition stream
remains experimental; interim values are never saved as completed readings.

## Manual collection and initial linking

1. Connect the phone to the same local Wi-Fi network as the scale.
2. Tap the scale awake; wait for `0.0` and a steady Wi-Fi symbol. Do not enter SET.
3. Choose the openScale user and tap **Find scale on Wi-Fi**. If the router blocks
   broadcast discovery, use **Find scale by IP address** with the address in the
   router's device list.
4. Select the scale and tap **Connect my scale profile**. Create a new scale
   profile from your openScale age, height and gender, or explicitly select your
   existing numbered scale user. Existing slots are never overwritten or deleted.
5. Weigh yourself barefoot and wait for the scale to finish. Tap **Sync stored
   measurements** while the scale is awake. Keep the screen open until saving
   completes. The selected profile and device are remembered for future syncs.
6. A successful save is reported only after the record is read back from
   openScale. Use **Open measurements** to view the normal charts and table.

## Implementation

- Java implementation of the local HS5 UDP protocol; no vendor SDK or native
  library is bundled or executed, and no cloud endpoint is contacted or changed.
- Socket bound to Android's Wi-Fi network, local port 8000, scale port 10000.
  A multicast lock is held during discovery and sessions.
- Discovery `B0 04 00 00 FF D0 CF`; identity reply `F0`; local challenge/response
  `FA/FB/FC/FD`; user management `31`; profile creation `51`; measurement `32`;
  live/stable/impedance/final/stop `35–39`; history `33/41/42`.
- Wi-Fi envelopes include six client/device identifier bytes before the checksum.
  The HS5 model constant and XXTEA handshake were determined from the public
  iHealth SDK 2.15.1's `A9InsSet`, `IdentifyIns`, `WifiCommProtocol`, `GenerateKap`
  and `libiHealth.so`. Other sources: `manager.h`, `Hs5DataUtil`, `UserListInHs5`.
- Only complete final or history records are saved. Temporary live/stable values
  are displayed, not recorded as completed measurements.
- Records contain packed local dates and weight, body fat, water, muscle mass,
  bone mass, visceral fat rating and daily calorie intake. Muscle arrives in kg
  and is converted to a percentage before using openScale's provider interface.
  Bone and visceral fat use the provider's generic value fields. DCI is retained
  in the private journal, not mislabeled as BMR.
- History accepts both the SDK's exact-length packet and the extra zero padding
  byte observed on this HS5. Incomplete records and other trailing data are rejected.
- Storage goes through the existing Room-backed content provider, including its
  unit conversions, then recalculates derived values. Timestamp/weight matches
  suppress repeated imports; conflicting measurements are not overwritten.
- Each received record is first flushed to the app-private `hs5-readings.jsonl`
  journal. A failed save stops history retrieval before asking for the next packet.
- Wi-Fi collection needs `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`
  and `CHANGE_WIFI_MULTICAST_STATE`. Android calls the first permission Internet
  even when the socket is used only on the LAN.

## Validation and current limits — 2026-10-14

The automatic-sync update compiles and packages successfully using the SDK build
path below. All **27 HS5 JVM tests pass**, including seven foreground lifecycle
tests (cancellation, reopen races, retries and non-overlapping scheduling) and
four profile-selection tests. Its APK has the same package and signing
certificate as the installed build, and its manifest includes the internal
lifecycle initializer. It was subsequently installed over the existing app on
the Samsung SM-A566E. The app launched successfully, and automatic foreground
checks ran and reported waiting for the scale. A local backup was taken first;
all database records were unchanged after installation/startup, and SQLite's
integrity check passed. **Automatic measurement transfer was then verified on
the owner's scale:** after it was woken, a foreground retry imported new stored
readings and confirmed each save through the normal database readback, without
pressing the manual sync button. An initial scale error was followed by a
successful automatic retry. The earlier hardware results below also validate
the manual collection path.

The original 16 protocol tests (6 Bluetooth setup tests and 10 Wi-Fi tests)
cover fixed command vectors, checksums, payload boundaries, packed
dates and metric units, malformed records, user identifiers and a cipher vector
independently obtained from the vendor's pure Java XXTEA implementation, plus
the observed padded history format and rejection of truncated history records.

The new Java sources compile against the installed Android SDK and existing
openScale build outputs. Because this workspace blocks Gradle's file-lock network
service, a temporary SDK build script in `research/build-hs5-wifi.py` recompiles
the Java additions with `javac`/D8 and the manifest with AAPT2, preserves the
existing resource table/assets/native libraries, and signs with the existing
debug key. The resulting APK installed successfully on the Samsung SM-A566E;
the new activity opens and loads the existing openScale user. The normal full
build is still:

```sh
cd android_app
./gradlew :app:testDebugUnitTest --tests 'com.health.openscale.hs5.*' :app:assembleDebug
```

**Discovery, authentication, profile linking and history import are validated
on the owner's HS5.** After initially failing local address resolution, the
scale became reachable following a battery restart. Direct-IP discovery found
it, and the local handshake and user-list command succeeded without changing
cloud settings. An existing scale profile was linked to its owner's openScale
profile. Stored measurements were imported and read back from openScale; the
transfer then completed successfully. Original record dates are preserved.
A fresh reading matching the scale display was subsequently synced and verified
in openScale Overview with the current date and body composition fields. The
subsequent database backup passed SQLite's integrity check. Personal readings
and database backups are not part of this source repository.

**Live measurement remains experimental.** The scale accepted the online
session and sent interim packets, but did not deliver the final reading during
that session. The completed reading was retained on the scale and successfully
retrieved by **Sync stored measurements**. This is the verified collection path
and the first collection action in the screen. Creating a new scale profile is
still untested on hardware.

The original app database/preferences were backed up locally before installing
the update. No synthetic measurements were added to the user's database.
