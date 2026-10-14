# V-OCR bench handoff (rev 3) - for the system engineering on-board

Standalone summary for a reviewer who has not followed the project. Date: Oct 14, 2026.

Legend: [MEASURED] run and scored by us. [PROPOSED] designed or reasoned, not run. [NOT VERIFIED] needs a device or a recording we do not have.
All timings are from an x86 sandbox (2 cores, 2 GB RAM) and are NOT Samsung S24 numbers. Nothing here has been run on a device unless stated.

## 1. System under test
- Android app (Kotlin/Compose, package app.vocr), consumer name ScrOllOn, engine V-OCR. It turns a screen recording of scrolling content into stitched, deduplicated text.
- On-device OCR: rapidocr PP-OCRv4 detector + recognizer on ONNX Runtime 1.22.0, 6,600-class recognition head.
- Pipeline today: decode video -> extract frames/strips -> detect text lines -> recognize -> stitch (pixel-overlap dedup) -> save .txt to Downloads/V-OCR.
- Repo: https://github.com/saidi-art/v-ocr-android (public). Releases: build-N / vocr-release-N.apk. Locked tags: locked-build57 (d478dca), locked-build55 (2de4b28). Build 45 is the Play candidate (no Play decision yet).
- Bench inputs: one 5.7 s "fling" clip of a chat scroll (50 fps, 720x1560) with ground-truth text. Baseline: 2,332 lines, word-sequence recall 0.905, recognition 231.1 s, detection 39.3 s. Recognition is about 82% of runtime. Recall 0.905 is the guardrail: a change must hold it or ship as a declared tradeoff.
- The clip and ground truth are kept in a private store, not in the public repo.

## 2. Bench state
### 2.1 Recognition height (recH) sweep [MEASURED, lab]
| recH | recall | recognition time | note |
|---|---|---|---|
| 48 | 0.905 | 231.1 s | baseline, "Slow" tier |
| 42 | 0.898 | 186.3 s (-19.4%) | "Fast" tier |
| 38 | 0.879 | 196.5 s | measured on an earlier machine, not comparable |
| 32 | 0.609 | - | disqualified |
Gate 1 (PASSED): a loss from lowering recH must be substitution, not missing text. If more than 10% of the lost words were missing lines, the plan would have pivoted to reducing detector input size. At recH 42, 97.4% of lost words sit in substituted lines and 2.6% in missing lines (287 substituted, 8 missing/empty of 3,307 lines). At recH 32 the split is 98.6% / 1.4%. Correction of an earlier internal claim: the recH 32 loss is not "missing lines". CTC confidence is a poor error flag: 1,137 of 3,069 lines with confidence >= 0.95 changed at recH 32.
Decision (product owner): Fast = recH 42, Slow = recH 48. Fast default OFF, with the manual line "play safe, use Slow when the data matters". [NOT VERIFIED on the S24.]

### 2.2 Other speed experiments [MEASURED, lab]
- Batching 6 lines per recognizer call: recall 0.898, 13% slower. Rejected.
- INT8 dynamic MatMul: recall 0.905, 221.4 s (-4.2%). Static INT8 declined (low expected gain).
- Polarity (inverted-theme) handling: recall 0.908 / 0.905, no loss.
- Execution-provider order (CPU/NNAPI/XNNPACK): not decided; to be set only from on-device Speed test numbers. The Speed test is in the held wrap build.

### 2.3 Gate 2: language head (decided: full head stays)
Trimmed heads, lab: EN+FR (120 classes) 220.0 s, EN-only (96 classes) 220.8 s, versus 220-231 s full: about 4.5% speed and about 3 MB, same recall on English [MEASURED]. French proxy (synthetic, 25 sentences x 2 fonts, not real captures): char match 0.951 full / 0.951 EN+FR / 0.927 EN-only; accented-char recall 0.35 / 0.35 / 0.00 [MEASURED, synthetic]. Decision (product owner): keep the full 6,600-class head; trims shelved.

### 2.4 Overlap dedup by pixel similarity (SAD) [MEASURED, lab]
2,831 lines checked, 33.5% have a true duplicate in the previous strip. SAD = sum of 256 absolute differences on a 16x16 grayscale line thumbnail.
| SAD threshold | precision | wrongly skipped real lines | duplicates caught |
|---|---|---|---|
| 1800 | 1.000 | 0 | 18.6% |
| 2500 | 0.931 | 19 | 26.9% |
| 3500 | 0.894 | 47 | 42.0% |
| 5000 | 0.890 | 75 | 64.2% |
Verdict: no single threshold is both safe and effective; pixel overlap alone is insufficient. One clip, adjacent strips only, labels from OCR text.

## 3. Field finding: build-57 capture (the new evidence)
A user capture from build 57 of a YouTube comments scroll, 7,252 output lines (text only; the video was NOT recovered, see section 6).
[MEASURED on the text]
- 821 exact-unique lines; 586 after case/punctuation normalization; about 332-430 distinct after fuzzy clustering (ratio > 0.8). Real duplication about 94-95%, versus about 29% on the lab clip.
- Single lines repeat 150-226 times. The output is a concatenation of per-frame screens (the header "Comments" recurs 109 times in the first 979 lines, blocks of 8-9 lines, one viewport each): stitching is not collapsing frames on this content.
- New content arrives unevenly: lines 720-3600 add about 40 new lines.
- About 345 lines are 3 characters or fewer, and 150 clusters occur once, mostly garble (e.g. "Wiu udt vemy sdiu"); about 5-7% of output (upper estimate).
- OCR variants of one comment defeat exact matching ("while/whil leaning", "Eis/E is number 1").
[HYPOTHESES, not tested]: (1) no cross-frame text dedup on stationary frames; (2) exact keys broken by OCR noise; (3) the playing video above the comments is recognized as text and, because it always changes, can never look stationary; (4) the comments sheet is a small viewport (about 8 lines), so any pause re-emits a whole screen. The user reports the video was in frame, very little text, and a long processing time, which fits (3).

## 4. Proposed engine: "Scrolling OCR" [PROPOSED]
Product direction (user decision): the engine is the product; the engine build is the priority track; the wrap build stays held; no naming yet.
Pipeline, in this order:
1. Video-region masking: separate non-rigid per-frame change (video) from static or rigidly scrolling content, before anything else.
2. Stationary-frame skip: frame-diff the unmasked region only; skip OCR when scroll offset change is about zero. The only step that saves recognition time.
3. OCR (existing PP-OCRv4, Fast/Slow tiers).
4. Fuzzy text dedup at stitch level (normalized text, edit similarity), complementing SAD.
Order matters: without masking, a playing video prevents step 2 from ever firing.
Projected impact on the capture: step 4 takes 7,252 lines to about 430 [MEASURED on the text]. Time saved by steps 1-2 [PROPOSED]: most recognized lines came from frames that added nothing new, but the exact saving needs the frame count of the recording; lab-speed conversions are about 0.1 s/line and not S24 numbers.

## 5. Prototype results on the lab clip (no-regression only; the clip has no video)
### 5.1 Fuzzy text dedup [MEASURED, PASSES]
Keep a line only if no similar line (normalized text, difflib ratio) is among the last W kept lines. Lines under 6 normalized chars are exempt.
| W | ratio | lines | recall |
|---|---|---|---|
| none | - | 2,332 | 0.905 |
| 30 | 0.8 | 1,995 (-14.5%) | 0.905 |
| 100 | 0.8 | 1,887 (-19.1%) | 0.905 |
| 400 | 0.8 | 1,835 (-21.3%) | 0.905 |
| 400 | 0.9 | 1,910 | 0.905 |
Zero recall change at every setting. Limit: recall is a word-sequence match to ground truth and cannot see a legitimately repeated line being dropped. On YouTube comments, short repeated UI lines ("1 reply >", "Read more") and usernames can legitimately repeat; over-dedup of those is the main risk and needs a test on the real capture type.
### 5.2 Video-region variance mask, naive version [MEASURED, FAILED]
Per-row-band (60 px) residual between consecutive frames after the best global vertical shift (search +-600 px), 47 frame pairs, clip with no video. Bands flagged as "video": 27% at residual > 30, 39% at > 20, 50% at > 8. A mask that would delete a quarter to a half of a text-only clip is unsafe. Likely cause [HYPOTHESIS]: during fast scroll the global shift estimate is 0 on many pairs (motion blur, shift beyond one global value), so normal scrolling text reads as non-rigid change.
Next version needs [PROPOSED]: (a) persistence: a band is video only if it stays high across many consecutive pairs and at a fixed screen location; (b) motion gating: skip pairs with large shift; (c) Option B: detect the player rectangle once on a clean, near-static pair (large 16:9, high temporal variance) and crop it for the whole clip. Cannot be validated without a recording that contains a playing video.

## 6. Widget recordings are deleted after processing (code finding, build 57 source d478dca) [VERIFIED BY CODE READING, not run on a device]
- The recording is written to app-private cache: File(cacheDir, "rec-<millis>.mp4") (CaptureService.kt:288), not visible in gallery or MediaStore.
- It is passed to the job queue as the job temp file and deleted in the job's finally block (JobQueue.kt:138), which runs on success, failure and cancel. A startup sweep also deletes rec-*.mp4 older than 12 h (JobQueue.kt:71).
- Only the text is kept: Downloads/V-OCR/<title>.txt via MediaStore (Saver.kt).
- Videos picked from the gallery are never deleted by the app.
Consequence: the build-57 capture's video cannot be recovered; analysis above is from text only.
APPROVED by the user Oct 14 and implemented in the wrap tree (compiles only, not run on a device): a "keep recording" debug toggle, default OFF. When on, the app copies the mp4 to Downloads/V-OCR instead of deleting it, so any problem capture can be replayed in the lab. Privacy note: screen recordings may contain sensitive content, so the toggle must be explicit and clearly labeled.

## 7. Open items and next steps
1. A new recording of a scrolling content area with a playing video (needed for steps 1-2 measurements: frames skipped, area removed, time saved, recall).
2. Run the fuzzy dedup on the real 7,252-line capture to see what it removes, and test legitimately repeated short lines.
3. Rebuild the mask with persistence/motion gating, and try the player-rectangle crop.
4. Calibrate the stationary-frame threshold with a precision/recall curve (risk: dropping content on slow scroll or mid-comment pauses).
5. Ship-test build with the keep-recording toggle; S24 Speed test for Fast/Slow and execution provider.
6. Held, unchanged: wrap build (Speed test row, About page, hands-free Scroll, haptics, Fast/Slow line) is waiting on the contact email and nits; it compiles but has not been run on a device or through full CI. Q&A is parked. Play/AAB decisions are deferred.

## 8. Artifacts
- Lab scripts and handoff doc: https://github.com/saidi-art/v-ocr-android/tree/wip-wrap/BACKUP (branch wip-wrap, not main).
- Lab inputs (clip, ground truth): private Drive folder, shared on request.
