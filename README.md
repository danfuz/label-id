# Label ID

Desktop prototype for checking whether text read from an alcohol label image matches pasted COLA/application text.

The app is intentionally offline-first. Version 1 uses a local Tesseract OCR ensemble by default, running several page segmentation modes against the same image. OCR/model work sits behind an `ImageTextReader` interface so another local model or hybrid pipeline can be added later without changing the UI.

## Verification Ensemble

Label ID separates OCR sources from verification match modes.

The default OCR ensemble reads each image through Tesseract `--psm 3`, `--psm 4`, `--psm 6`, `--psm 11`, and `--psm 12`. For every verification field, the verifier checks every OCR source and chooses the strongest result for that field. PaddleOCR remains available as an optional adapter and comparison engine, but it is not required for the desktop app.

Ordinary text fields are checked with several match modes:

- `raw`: exact text after whitespace cleanup only
- `nfkc-normalized`: Unicode compatibility normalization, case folding, punctuation cleanup, and apostrophe tolerance
- `token-window`: ordered non-contiguous token matching with strict locality
- `similarity`: close OCR matches are marked REVIEW, not PASS

Numeric fields such as alcohol content and net contents use normalized numeric parsing.

The required government warning is intentionally stricter than ordinary fields. `GOVERNMENT WARNING:` must be confirmed from an OCR source in raw uppercase form, with the existing close split allowance for `GOVERNMENT` and `WARNING:`. The rest of the warning statement is checked with normalized token evidence and required anchor words aggregated across the OCR ensemble. Boldness, type size, placement, and contrast are outside v1 OCR scope and are not reported as a separate verification field.

## Requirements

- JDK 21 or newer
- Gradle 8.x or newer, or use the included Gradle wrapper
- Tesseract installed on `PATH`

Optional Linux PaddleOCR setup for comparison tests or custom OCR wiring:

```bash
python3 -m venv /tmp/label-id-paddleocr-venv
/tmp/label-id-paddleocr-venv/bin/python -m pip install paddlepaddle==3.2.0 -i https://www.paddlepaddle.org.cn/packages/stable/cpu/
/tmp/label-id-paddleocr-venv/bin/python -m pip install paddleocr
```

Point the comparison test or custom adapter at that Python executable:

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

To compare OCR engines against the checked-in ABC sample label, run:

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

Native packages are generated under `build/compose/binaries`. Version 1 expects Tesseract to be installed on the target machine. A future all-in-one package can bundle an OCR runtime per operating system.

## Current Scope

- Single image at a time
- Paste COLA/application text into one text box
- Parse known COLA fields such as Brand Name, Fanciful Name, Net Contents, and Alcohol Content
- OCR the label image through Tesseract `--psm 3`, `--psm 4`, `--psm 6`, `--psm 11`, and `--psm 12`
- Score each normal verification field against each OCR source and match mode, then use the strongest result for that field
- Verify parsed fields plus the required government warning statement
- Tolerate case, whitespace, Unicode compatibility differences, common punctuation, apostrophe differences, and limited ordered non-contiguous OCR splits for normal text fields
- Require raw uppercase OCR evidence for the `GOVERNMENT WARNING:` heading while aggregating warning anchors across the ensemble

See `agent/architecture.md`, `agent/apis.md`, `agent/features.md`, `agent/testing.md`, and `agent/issues.md` for implementation notes and known limitations.
