# Sipan · سیپان

Sipan is a native Android browser for reading web pages, manga, manhwa, webtoons and menus in your own language. Sipan upgrades the signed RojInk and Sipan releases with the same Android application ID and signing key. Tap **Paste & open** (or enter a link), then **Translate → Start translation**, and Sipan uses Google Translate for page text and recognized text inside images. **کوردیی سۆرانی (Sorani Kurdish, `ckb`)** is the default target. Open **Try a sample page** on the home screen to try a built-in panel and a menu.

**بۆ دەستپێکردن:** لینکی مانگا یان مانهوا دابنێ. کاتێک پەڕەکە بار بوو، **Translate → Start translation** هەڵبژێرە. زمانی بنەڕەتی وەرگێڕان کوردیی سۆرانییە. بۆ گۆڕینی زمانی ڕووکاری ئەپ، لە سەرەتای ئەپ **کوردی** هەڵبژێرە.

## Install the APK without Android Studio

1. On your Android phone (Android 8.0 or later), sign in to GitHub and open the [Build Android APK runs](https://github.com/s1po0/Browser-translator/actions/workflows/build-apk.yml?query=branch%3Aarena%2F1fef4dd4-browser-translator). Open the latest successful signed build on the working branch.
2. Under **Artifacts**, download **sipan-signed-release.apk** and open it directly in Files. GitHub also lists the download in the run summary. **translating-browser-debug.apk** is a separate test build; older runs distribute it as a ZIP that you must extract first. If Android asks, allow installation from the file manager you just used. You can turn that permission off again afterwards.
3. Open **Sipan**. Use **Paste & open** on a copied link, type in the address bar, or try the sample page. Tap **Translate → Start translation**. Scroll for newly visible panels. Hold a translated image to peek at the original, or use **Show original** for the whole page. To save an original web image, switch to **Show original**, then long-press the image.

The debug APK is for testing (`dev.rojink.browser.debug`). GitHub's clean runners use debug signing keys, so a build from another run may not install over a previous debug build. Export bookmarks before uninstalling a debug build. Use the **signed release** APK (`dev.rojink.browser`) to update a signed RojInk or Sipan installation in place. Do not uninstall it first. The database migration keeps bookmarks, history, download records and the text cache.

## Release signing and key custody

**Do not commit or share the keystore, key passwords, or an unencrypted export.** If you already have a release key for `dev.rojink.browser`, keep using it. For the first release, generate an Android-compatible signing keystore in a trusted, private environment. Keep an independent encrypted backup in a vault you control; **GitHub Actions Secrets cannot be read back** as a backup. Store the passwords with the backup, not in the code or in chat.

The optional offline tool in `tools/signing-key/` can generate a protected `.p12` and copy-ready values locally. It can also re-encrypt a previous RojInk/Sipan export without changing the signing identity. To build it, run `npm ci --prefix tools/signing-key --ignore-scripts` and `npm run build --prefix tools/signing-key`; the generated HTML contains no saved keys and is ignored by Git.

Use the repository's **Settings → Secrets and variables → Actions → New repository secret** page (or authenticated `gh secret set` outside chat):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | The contents of your `.jks` or `.p12` keystore, base64-encoded (base64 is **not** encryption) |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | The signing certificate's alias |
| `ANDROID_KEY_PASSWORD` | The signing key's password (often the same as the store password for PKCS#12) |

On `arena/1fef4dd4-browser-translator`, a trusted push whose head commit message contains `[signed-apk]` requests a signed build. Regular pushes only build the debug APK. When the workflow is on the default branch, you can also go to **Actions → Build Android APK → Run workflow** and check **Also build the signed release APK**. The signing step reconstructs the keystore in a temporary file, deletes it at exit, verifies the APK with `apksigner`, and uploads **sipan-signed-release.apk**. Keep the same app ID and key for future releases; successful runs increase the APK version code with the workflow run number.

## Search the web or this device

- **Web search:** Type a word or question in the address bar and tap **Use web search** the first time. Subsequent address-bar searches in normal tabs open the public **Brave Search** results page inside Sipan. This uses [Brave’s web index](https://search.brave.com/help/independence), not Google Search or a Sipan crawler, with no API key or account. Brave receives your submitted query as part of normal website navigation. URLs and **Paste & open** still open links directly. Search results require internet access and depend on what Brave has indexed.
- **Country and Safe Search:** Open **Web search filters** in Sipan Search, above Brave results, or from the browser menu. Choose **Worldwide** or find a country by its English name, Sorani name, or code. Supported countries use Brave’s regional ranking (not a geographic restriction). **Country domains only** requests the country’s top-level domain instead of relying on ranking alone. For regions without a Brave preset, Sipan uses this domain mode automatically (e.g., Iraq uses `site:iq`; the United Kingdom’s domain is `.uk`). That can miss local `.com` sites; Brave may broaden results or show ads and other content outside the domain. This is a search preference, not a change to your connection or IP.
- **Safe Search** defaults to **Moderate**; choose **Strict** or **Off**. Off turns off Brave’s adult-content filter, not Android Safe Browsing, website restrictions, or network blocks. It cannot surface pages outside Brave’s index. Sipan applies the [documented preference cookies](https://search.brave.com/help/anonymous-cookies) to Brave in the current WebView profile before submitting a web query, with automatic IP-based local ranking turned off. Normal settings persist; private-tab settings start at Worldwide/Moderate and expire with the tab. **Apply** refreshes an open Brave results page or uses the new preferences for the next search from Sipan. Changing filters from the local search screen does not contact Brave or enable web search in the address bar.
- **On-device search:** Choose **Keep on-device search** or turn off **Settings → Web search from the address bar**. This mode searches Sipan’s 27-entry starter catalog, bookmarks, recent history and explicitly indexed pages. It works offline and sends no query. Use **On device** above web results to run the same term locally, or try **Search the web with Brave** on the on-device results screen without changing your default.
- **Add personal results:** On an eligible loaded page, use **Browser menu → Add page to Sipan Search** to save a local excerpt. Forms, editable fields and hidden text are skipped. Avoid adding sensitive pages. **Settings → Clear pages added to search** removes the excerpts without deleting bookmarks/history.
- **Private tabs and history:** A private address bar always starts with the local starter catalog and cannot add pages. Opening web results from a private tab is an explicit separate action and still sends the query to Brave, in the isolated WebView. Sipan does not add Brave results pages to its persistent History list, although you can go back to them within a tab. Visiting a result is normal browsing.

## Google services and privacy

- **No key required to try it.** The free `gtx` endpoint is unofficial; it can be slow, refuse requests, or change. If it stops working, retry later or use Cloud Translation. Unofficial and official endpoints can have different language coverage.
- **Optional Cloud Translation.** In a Google Cloud project, enable **Cloud Translation API**, enable billing, and create an API key restricted to that API. In **Sipan → Settings → Cloud Translation API key**, enter the key. Sipan uses it automatically for the official v2 API. Google may charge for use. This is **not** the Android signing key.
- **Optional Cloud Vision.** Enable **Cloud Vision API** in Google Cloud, create a restricted key, then add it in **Settings → Cloud Vision API key** and enable **Use Cloud Vision OCR**. This sends the selected image to Google for recognition. Without it, bundled ML Kit models read image pixels on the phone. For manga with wrong OCR, try selecting **Japanese**; for manhwa, try **Korean**. The translation source language is automatically detected separately.
- Nothing is sent to translation providers until you press **Start translation**. If you use on-device OCR, Google Translate gets the recognized text, not the image pixels. On-device search stays local. Optional web search sends the submitted term directly to Brave; Google is still only used for the translation path. The app has no accounts, own backend, or ads. API keys are encrypted with Android Keystore. The app removes ML Kit's bundled CCT telemetry backend registration; Android System WebView still has its own platform services.
- Normal translation strings and rendered images are cached locally. Clear them under **Settings → Clear browser and translation caches**. Private tabs use separate WebView profiles, skip browser history, personal search indexing and translation disk caches, and remove their profile on close (or next launch after an interrupted exit). They do not make browsing or translation anonymous.

## Image OCR and Sorani names

**Enhanced image OCR** is on by default for on-device reading. Sipan scores bundled ML Kit script results by confidence and matching letters, prioritizes Japanese/Korean alternatives in Auto when needed, and can retry up to three small/low-confidence tiles with scaled, contrast-adjusted pixels. The extra bitmap is bounded to about 2.35 megapixels. Detected regions are mapped back to the original image; retry pixels never appear in the saved art. Turn the extra preprocessing off in the Translate sheet or Settings for faster scans. Explicit **Japanese** and **Korean** modes are available, including in the gallery. Optional Cloud Vision is unaffected.

When the target is Sorani, an on-device output pass spells common character names that are still in Latin script, e.g. `Naruto` → `ناروتۆ` and `Sung Jin-Woo` → `سونگ جین وو`. It approximates unfamiliar name-like spellings, leaves detected URLs, email addresses, prices, and code-like strings alone, and does not call another translation service. Actual link destinations and the exact original view are unchanged.

## Other browser features

| Area | What's included |
| --- | --- |
| Browsing | Tabs/grid, optional Brave web search and independent on-device search, back/forward/reload, pull-to-refresh at the top of a page, find in page, desktop-site toggle, full-screen video, sharing, opening in another app |
| Local library | Room bookmarks and history, standard Netscape HTML bookmark import/export, system DownloadManager and download status |
| Images from phone | System photo picker (no broad storage access), the same OCR/translation pipeline, zoom, original/translation toggle, Save PNG and Share |
| Appearance | System/light/dark theme, adjustable web text size, English and Sorani app UI with mirrored layouts, bundled OFL Noto Naskh Arabic for Sorani typesetting |
| Privacy | Tracker domain blocklist and tab counter, isolated private tabs on compatible WebView versions, clear history/cookies/cache |

## Deviations and known limits

- Sipan uses `evaluateJavascript` to pull small snapshots from an explicitly selected top-level document. There is **no** `@JavascriptInterface` exposed to every frame. Originals are kept in a document-local map so handling inline links and restoring text never requires replacing arbitrary page HTML. Cross-origin iframes, closed shadow DOM, streaming video and JS-only text not accessible in the DOM are not translated. Translation pauses while the tab is hidden or showing the original, and resets on full navigation so a different page isn't translated without consent.
- Manga layout is inferred from OCR line/word geometry, not from a bubble segmentation model. Prices and other numeric-only fields are left in place. White/flat bubbles translate best; angled, stylized, very narrow vertical text and busy backgrounds can be missed. No additional PaddleOCR/Manga OCR model or bundled Arabic-script recognizer is shipped. Sorani name spellings are approximate; the app does not identify every name or undo names already translated semantically by the provider. Very large images are downsampled and scanned in overlapping tiles. If the translation does not fit legibly, the original region stays in place. Timing depends on the device, image size and network; it isn't a fixed number of seconds.
- Content Security Policy, authentication, hotlink protection and DRM can prevent a native duplicate image request or replacement. Cross-site media fetches omit cookies, unlike eligible same-site fetches. CSS handling targets single background images and canvas handling requires readable, untainted pixels. Some responsive canvases, animations, SVG-only content or unusually virtualized readers may stay untranslated. A data/blob `<img>` is supported when it can be captured locally. The app does not bypass paywalls or anti-bot controls.
- Private tabs require multi-profile support in the installed Android System WebView. If it is unavailable, Sipan doesn't silently reuse the normal profile. Downloads are OS-managed in app-specific storage, and don't forward raw cookies through DownloadManager redirects. Some logged-in downloads may need the site's own workflow or an external app. New views use the selected WebView theme; a page already open when changing the theme may need reopening.
- The app ships 249 Google Translate language choices (public picker codes and names, with native labels). Some variants aren't available on the same schedule on Cloud Translation v2 or the unofficial endpoint. Translation requires an internet connection; bundled OCR itself works locally.

## Develop and verify

Kotlin and Jetpack Compose Material 3, Android System WebView/AndroidX WebKit, Room, DataStore, OkHttp and coroutines. Build with JDK 17, Android SDK Platform 37 and build-tools 36.0.0:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
npm ci --prefix tools/browser-tests --ignore-scripts
npm test --prefix tools/browser-tests
# With an Android device or emulator attached:
./gradlew :app:connectedDebugAndroidTest
```

- **Build Android APK** builds and uploads an installable debug APK after every push and supports manual dispatch. The JVM tests exercise Google response parsing, batching/target selection with a mock web server, OCR layout/quality, local search ranking and private scope, web search URL encoding and filters, and Sorani spelling/protected spans. DOM tests cover consent gating, exact text restoration, dynamic content, responsive images, hold/release, and bounded index extraction.
- **Reader device smoke tests** use Android WebView and the real bundled OCR models with a deterministic test translation provider. They cover the Sorani controls and consent sheet, search opt-in and switching between web and on-device results, changing the country and Safe Search, real cookie stores on WebViews that support isolated profiles, explicit index extraction, the v1 → v2 library migration, exact WebView restoration, image swaps and hold/release, lazy loading, Japanese/Korean OCR, and stationary menu prices. They compare recognition on the same small, faded Japanese and Korean fixtures with the enhancement off and on. These tests do not depend on an API key or on a live website returning specific results.
- For a live end-to-end check on a phone: translate the sample headings into `ckb`, hold the comic image and release it, scroll to the menu and check prices, then try an English article and a chapter you can access. Try the explicit Japanese/Korean OCR models for that chapter. Verify the Google provider can be reached on your network.

### Roadmap / extension points

`web/DocumentFeatures` and `translation/TranslationProvider` / `OcrProvider` are the internal hooks for new browser tools or engines. Web extensions, ad SDK integration, sync, and accounts are deliberately absent. Any future ad SDK would require explicit review and separation from page data. Possible additions: bubble segmentation for difficult lettering and better support for animated or canvas-based readers.

The bundled Noto Naskh Arabic font license is in `app/src/main/assets/noto-naskh-ofl.txt`. The country names come from [Unicode CLDR 49](https://github.com/unicode-org/cldr-json), with the license bundled in `app/src/main/assets/cldr-license.txt`. The comic and menu assets are original test fixtures; their generator is under `tools/`.
