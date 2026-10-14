# V-OCR Engineering Proposal - Update (rev 3)

Product: ScrOllOn (consumer app) on the V-OCR engine. Native Android (Kotlin/Compose), on-device ONNX OCR (rapidocr PP-OCRv4 det + rec, ORT 1.22.0). Date: Oct 14, 2026.

## 0. How to read the numbers
- MEASURED = run and scored by us. Lab = x86 sandbox on the fling clip (2,332 lines, ground truth = chat of Oct 11). Lab timings are NOT S24 timings.
- ESTIMATE / PROJECTION = reasoned from measurements, not run. Marked as such.
- NEEDS RECORDING = cannot be verified without the user's build-57 screen recording.
- Nothing below has been verified on a real device unless it says so. CI emulator screenshots are synthetic.

## 1. Baseline (MEASURED, lab, fling clip)
Recall 0.905, 2,332 lines, recognition 231.1 s, detection 39.3 s. Recognition is about 82% of runtime. Recall 0.905 is the guardrail: a change either holds it or ships as a declared tradeoff.

## 2. Recognition-height sweep (recH) and Fast/Slow tiers
| recH | recall | rec time | note |
|---|---|---|---|
| 48 (Slow) | 0.905 | 231.1 s | baseline |
| 42 (Fast) | 0.898 | 186.3 s (-19.4%) | Gate 1 PASSED |
| 38 | 0.879 | 196.5 s* | *earlier machine, not comparable |
| 32 | 0.609 | - | disqualified |

Correction on the loss type: the recH loss is substitution, not missing text. At recH 42, 97.4% of the lost words are substituted lines and 2.6% are missing lines (gate rule: pivot to detector maxSide reduction if more than 10% were missing; it was not). At recH 32, 98.6% of the loss is substitution. An earlier statement that low recH "drops lines" was wrong and is withdrawn.
Related: CTC confidence is not a reliable error flag. 1,137 of 3,069 baseline lines with confidence >= 0.95 changed at recH 32.
Decision: Fast = recH 42, Slow = recH 48, Fast default OFF, with the manual line "play safe, use Slow when the data matters". Device speed of Fast is NOT yet measured on the S24.

## 3. Other speed experiments (MEASURED, lab)
- Polarity inversion handling: recall 0.908 / 0.905, no loss.
- Batching 6 lines per call: recall 0.898 and 13% SLOWER. Rejected.
- INT8 dynamic MatMul: recall 0.905, rec 221.4 s (-4.2%). Small gain, kept as an option. Static INT8: declined (low expected gain for the calibration effort).
- Execution-provider order: NOT hardcoded. Waiting on on-device Speed test numbers (the Speed test row is in the wrap tree, not yet shipped).

## 4. Gate 2: language head (RESOLVED by the user, Oct 14)
Full 6,600-class head STAYS. Trims shelved.
Measured reasoning: EN+FR head 220.0 s and EN-only 96-class head 220.8 s vs 220-231 s full, i.e. about 4.5% speed and about 3 MB size. French proxy (synthetic, 25 sentences x 2 fonts): char match 0.951 full / 0.951 EN+FR / 0.927 EN-only; accented-character recall 0.35 / 0.35 / 0.00. EN-only loses all accents and every other language for a negligible gain. Not worth it. (Proxy is synthetic, not real French content.)

## 5. SAD overlap dedup calibration (MEASURED, lab, 2,831 lines checked, 33.5% have a true duplicate)
| SAD threshold | precision | wrong skips | duplicates caught |
|---|---|---|---|
| 1800 | 1.000 | 0 | 18.6% |
| 2500 | 0.931 | 19 | - |
| 3500 | 0.894 | 47 | - |
| 5000 | 0.890 | 75 | 64.2% |
Verdict: no single threshold is both safe and effective. A threshold that never wrongly skips (1800) catches under a fifth of duplicates; one that catches most duplicates drops ~11% real lines. Pixel overlap dedup alone is insufficient and must be paired with a text-level dedup. This is the motivation for section 6.

## 6. New: real-capture analysis (build 57)
Input: a real app capture from build 57 of a YouTube comments scroll, 7,252 output lines. The user reports the recording also contained the YouTube video playing above the comments, and that it took long to process for little text. (Analysis below is of the output text only. The recording itself has not been seen.)

MEASURED on the text:
- 821 exact-unique lines; 586 after normalizing case/punctuation; about 332-430 distinct after fuzzy clustering (ratio > 0.8; 332 for lines of 8+ chars, 429 for all lengths). Real duplication is about 94-95%, versus about 29% overlap on the lab clip.
- Single lines repeat 150-226 times (e.g. "1 reply >" x214).
- The output is a concatenation of per-frame screens, not a stitched transcript: the "Comments" header recurs 109 times in the first 979 lines, each block about 8-9 lines (one viewport). Dedup/stitch is not collapsing frames on this content.
- New content arrives unevenly (fuzzy first-appearance per 720-line bucket: 28, 10, 11, 8, 11, 95, 75, 79, 13, 2). Lines 720-3600 add about 40 new lines, so the recording sat on near-identical content for a long stretch.
- Noise: about 345 lines are 3 characters or fewer, and 150 distinct clusters appear only once, mostly garbled text ("Wiu udt vemy sdiu", "wutatueiig"), consistent with OCR of playing-video pixels and transition frames. Singletons also include a few genuine first-scan lines, so "garbage" is an upper estimate, about 5-7% of output lines.
- OCR variants of the same comment defeat exact matching ("while/whil leaning", "Eis/E is number 1"), which is why exact-unique (821) is about double the true count.

Causes (HYPOTHESES, untested against the video): (1) no cross-frame text dedup on stationary frames; (2) exact-match keys broken by OCR noise; (3) the playing video is recognized as if it were text, and its constant change means it can never be "stationary"; (4) the comments sheet is a small viewport, so any pause re-emits a whole screen.

## 7. Engine spec: "Scrolling OCR" (proposed)
Positioning: a scrolling screen recording in, clean ordered deduplicated text out. Every OCR tool assumes a static page; handling scroll is the part that is ours. The user chose to treat the engine, not the app, as the product.

Pipeline, in this order (order matters):
1. Video-region masking. Find regions whose pixels change non-rigidly frame to frame (video) versus content that is static or moves rigidly with the scroll. Mask them before anything else. Option A: per-frame row-band temporal variance after scroll-offset compensation. Option B (cheaper): detect the player rectangle once (large 16:9, high temporal variance) and crop it for the whole clip.
2. Stationary-frame skip. Frame-diff only the unmasked region; skip OCR when the scroll offset delta is about zero. The only fix that saves recognition time.
3. OCR (existing PP-OCRv4, Fast/Slow tiers).
4. Fuzzy text dedup. Normalized-text key plus edit similarity (about 0.8 on the file), applied at stitch level. Complements pixel SAD (section 5), does not replace it.
Why masking must come first: a playing video always differs between frames, so without masking, step 2 never fires and step 4 only cleans up output after the time is spent.

### Quantified impact on the build-57 capture
| Fix | Output lines | Processing time | Status |
|---|---|---|---|
| Fuzzy text dedup | 7,252 -> about 430 (about -6,800, 94%) | none (OCR already ran) | MEASURED on the text with the same fuzzy rule |
| Stationary-frame skip | follows from time saved | about 80%+ of recognized lines came from frames that added nothing new, so a large share of recognition time; exact figure unknown | PROJECTION. NEEDS RECORDING (frame count) |
| Video-region masking | removes noise lines (about 5-7% of output, upper estimate) | removes video area from detection and recognition; also what makes step 2 work | PROJECTION. NEEDS RECORDING (area share, time) |
Time conversions use lab speed (about 0.1 s per recognized line) and are not S24 measurements.

## 8. Verification plan
1. Get the build-57 recording (not yet confirmed to exist). Replay through the lab, count frames, measure detection area removed and time saved per fix, score recall against the text.
2. Prototype fuzzy dedup and the variance mask on the lab clip to prove no regression (that clip has no playing video, so this cannot validate masking).
3. Build one variant, ship as a labeled test build, measure on the S24 Speed test.
Open risk: aggressive stationary skip can drop content when the user scrolls very slowly or pauses mid-comment; threshold needs calibration like section 5 (precision/recall curve, not one number).

## 9. Status and held items
- Build 45 stays the Play candidate; build 57 and 55 are locked tags (locked-build57, locked-build55). AAB upload and Play decisions deferred.
- The wrap build (Speed test, About page, hands-free Scroll, haptics, sound levels, Fast/Slow line) is held for the About contact email and remaining nits. Compiles only; not run on a device or through full CI.
- Q&A ("Full") is parked.
- Backup: BACKUP/HANDOFF.md and lab scripts on branch wip-wrap (https://github.com/saidi-art/v-ocr-android/tree/wip-wrap/BACKUP). Private lab inputs are in a private Drive folder, not in the public repo.
