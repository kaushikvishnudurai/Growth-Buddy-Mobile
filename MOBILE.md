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

`npm run sync` = `build:web` + `cap sync`. Run it after **any** web change.
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
