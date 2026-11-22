# Panel Reader — Android & Web

A mobile-first manhwa/webtoon reader prototype with Kurdish Sorani demo translation overlays. The web app is packaged as a Capacitor Android project (`com.panel.reader`) and also supports PWA installation.

## Run the web app

```sh
npm install
npm run start
```

Open `http://localhost:5173`. For installable PWA/offline features, use an HTTPS deployment (or localhost).

## Build an Android debug APK

Requirements: JDK 21, Android Studio/Android SDK (Android API 36), and Android SDK Build Tools. Then run:

```sh
npm install
npm run android:apk
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`. Alternatively, run `npm run android:open` to open the project in Android Studio and build it there. `npm run android:sync` rebuilds the web assets and syncs them into the native shell.

## Included

- Responsive reader UI and six-panel illustrated demo chapter.
- Sorani demo dialogue badges with peek, hide, and style controls.
- Text scaling, tone selection, reading progress, bookmarks, image upload and direct image URL loading.
- Capacitor Android wrapper, custom launcher icon, web manifest, offline app-shell cache.

## Translation integration

The demo chapter uses locally authored sample dialogue. Uploaded pages and direct image URLs display in the reader, but this prototype does not run OCR or call a multimodal translation service. A server-side vision/OCR and Sorani translation endpoint is required to generate translations for user-provided images. No API key is bundled in the app.
