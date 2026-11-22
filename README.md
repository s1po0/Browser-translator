# Panel Browser — Android manhwa translator

This project contains a real Android WebView browser (`com.panel.reader`) plus a browser-based reader demo. The APK has a URL/search bar, ordinary web navigation, page loading/progress, downloads and image upload. It is not limited to the bundled demo chapter.

## Android app behavior

- Opens websites in an in-app browser, with back/forward, reload, home, URL/search, downloads, and image-file selection.
- **Live Translate** captures the currently visible web page, limits OCR to the screen regions occupied by images, runs on-device ML Kit OCR (Latin + Korean), and overlays Sorani dialogue at the detected coordinates. Once enabled, it rescans as you scroll so translations follow the page without downloading or hotlinking each comic image.
- The plus button can open the camera or gallery. Photos/selected images are OCRed and translated automatically after they load.
- Tap a translation badge to reveal the source; tap the toolbar translation control to show/hide all overlays. Long-press it to scan the current page again.
- No user API key is required. OCR is local; recognized dialogue is sent to Google Translate's public web endpoint for Sorani translation, so translation needs internet access and may be rate-limited. Image hotlink restrictions and non-image/canvas-based comics may prevent OCR on some sites.

## Build APK

Requires JDK 21 and Android SDK platform/build tools 36:

```sh
npm ci
npm run android:apk
```

The debug APK is created at `android/app/build/outputs/apk/debug/app-debug.apk`. A GitHub Actions workflow builds it on pushes to the active branch and uploads the APK as an artifact. It also copies the latest requested build to `downloads/panel-reader.apk` on that branch.

## Web reader demo

`index.html` remains a companion web reader demo with a six-panel sample chapter. Run it with `npm run start`.
