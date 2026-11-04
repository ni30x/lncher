# iOS Launcher

An iOS-style home screen launcher for Android, built for the Samsung Galaxy A56 (Android 11+).

## Features

- Rounded-square app icons on a 4-column grid
- Swipeable pages with page dots
- Frosted dock with Phone, Messages, Browser and Camera (uses your default apps)
- Search pill with live app search
- Long-press an icon for App info or Uninstall
- Shows your normal wallpaper behind the icons

Not included: drag-to-rearrange, folders, widgets, real blur.

## How to get the APK

The APK is built automatically by GitHub Actions. No PC needed.

1. Open the **Actions** tab and wait for "Build iOS Launcher APK" to finish (about 3-5 minutes).
2. Open **Releases** on the main page of this repo.
3. Download `iOS-Launcher.apk` from the latest release.

To rebuild, go to **Actions**, select the workflow, and tap **Run workflow**.

## Install

1. Open the downloaded APK. If asked, allow "Install unknown apps" for your browser.
2. Press Home and choose **iOS Launcher**, then **Always**.

Or go to Settings, Apps, Choose default apps, Home app, and pick **iOS Launcher**.

## Go back to the Samsung launcher

Settings, Apps, Choose default apps, Home app, then select **One UI Home**.

## Troubleshooting

- **Build failed (red X):** open the failed run, expand the failed step, and copy the last lines of the log.
- **Home button does nothing special:** the launcher is not set as the default Home app yet.
- **Dock is missing an app:** the dock picks your default Phone, Messages, Browser and Camera apps. If none is set, that slot is skipped.

## Project layout

The workflow file at `.github/workflows/build.yml` writes the whole Android project (Gradle files, manifest, resources and `MainActivity.java`) at build time, then compiles it with Gradle 8.9, JDK 17 and Android Gradle Plugin 8.5.2.

Package: `com.example.ioslauncher`, minSdk 30, targetSdk 34.