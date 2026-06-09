# PDD Price Monitor

Android 10+ personal-use Kotlin sample app for capturing Pinduoduo product prices with a floating OCR button.

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
2. If prompted, enable floating window permission for `PDD Price Monitor`.
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

## Notes

This is a personal-use sample. Commercial use against third-party apps may violate their terms. OCR accuracy depends heavily on page layout, font size, screenshots, and Pinduoduo UI changes. For real daily use, tune `ProductTextParser` and `TitleMatcher.threshold`.
