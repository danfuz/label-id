# Label ID

Desktop prototype for checking whether text read from an alcohol label image matches pasted COLA/application text.

The app is intentionally offline-first. Version 1 uses Tesseract as the default OCR engine, but all OCR/model work sits behind an `ImageTextReader` interface so another local model or hybrid pipeline can be added later without changing the UI or verification logic.

## Requirements

- JDK 21 or newer
- Gradle 8.x or newer, or use the included Gradle wrapper
- Tesseract OCR installed and available on `PATH`

Tesseract install examples:

- macOS: `brew install tesseract`
- Ubuntu/Debian: `sudo apt install tesseract-ocr`
- Windows: install Tesseract, then add the install directory containing `tesseract.exe` to `PATH`

Verify Tesseract:

```bash
tesseract --version
```

## Run

```bash
./gradlew run
```

The app opens a desktop window. Choose one label image, paste COLA/application text, and select **Verify Label**.

## Test

```bash
./gradlew test
```

Tests cover parsing, matching, verification behavior, and the Tesseract adapter. The Tesseract integration test generates a local PNG and is skipped automatically if `tesseract` is not installed on `PATH`.

## Package

```bash
./gradlew packageDistributionForCurrentOS
```

Native packages are generated under `build/compose/binaries`. Version 1 documents Tesseract as an external dependency. A future all-in-one package can bundle Tesseract binaries per operating system.

## Current Scope

- Single image at a time
- Paste COLA/application text into one text box
- Parse known COLA fields such as Brand Name, Fanciful Name, Net Contents, and Alcohol Content
- OCR the label image through Tesseract
- Verify parsed fields plus the required government warning statement
- Tolerate case, whitespace, common punctuation, and apostrophe differences

See `agent/architecture.md`, `agent/apis.md`, `agent/features.md`, `agent/testing.md`, and `agent/issues.md` for implementation notes and known limitations.
