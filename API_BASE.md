# Why `build:web` sets `VITE_API_BASE`

The web app resolves its API host **at build time**, and an absent
`VITE_API_BASE` is a hard error rather than a default (`resolveApiBase()` in
`scripts/app.js`). That is deliberate: a shipped binary cannot be re-pointed
after the fact, so failing loudly at build beats every API call going somewhere
wrong on a user's phone.

Served from the backend's own origin, the right answer is *set but empty* —
relative paths, no CORS. **The Capacitor app is the exception.** It runs from
`https://localhost` (`androidScheme: "https"`), so relative paths would resolve
to the device itself. It needs the absolute production URL.

`npm run sync` without it produced a bundle that threw on launch — the app was
dead before the first screen. Override for a different environment:

    GB_API_BASE=https://staging.example.com npm run sync

The URL must also be in the backend's `CORS_ALLOWED_ORIGINS`, alongside
`https://localhost` for the app shell itself.
