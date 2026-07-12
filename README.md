# Growth Buddy Mobile

Capacitor shells (Android + iOS) around the web app in `../Growth-Buddy`. No app code lives here — the web build is copied in as-is.

## Build & run

```sh
# Android (needs Android Studio). API base defaults to the emulator's host alias.
npm run android

# iOS (needs Xcode)
npm run ios
```

Both scripts rebuild the web app, copy `../Growth-Buddy/dist` into `www/`, run `cap sync`, and open the native IDE. Hit Run from there.

## Pointing at a real backend

The API base URL is baked into the web build via `VITE_API_BASE` (it passes through the npm scripts):

```sh
VITE_API_BASE=http://<your-mac-ip>:8080 npm run android   # real device on your LAN
VITE_API_BASE=https://your-deployed-backend npm run ios   # production backend
```

Unset, requests go to relative paths. The current checked-in build was made with `http://10.0.2.2:8080` (Android emulator's alias for your Mac's localhost) — rebuild before shipping.

## Notes

- `CapacitorHttp` + `CapacitorCookies` are enabled in `capacitor.config.json` so fetch + the httpOnly auth cookie work natively without CORS setup.
- Web Push / service worker features degrade quietly inside the native shell (the web app already handles that). Native push would need `@capacitor/push-notifications` + FCM/APNs — not wired up.
- Icons/splash regenerate with `npx @capacitor/assets generate --iconBackgroundColor '#F97316' --splashBackgroundColor '#F97316'` (sources in `assets/`).
