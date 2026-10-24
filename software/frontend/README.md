# Heal-T — Android Frontend

Field-medic triage dashboard (Jetpack Compose). Talks to the FastAPI backend in
`software/backend` over REST + WebSocket.

## Opening the project

1. **Android Studio** (any recent version — it bundles the JBR JDK 21 that the build needs).
   No separate Java install is required or expected on your machine.
2. Open `software/frontend` (the folder containing `settings.gradle.kts`).
3. Let Gradle sync finish. On first sync Android Studio will prompt to install
   **SDK Platform 37** (and build-tools) — accept the prompt and let it download.
4. `local.properties` (SDK path) is created automatically — it is git-ignored, never commit it.

## Choosing the backend

`app/src/main/java/com/example/healthmonitor/NetworkConfig.kt`:

- `USE_LOCAL_BACKEND = true` → `http://10.0.2.2:8000` (emulator loopback to your PC;
  change `LOCAL_HOST` to your PC's LAN IP for a physical device over Wi-Fi).
- `USE_LOCAL_BACKEND = false` → the production `https`/`wss` endpoint.

Set it per your working mode. **Release builds must use the production endpoint** —
cleartext to the local backend is for development only.

## Running the backend locally (for the local-backend mode)

```
cd software/backend
python -m venv .venv          # once
.venv\Scripts\activate        # Windows
python seed.py                # after first server start, to populate demo data
```

Start the server (`uvicorn main:app`) before launching the app.

## Building

- **Debug APK**: Build → Build APK(s), or `.\gradlew.bat assembleDebug` (needs a JDK 21 on PATH).
- **Release APK**: `.\gradlew.bat assembleRelease` — R8 minification is enabled.
  Note: certificate pinning is still placeholder (no active pins) — see the security audit.

## Gotchas

- The version catalog is `gradle/libs.versions.toml` — all plugin/library versions live there.
- Do not add `org.gradle.java.home` to `gradle.properties` (machine-specific paths break
  everyone else's builds).
- `app/src/main/java/.../extra/` holds stale pre-fix copies of the map assets —
  only `app/src/main/assets/` is packaged.