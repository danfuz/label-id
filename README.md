# Label ID

Desktop prototype for checking whether text read from an alcohol label image matches pasted COLA/application text.

The app is intentionally offline-first. Version 1 uses a local OCR ensemble by default: PaddleOCR plus Tesseract page segmentation mode 11 when Tesseract is installed. OCR/model work sits behind an `ImageTextReader` interface so another local model or hybrid pipeline can be added later without changing the UI.

## Verification Ensemble

Label ID separates OCR sources from verification match modes.

The default OCR ensemble reads each image through PaddleOCR and, when installed, Tesseract `--psm 11`. PaddleOCR contributes its normal recognition order plus a spatially grouped source built from OCR word boxes. For ordinary label fields, the verifier checks every OCR source and chooses the strongest result for each field.

Ordinary text fields are checked with several match modes:

- `raw`: exact text after whitespace cleanup only
- `nfkc-normalized`: Unicode compatibility normalization, case folding, punctuation cleanup, and apostrophe tolerance
- `token-window`: ordered non-contiguous token matching with strict locality
- `similarity`: close OCR matches are marked REVIEW, not PASS

Numeric fields such as alcohol content and net contents use normalized numeric parsing.

The required government warning is intentionally stricter. `GOVERNMENT WARNING:` must be confirmed from PaddleOCR output in raw uppercase form, with the existing close split allowance for `GOVERNMENT` and `WARNING:`. Tesseract is not allowed to authorize or rescue that check because it can normalize casing in ways that create false positives. Boldness, type size, placement, and contrast remain visual checks outside v1 OCR scope.

## Requirements

- JDK 21 or newer
- Gradle 8.x or newer, or use the included Gradle wrapper
- Python with PaddleOCR installed
- Tesseract installed on `PATH` for the optional second OCR pass. If Tesseract is missing, PaddleOCR can still run by itself.

Example Linux PaddleOCR setup:

```bash
python3 -m venv /tmp/label-id-paddleocr-venv
/tmp/label-id-paddleocr-venv/bin/python -m pip install paddlepaddle==3.2.0 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
/tmp/label-id-paddleocr-venv/bin/python -m pip install paddleocr
```

Point the app at that Python executable:

```bash
export LABEL_ID_PADDLEOCR_PYTHON=/tmp/label-id-paddleocr-venv/bin/python
export LABEL_ID_PADDLEOCR_CACHE_HOME=/tmp/label-id-paddlex-cache
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

Tests cover parsing, matching, verification behavior, the OCR ensemble, and the Tesseract adapter. The Tesseract integration test generates a local PNG and is skipped automatically if `tesseract` is not installed on `PATH`.

To compare OCR engines against the checked-in ABC sample label, install Tesseract as an optional comparison engine and run:

```bash
./gradlew test --tests labelid.ocr.OcrComparisonTest
```

The comparison report is written to `build/reports/ocr-comparison/abc-single-barrel-straight-rye-whisky.md`. It runs several Tesseract page segmentation modes and PaddleOCR variants when configured.

Point the comparison test at the PaddleOCR Python executable:

```bash
LABEL_ID_PADDLEOCR_PYTHON=/tmp/label-id-paddleocr-venv/bin/python \
LABEL_ID_PADDLEOCR_CACHE_HOME=/tmp/label-id-paddlex-cache \
./gradlew test --tests labelid.ocr.OcrComparisonTest
```

To use a custom PaddleOCR wrapper instead, set `LABEL_ID_PADDLEOCR_COMMAND` with `{image}` where the image path should be inserted.

## Package

```bash
./gradlew packageDistributionForCurrentOS
```

Native packages are generated under `build/compose/binaries`. Version 1 documents PaddleOCR as an external dependency. A future all-in-one package can bundle an OCR runtime per operating system.

## Current Scope

- Single image at a time
- Paste COLA/application text into one text box
- Parse known COLA fields such as Brand Name, Fanciful Name, Net Contents, and Alcohol Content
- OCR the label image through PaddleOCR and Tesseract `--psm 11` when both are available
- Score each normal verification field against each OCR source and match mode, then use the strongest result for that field
- Verify parsed fields plus the required government warning statement
- Tolerate case, whitespace, Unicode compatibility differences, common punctuation, apostrophe differences, and limited ordered non-contiguous OCR splits for normal text fields
- Require PaddleOCR evidence for the raw `GOVERNMENT WARNING:` heading in uppercase; bold and positioning are not assessed in v1

See `agent/architecture.md`, `agent/apis.md`, `agent/features.md`, `agent/testing.md`, and `agent/issues.md` for implementation notes and known limitations.
