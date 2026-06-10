# Label ID Documentation

## Approach

Label ID is an offline-first desktop verifier for alcohol label text. The user selects one label image, pastes the expected COLA/application text, and the app reports whether the label appears to contain the required fields and government warning text.

The application keeps OCR behind a small interface:

```kotlin
interface ImageTextReader {
    suspend fun readImage(image: ImageInput): ImageText
}
```

That boundary keeps the desktop UI and verification rules independent from any single OCR engine. Tesseract, RapidOCR, a local vision model, or a future hosted model can be swapped in by implementing the same contract.

## Solution

The verifier uses an ensemble of many light OCR sources instead of relying on one heavy model. By running several fast Tesseract page segmentation modes first, the app gets different readings of the same image while keeping runtime low. RapidOCR PP-OCRv5 is available as a heavier fallback and runs last. After each OCR source succeeds, the verifier checks the accumulated result; if the label already passes, later sources are skipped.

This gives the app two useful properties:

- Better accuracy than a single OCR pass, because fields can pass from whichever source reads them best.
- Better speed than always running every engine, because the slower RapidOCR pass is skipped when Tesseract sources are enough.

Normal label fields are matched with several modes: raw text, Unicode/case/punctuation normalization, ordered token-window matching for line breaks or OCR splits, and similarity review for close but uncertain results.

The government warning check is stricter. The `GOVERNMENT WARNING:` heading must be seen in raw uppercase OCR text, while the rest of the warning is checked through normalized token evidence and required anchor words. Warning anchors tolerate one OCR character error per token.

## Tools

- Kotlin/JVM for application code.
- Compose Desktop for the Windows/macOS/Linux UI.
- Gradle wrapper for reproducible builds and tests.
- Tesseract CLI for lightweight local OCR.
- RapidOCR with ONNX Runtime and PP-OCRv5 mobile models as the optional fallback OCR source.
- Kotlin test/JUnit for parser, verifier, OCR adapter, and ensemble behavior tests.

## Current Limits

- One image is verified at a time.
- Tesseract must be installed on `PATH`.
- RapidOCR requires a Python virtual environment when enabled.
- The app checks warning text, but not visual style details such as boldness, font size, placement, or contrast.
- Raw OCR text and diagnostics are hidden by default unless the global `debug` flag is set to `true`.
