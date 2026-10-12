# Nauru: The Game

A small, touch-first arcade game inspired by Nauru and its surrounding Pacific reef. Steer an outrigger canoe, collect native seedlings and rain, and avoid drifting debris to help the island grow greener.

## Play locally

```bash
npm ci
npm run dev
```

Open the Vite URL shown in the terminal. On phones, drag across the water or hold the arrow buttons; on desktop, use the arrow keys or **A / D**. Gather 12 supplies before time runs out. The sound toggle, pause/resume, and best score are included.

## Build an Android APK

The Android app wraps the same offline-capable game with Capacitor. The installable debug APK is built automatically on pushes to `main` or `arena/7a3bc4c5-temporary3` and can be downloaded from the **Nauru: The Game APK** artifact on the GitHub Actions run.

To build it locally with Android Studio / Android SDK and JDK 21 installed:

```bash
npm ci
npm run android:sync
cd android
./gradlew assembleDebug
```

The APK will be at `android/app/build/outputs/apk/debug/app-debug.apk`. This debug build is directly installable on Android; it is not a Play Store-signed release.

## Project structure

- `src/` — game logic, canvas rendering, and responsive styles
- `public/` — app icon and web app manifest
- `android/` — Capacitor Android wrapper
- `.github/workflows/android-apk.yml` — reproducible APK build and artifact upload
