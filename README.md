# V-OCR

Turns a screen recording of scrolling content into one clean text file. English and French, fully on-device (PP-OCRv4 detection and recognition via ONNX Runtime). No internet permission, no account, no cloud.

## Build
The OCR models are not in the repo. CI fetches them (see `.github/workflows/build.yml`, step "Fetch OCR models"): they come from the `rapidocr-onnxruntime==1.4.4` Python package (`ch_PP-OCRv4_det_infer.onnx`, `ch_PP-OCRv4_rec_infer.onnx`) into `app/src/main/assets/ocr/` as `det.onnx`, `rec.onnx` and `keys.txt`. To build locally, run the same steps, then `gradle assembleDebug`.

## Accessibility service (auto-scroll) - what it does
The optional auto-scroll uses an Android accessibility service. It does two things:
1. Performs swipe gestures to scroll the page while you record.
2. After each swipe it takes a tiny grey screenshot (48 x 96 pixels, kept in memory only) and compares it with the previous one to detect that the page stopped moving (the bottom).
It does not read text or window content (`canRetrieveWindowContent="false"`), stores no screenshots, and sends nothing anywhere. The app asks for consent on a dedicated screen before sending the user to Accessibility settings. V-OCR works without it.

## Native libraries
ONNX Runtime 1.22.0 (arm64-v8a and x86_64 libraries are 16 KB page-size aligned).

## License
Apache-2.0
