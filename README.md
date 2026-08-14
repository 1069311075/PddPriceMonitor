# 我爱拼多多 (PDD Price Monitor)

Android 10+ personal-use Kotlin sample app for capturing Pinduoduo product prices with a floating OCR button.

## Why

You see something you like on Pinduoduo — a phone, a pair of shoes, a kitchen gadget. The price looks reasonable, so you think: *I'll come back later.*

Then one of three things happens:

- **You screenshot it.** The screenshot sinks into your gallery among hundreds of others. A week later you can't even remember which one it was, let alone what it cost.
- **You type it into a notes app.** Switch apps, copy the title, type the price, switch back — every time it's a hassle, and most people give up after the second product.
- **You don't record it at all.** The most common case. The next time you see it, you think "wasn't it cheaper before?" — and you have nothing to check against. You buy at the wrong moment, or you hesitate and miss the real deal.

The problem isn't that you forget. It's that **price memory has no anchor** — no history, no trend, no way to know if *now* is the right time to buy.

This app is a tiny fix for that: tap the floating button on any product page, and the title and price are saved into a local history with a price trend. Next time you see the same product, you instantly know whether it's cheaper, more expensive, or at its lowest — no screenshots, no notes, no guessing.

## What is included

- MediaProjection foreground service for on-demand screen capture.
- ML Kit Chinese OCR for title and price recognition.
- Levenshtein fuzzy title matching.
- Room/SQLite database with insert, lower-price update, query, and descending update-time sort.
- Floating overlay button: tap once on a product page to OCR the current screen.
- Editable floating result panel: fix title or price before saving.
- Basic Compose UI for history, latest price, update time, and debug status.

## Import

1. Open Android Studio.
2. Choose `Open`.
3. Select this directory: `PddPriceMonitor`.
4. Let Android Studio sync Gradle.
5. Run on Android 10+ device.

## First run permissions

Android does not allow apps to silently enable screen recording or accessibility.

1. Start the app.
2. If prompted, enable floating window permission for `我爱拼多多`.
3. Approve the screen capture dialog.
4. Tap `Open PDD`.
5. On a product page, tap the floating `OCR` button.
6. Review the recognized title and price, edit if needed, then tap `Save`.

## Important files

- `app/src/main/java/com/example/pddpricemonitor/capture/ScreenCaptureService.kt`
  - MediaProjection setup, ImageReader screenshot capture, foreground service, floating OCR button, editable result panel.
- `app/src/main/java/com/example/pddpricemonitor/capture/FrameDiffer.kt`
  - Screen-change region detection.
- `app/src/main/java/com/example/pddpricemonitor/ocr/TextRecognizerClient.kt`
  - Google ML Kit OCR wrapper.
- `app/src/main/java/com/example/pddpricemonitor/ocr/ProductTextParser.kt`
  - Product title and price extraction.
- `app/src/main/java/com/example/pddpricemonitor/matcher/TitleMatcher.kt`
  - Levenshtein fuzzy matching.
- `app/src/main/java/com/example/pddpricemonitor/data/ProductRepository.kt`
  - Insert/update product history. Manual floating-panel saves update the stored price directly.
- `app/src/main/java/com/example/pddpricemonitor/data/AppDatabase.kt`
  - Room/SQLite database.

## Usage & Compliance

This project is designed as a **manual, on-demand, local-only** tool:

- **Manual trigger**: Screen capture and OCR run only when you tap the floating button. There is no background automation, no scheduled scraping, and no continuous monitoring.
- **Local storage**: All recognized data is stored only in your device's local database. Nothing is uploaded to any server or shared with any third party.
- **Official APIs only**: The app uses only official Android APIs (MediaProjection, ML Kit). It does not crack, inject, modify, or bypass any technical protection of third-party apps.
- **Accessibility service**: Used solely to detect whether Pinduoduo is in the foreground (to reduce unnecessary capture). It does not read, inject, or alter any app data.

Scope of use:

- **Personal, non-commercial use only.** Do not use this project for commercial services, mass data collection, or any activity that competes with or harms third-party platforms.
- Third-party app terms of service may restrict data collection. Manual capture for personal note-taking is generally not enforceable against individuals, but the line shifts if you provide commercial services or add automated collection.
- If you plan to add automated monitoring (e.g., background detection + price-drop push), the behavior changes from "user-initiated capture" to "programmatic collection" — reassess compliance before doing so.

This project is provided "as is" for learning purposes. You are responsible for how you use it.

## Notes

OCR accuracy depends heavily on page layout, font size, screenshots, and Pinduoduo UI changes. For real daily use, tune `ProductTextParser` and `TitleMatcher.threshold`.
