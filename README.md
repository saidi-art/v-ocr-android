# V-OCR

**If you can scroll it, you can save it.**

V-OCR turns a screen recording of scrolling content into clean, searchable text. Record a chat, a thread or a long page while you scroll, and get back one stitched text file with the repeated parts removed. Everything runs on your phone.

> **Status: MVP.** Built and tested on a Samsung Galaxy S24 Ultra. English and French only. See [Honest status](#honest-status).

## What it does

- Records your screen while you scroll, or scrolls for you.
- Picks the frames that matter and stitches them into a continuous strip.
- Reads the text with an open-source OCR model that runs on the device.
- Removes the overlap between frames so each line appears once.
- Names the file from its content and saves it to `Downloads/V-OCR/`.
- Queues work: add several videos at once (ADD, top right) or keep recording while earlier recordings are read. Jobs run one at a time, and each result is its own `.txt` you can open or share as a file.

No account, no upload, no per-page fees. Your recording never leaves the phone.

## How it works

1. **Record.** Tap the floating recorder bubble and scroll. A ring on the bubble shows progress. Long-press it for the menu: close, Fast/Slow mode, Auto-scroll.
2. **Extract.** V-OCR samples the video, keeps frames that add new content, and builds strips. Fast flings are handled by sampling more often when the screen jumps a long way between frames.
3. **Read.** An on-device PP-OCRv4 detector and recognizer (ONNX Runtime) read each strip, with word-spacing recovery.
4. **Deduplicate.** Lines that repeat across overlapping strips are removed.
5. **Save.** The text is saved as a `.txt` file, titled `<header> - <first meaningful line>` (max 60 characters). If no clean line is found, it falls back to `V-OCR <date time>`.

### Auto-scroll

With Auto-scroll on, V-OCR scrolls the page for you at a steady pace while recording, and stops by itself after a time cap (5 minutes by default).

It uses an Android Accessibility Service that only performs swipe gestures. It does not read your screen content. You enable it once in Settings > Accessibility, and you can turn it off there at any time.

### Fast and Normal modes

| | Normal (default) | Fast |
|---|---|---|
| Recognition input | full size | smaller |
| Speed | baseline | roughly 20-28% quicker |
| Accuracy | best | a little lower, with more character-level slips |

Use Normal when accuracy matters. Use Fast when you need the text sooner and can tolerate small slips.

## Install

1. Open the [latest release](https://github.com/saidi-art/v-ocr-android/releases/latest).
2. Download `vocr-release-<N>.apk` on your phone.
3. Allow installs from your browser or file manager if Android asks, then open the APK.
4. Grant the screen-capture and notification prompts when the recorder starts.

Requires Android 10 (API 29) or newer.

Each push to `main` builds a new release (`build-N`) with a release APK and a debug APK.

## Honest status

- **MVP.** It works end to end, and the UI is still changing.
- **Tested on one device**, a Galaxy S24 Ultra. Other phones are untested.
- **EN and FR only.**
- **Fast mode makes small mistakes.** Expect dropped spaces and the odd wrong letter in words.
- **Speed depends on heat.** On the test phone a 126-strip run took about 185-215 s in Fast and 255-290 s in Normal. The same strip took 1.8x longer on a hot phone than a cool one, so long sessions get slower.
- **Very fast flings** can lose lines. On a deliberately extreme test clip (about 30 screens per second) recall was about 90%.
- **Auto-scroll and the 5-minute cap** are new and have had little real-device testing.
- Two recordings read at once would be slower each, so the queue reads one at a time. Recording while a job is reading slows both.
- Recordings of V-OCR's own result screen will return their own text, as expected.

## Build from source

The OCR models are not stored in the repo. Fetch them first (this is what CI does), then build:

```
git clone https://github.com/saidi-art/v-ocr-android
cd v-ocr-android
pip install rapidocr-onnxruntime==1.4.4
M=$(python -c "import rapidocr_onnxruntime,os;print(os.path.join(os.path.dirname(rapidocr_onnxruntime.__file__),'models'))")
mkdir -p app/src/main/assets/ocr
cp "$M/ch_PP-OCRv4_det_infer.onnx" app/src/main/assets/ocr/det.onnx
cp "$M/ch_PP-OCRv4_rec_infer.onnx" app/src/main/assets/ocr/rec.onnx
python -c "import onnxruntime as o;s=o.InferenceSession('app/src/main/assets/ocr/rec.onnx');open('app/src/main/assets/ocr/keys.txt','w').write(s.get_modelmeta().custom_metadata_map['character'])"
gradle assembleDebug   # Gradle 8.7, JDK 17
```

Kotlin, Jetpack Compose, ONNX Runtime. Four ONNX threads gave the best speed on the test phone, and more threads were slower.

## Roadmap

- End-of-page detection for auto-scroll.
- Cool-phone benchmarks and more devices.
- More languages.

## License

Apache-2.0. See [LICENSE](LICENSE). The OCR models come from the PaddleOCR project, which is also Apache-2.0.
