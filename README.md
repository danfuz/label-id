# Label ID

Desktop prototype for checking whether text read from an alcohol label image matches pasted COLA/application text.

The app is intentionally offline-first. Version 1 uses a local OCR ensemble by default, running several Tesseract page segmentation modes and falling through to one optional RapidOCR PP-OCRv5 pass only when the faster sources have not already passed verification. OCR/model work sits behind an `ImageTextReader` interface so another local model or hybrid pipeline can be added later without changing the UI.

See `DOCUMENTATION.md` for a short overview of the architecture, tools, and OCR ensemble strategy.

## Verification Ensemble

Label ID separates OCR sources from verification match modes.

The default OCR ensemble reads each image progressively: Tesseract `--psm 3`, `--psm 4`, `--psm 6`, `--psm 11`, `--psm 12`, then one RapidOCR source configured for PP-OCRv5 mobile ONNX models. After each OCR source succeeds, the verifier checks the accumulated OCR text; if the label already passes, later sources are skipped. For every verification field, the verifier checks the available OCR sources and chooses the strongest result for that field. If RapidOCR is needed but not installed or times out, the app records that in OCR diagnostics and returns the best result from the sources that did run.

Ordinary text fields are checked with several match modes:

- `raw`: exact text after whitespace cleanup only
- `nfkc-normalized`: Unicode compatibility normalization, case folding, punctuation cleanup, and apostrophe tolerance
- `token-window`: ordered non-contiguous token matching with strict locality
- `similarity`: close OCR matches are marked REVIEW, not PASS

Numeric fields such as alcohol content and net contents use normalized numeric parsing.

The required government warning is intentionally stricter than ordinary fields. `GOVERNMENT WARNING:` must be confirmed from an OCR source in raw uppercase form, with the existing close split allowance for `GOVERNMENT` and `WARNING:`. The rest of the warning statement is checked with normalized token evidence and required anchor words aggregated across the OCR ensemble. Warning anchors accept whole-token OCR matches with edit distance up to one character. Boldness, type size, placement, and contrast are outside v1 OCR scope and are not reported as a separate verification field.

## Requirements

- Windows 10/11, macOS, or Linux
- JDK 21 or newer
- Git
- Tesseract installed on `PATH`
- Python 3.10+ for optional RapidOCR support
- Gradle 8.x or newer, or use the included Gradle wrapper

## Windows Setup

These commands are intended for a fresh Windows 10 or Windows 11 install using PowerShell. If `winget` is missing, install or update **App Installer** from the Microsoft Store, or use the help link printed by Windows: <https://aka.ms/winget-command-install>.

Install Git, JDK 21, Python, and Tesseract. Keep each command on one line; `--accept-package-agreements` and `--accept-source-agreements` are winget flags, not separate commands.

```powershell
winget install --id Git.Git -e --source winget --accept-package-agreements --accept-source-agreements
winget install --id EclipseAdoptium.Temurin.21.JDK -e --source winget --accept-package-agreements --accept-source-agreements
winget install --id Python.Python.3.12 -e --source winget --accept-package-agreements --accept-source-agreements
winget install --id UB-Mannheim.TesseractOCR -e --source winget --accept-package-agreements --accept-source-agreements
```

Close and reopen PowerShell, then verify the installs:

```powershell
git --version
java -version
py -3 --version
where.exe tesseract
tesseract --version
```

If `where.exe tesseract` cannot find Tesseract but winget says it is installed, add the common install directory to your user `PATH`:

```powershell
$tessDir = "C:\Program Files\Tesseract-OCR"
Test-Path "$tessDir\tesseract.exe"
$userPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($userPath -notlike "*$tessDir*") { [Environment]::SetEnvironmentVariable("Path", "$userPath;$tessDir", "User") }
$env:Path = "$env:Path;$tessDir"
where.exe tesseract
```

Set up the optional RapidOCR venv used by the final ensemble fallback source:

```powershell
$venv = "$env:LOCALAPPDATA\label-id\rapidocr-venv"
py -3 -m venv $venv
& "$venv\Scripts\python.exe" -m pip install --upgrade pip
& "$venv\Scripts\python.exe" -m pip install rapidocr onnxruntime
[Environment]::SetEnvironmentVariable("LABEL_ID_RAPIDOCR_PYTHON", "$venv\Scripts\python.exe", "User")
$env:LABEL_ID_RAPIDOCR_PYTHON = "$venv\Scripts\python.exe"
& $env:LABEL_ID_RAPIDOCR_PYTHON -c "import rapidocr, onnxruntime; print('rapidocr ok')"
```

On the first OCR run, RapidOCR downloads the PP-OCRv5 ONNX models into the Python environment it is using. Set `LABEL_ID_RAPIDOCR_TIMEOUT_SECONDS` if you need to override the default 20 second timeout:

```powershell
[Environment]::SetEnvironmentVariable("LABEL_ID_RAPIDOCR_TIMEOUT_SECONDS", "45", "User")
```

Clone and run the app:

```powershell
git clone https://github.com/danfuz/label-id.git
cd label-id
.\gradlew.bat run
```

If you already have the repo:

```powershell
cd C:\path\to\label-id
git fetch
git pull
.\gradlew.bat run
```

Run the test suite:

```powershell
.\gradlew.bat test
```

Run the OCR comparison report:

```powershell
.\gradlew.bat test --tests labelid.ocr.OcrComparisonTest
```

The app opens a desktop window. Choose one label image, paste COLA/application text, and select **Verify Label**. In normal mode, raw OCR text and OCR diagnostics are hidden from the results panel; set `labelid.debug` to `true` in `src/main/kotlin/labelid/Debug.kt` when debugging OCR output locally.

## RapidOCR Setup For macOS/Linux

Optional RapidOCR setup for the PP-OCRv5 ensemble source:

```bash
python3 -m venv /tmp/label-id-rapidocr-venv
/tmp/label-id-rapidocr-venv/bin/python -m pip install rapidocr onnxruntime
export LABEL_ID_RAPIDOCR_PYTHON=/tmp/label-id-rapidocr-venv/bin/python
```

Set `LABEL_ID_RAPIDOCR_TIMEOUT_SECONDS` to override the default 20 second RapidOCR timeout.

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

The comparison report is written to `build/reports/ocr-comparison/abc-single-barrel-straight-rye-whisky.md`. It runs several Tesseract page segmentation modes plus RapidOCR when configured.

## Package

```bash
./gradlew packageDistributionForCurrentOS
```

Native packages are generated under `build/compose/binaries`. Version 1 expects Tesseract to be installed on the target machine. A future all-in-one package can bundle an OCR runtime per operating system.

## Current Scope

- Single image at a time
- Paste COLA/application text into one text box
- Parse known COLA fields such as Brand Name, Fanciful Name, Net Contents, and Alcohol Content
- OCR the label image progressively through Tesseract `--psm 3`, `--psm 4`, `--psm 6`, `--psm 11`, `--psm 12`, and optional RapidOCR PP-OCRv5 when the faster sources do not pass
- Score each normal verification field against each OCR source and match mode, then use the strongest result for that field
- Verify parsed fields plus the required government warning statement
- Tolerate case, whitespace, Unicode compatibility differences, common punctuation, apostrophe differences, and limited ordered non-contiguous OCR splits for normal text fields
- Require raw uppercase OCR evidence for the `GOVERNMENT WARNING:` heading while aggregating warning anchors across the ensemble

See `DOCUMENTATION.md` for the project overview. Local agent implementation notes are kept under `agent/`.
