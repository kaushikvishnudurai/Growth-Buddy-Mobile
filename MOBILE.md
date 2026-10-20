# Growth Buddy — mobile (Capacitor) handoff

## What it is

`../Growth-Buddy-Mobile` is a thin Capacitor 8 wrapper around the **web build**
of `../Growth-Buddy`. There is no separate mobile source — `npm run build:web`
builds the Vite app and copies `Growth-Buddy/dist` → `Growth-Buddy-Mobile/www`.

- appId `com.growthbuddy.app`, appName "Growth Buddy"
- `androidScheme: https` (WebView origin is `https://localhost`)
- `CapacitorHttp` **enabled** — fetch goes through native, so CORS does not apply.
  If API calls fail anyway, suspect this first: without it the Render backend
  would need `https://localhost` in `CORS_ALLOWED_ORIGINS`.
- Android + iOS platforms exist. **iOS is not buildable on this machine** — no Xcode.

## Build / sync

`VITE_API_BASE` is read at **build time** by `resolveApiBase()` (`scripts/app.js:55`)
and baked into the bundle. A native build with it unset **throws at runtime**.
So always pass it on the sync:

```bash
cd ../Growth-Buddy-Mobile
VITE_API_BASE=https://growth-buddy-cu63.onrender.com npm run sync
```

`npm run sync` = `build:web` + `cap sync`. Run it after **any** web change. It builds `../Growth-Buddy` as checked out; to build another worktree (e.g. a `main` worktree while that checkout is on a feature branch), `GB_WEB=../Growth-Buddy-main npm run sync`.
Verify the URL landed: `grep -o 'onrender[^"]*' android/app/src/main/assets/public/assets/index-*.js`

Then:

```bash
cd android && ./gradlew assembleDebug     # → app/build/outputs/apk/debug/app-debug.apk (~8.7 MB)
```

## The JDK trap (already fixed, don't undo)

`android/gradle.properties:33` pins:

```
org.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```

JDK 21 is the **only** version that satisfies both ends:

- Capacitor 8 compiles at source release 21 → JDK 17 fails `invalid source release: 21`
- Gradle 8.14.3 supports at most Java 24 → Android Studio's bundled Java 25 JBR
  fails `Unsupported class file major version 69`

This overrides `JAVA_HOME`, and Android Studio honours it over its own Gradle JDK
setting. The path is machine-specific (installed via `brew install openjdk@21`) —
update it if the JDK moves or the repo is cloned elsewhere.

## Launching

Android Studio is at `/Applications/Android Studio.app`, SDK at `~/Library/Android/sdk`.

**No AVD exists yet** — the SDK is installed but no system image was downloaded
(`emulator -list-avds` is empty). Create one once, in the GUI:

1. `npx cap open android` (or open `Growth-Buddy-Mobile/android` directly)
2. Device Manager → Create Virtual Device → Pixel 7 → API 34/35 (~1.5 GB download)
3. Run ▶

After that the IDE is optional:

```bash
~/Library/Android/sdk/emulator/emulator -avd <name> &
cd Growth-Buddy-Mobile/android && ./gradlew installDebug
```

## Backend

Pointed at production: `https://growth-buddy-cu63.onrender.com` (Render free tier).

- Sleeps after 15 min idle → **first request cold-starts for ~50s**. The app looks
  hung; it is the server waking.
- To hit the local backend instead, rebuild with the LAN IP (not `localhost` — that
  is the emulator itself): `VITE_API_BASE=http://10.0.2.2:8080 npm run sync`
  (`10.0.2.2` is the Android emulator's alias for the host machine).

## Files and permissions

- `@capacitor/filesystem` + `@capacitor/share`: every export (Money and Report share cards, Download
  my data, reminders .ics, money CSV) goes through the web repo's `native.js` `saveFile`, which
  writes to the cache dir and opens the share sheet. The WebView has no download handler, so
  without these two a blob download is silently dropped. Share uses the app's existing
  `${applicationId}.fileprovider` (`res/xml/file_paths.xml` already exposes `cache-path`).
  After `npm install`, a `cap sync` is needed to register them in the Android/iOS projects.
- `AndroidManifest.xml`: `CAMERA` (barcode scanner, photo capture) and `RECORD_AUDIO` +
  `MODIFY_AUDIO_SETTINGS` (custom tone recording). Capacitor's `BridgeWebChromeClient` turns a
  WebView getUserMedia into a runtime prompt only for permissions the manifest declares; an
  undeclared one is refused with no dialog. iOS: `NSCameraUsageDescription`,
  `NSMicrophoneUsageDescription`, and `NSPhotoLibraryAddUsageDescription` (the share sheet's
  Save Image).

## Health (HealthKit / Health Connect, read only)

`@capgo/capacitor-health` reads what the OS health store already holds: today's steps for a Steps
habit, last night's sleep, and the Health summary screen. The read set is `HEALTH_READ` in the web
repo's `scripts/native.js`; only `requestHealthAccess()` opens a permission sheet, every other read
just reads. It never runs a sensor or service of its own, so there is no battery cost, and it never
writes anything back.

- Android: minSdk 26 (Health Connect's floor). The plugin declares ~50 health permissions;
  `AndroidManifest.xml` keeps the `READ_*` for exactly `HEALTH_READ` and strips every `WRITE_*`
  and every other `READ_*` (VO2 max, blood glucose, body and basal temperature, basal metabolic
  rate, total calories, mindfulness, hydration, nutrition), because Play asks you to justify every
  one. Adding a type means `HEALTH_READ`, the manifest and a line below, in one change.
  Health Connect's "Privacy policy" link opens `public/privacy.html` (string
  `health_connect_privacy_policy_url`). The APK is shared directly for now; if it ever goes on
  Play, Play Console needs the Health apps declaration form first. On Android 13 and below,
  Health Connect is a separate app from Play; 14+ has it built in. Health Connect only has data if
  something writes it: the phone itself on newer Android versions, or Fit, Samsung Health or a
  watch app.
- No `READ_HEALTH_DATA_HISTORY`: Health Connect returns at most 30 days before the first grant,
  so a weight logged months before install is not shown. Body measurements look back 90 days;
  adding it needs the manifest entry plus `requestHistoryAccess: true` on the request.
- iOS: `App.entitlements` turns on HealthKit (no clinical records, no background delivery), and
  `NSHealthShareUsageDescription` is set. No `NSHealthUpdateUsageDescription`: the request passes
  only `read`, so the plugin asks HealthKit for an empty share set. The App ID also needs the
  HealthKit capability in the developer portal.

Per-permission justification (one line each, reusable in the Play Console health declaration):

| Permission | Plugin type | Why Growth Buddy reads it |
|---|---|---|
| `READ_STEPS` | steps | Fills in the user's Steps habit with today's step count and shows daily steps in the Health summary. |
| `READ_DISTANCE` | distance | Shows the distance walked or run today, and each workout's distance, in the Health summary. |
| `READ_ACTIVE_CALORIES_BURNED` | calories | Shows active calories burned today, and per workout, in the Health summary. |
| `READ_FLOORS_CLIMBED` | flightsClimbed | Shows floors climbed today in the Health summary's activity section. |
| `READ_SLEEP` | sleep | Fills in last night's sleep automatically so the user does not have to log bedtime and wake time by hand. |
| `READ_HEART_RATE` | heartRate | Shows today's heart rate range in the Health summary. |
| `READ_RESTING_HEART_RATE` | restingHeartRate | Shows resting heart rate in the Health summary as a recovery indicator. |
| `READ_HEART_RATE_VARIABILITY` | heartRateVariability | Shows heart rate variability in the Health summary as a recovery indicator. |
| `READ_OXYGEN_SATURATION` | oxygenSaturation | Shows the latest blood oxygen reading in the Health summary. |
| `READ_RESPIRATORY_RATE` | respiratoryRate | Shows the latest respiratory rate in the Health summary. |
| `READ_WEIGHT` | weight | Shows the user's latest weight in the Health summary's body measurements. |
| `READ_HEIGHT` | height | Shows the user's height in the Health summary's body measurements. |
| `READ_BODY_FAT` | bodyFat | Shows the latest body fat percentage in the Health summary's body measurements. |
| `READ_BLOOD_PRESSURE` | bloodPressure | Shows the latest blood pressure reading in the Health summary. |
| `READ_EXERCISE` | workouts | Lists the past week's workouts in the Health summary. |

## Notification buttons run in the background (Android)

`patches/@capacitor+local-notifications+*.patch` (applied by `postinstall: patch-package`) makes a
button on a local notification broadcast `<applicationId>.NOTIFICATION_ACTION` instead of launching
the activity, whenever the app declares a receiver for it. `NotificationActionReceiver`
(`com.growthbuddy.actions`) then makes the same call the web app's `handleNotificationAction` would
(reminder done / snooze, habit check-in, +250 ml water) with the session token the web app mirrors
through `NotificationActionsPlugin.setAuth`, and re-posts a snoozed card at `snoozedUntil`.
Upgrading `@capacitor/local-notifications`: re-check that the patch still applies (`npm i` fails
loudly if not). iOS is unpatched: its buttons are `foreground` and open the app.

## Money's automatic capture (Android, `com.growthbuddy.capture`)

Web side: `scripts/capture.js`, `capture-ui.js`, `bank-msg.js` (the parser) in ../Growth-Buddy; server
`money/CaptureService`. `CapturePlugin` (registered in `MainActivity`) is the bridge, as `GbCapture`.

- **Payment app alerts** — `CaptureNotificationListener` (manifest `<service>`, bound only after the user
  turns on this app's "Notification access", which the app asks for behind its own consent card). It keeps
  text only from `APPS` (GPay, PhonePe, Paytm, BHIM, CRED, HDFC, ICICI iMobile, SBI YONO, Axis, Kotak), and
  only when it looks like a payment. Messaging apps are deliberately not in the list (reading SMS through
  their notifications would sidestep Play's SMS review). No Play declaration form exists for it; the
  in-app consent card is the User Data policy's prominent disclosure.
- **Share to Growth Buddy** — `MainActivity`'s `ACTION_SEND text/plain` filter; the plugin keeps the text
  for `takeShared()` / fires `shared`. A bank SMS becomes a capture, other text opens Quick add.
- **Queue** — `CaptureStore`: `noBackupFilesDir()/gb_capture.json` (never in cloud backup), at most 200
  items, 3 days; the web side `peek()`s, sends only the parsed fields, then `ack()`s. Switching a source off
  drops what it queued.

### SMS capture release (its own release, not mixed into a fix build)

The code ships in every build (`CaptureSmsReceiver`, `readSmsInbox`); the manifest entries do not, because
Play holds the **whole release** in review (up to several weeks) once READ_SMS / RECEIVE_SMS appear.

1. In `AndroidManifest.xml`, uncomment the `CaptureSmsReceiver` `<receiver>` and the two SMS
   `<uses-permission>` lines (both marked "SMS capture release ONLY").
2. Bump `versionCode`, build the bundle, upload to a new release.
3. Play Console > App content > **Permissions declaration**: use case **SMS-based money management**;
   core feature = "Money fills in spends from the user's bank SMS; parsed on the device, only amount,
   payee, day, last 4 digits and a reference fingerprint are sent". Attach a **video** (switch on Bank SMS
   in Settings > Layout > Money > Automatic capture, the consent sheet, Android's prompt, the 90-day read,
   rows appearing in Money, turning it off and deleting). Add the review test account.
4. Data safety: SMS = processed on device, not collected; financial info (amounts, payees) = collected,
   not shared, user can delete. The Financial features declaration (personal finance: budgeting) too.
5. If refused, ship the next build with the lines commented again; payment app alerts keep working.

Needs a real phone test (no emulator here): notification access per OEM (OnePlus/Xiaomi battery savers can
kill listeners), each app's real notification wording against `scripts/bank-msg.cases.json`, Android 15 OTP
redaction, the share sheet, and the SMS release's permission prompt + backfill.
