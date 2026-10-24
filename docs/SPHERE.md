# Sphere v1 - foundation map

Product brief: "Sphere v1 - Development Brief (v2)", 24 Oct 2026 (Yacine). This file maps the brief to the code
and records the technical choices made while building the foundation. Sphere is a separate product from ScrollRSI:
ScrollRSI build 109 is frozen on `main` (e7699c3) and never gains a network, an account or billing. Sphere lives on
this branch and carries all three by design.

## Layers (brief: "Build approach")

| Layer | Where | State |
| --- | --- | --- |
| Capture core (shared): scroll capture, REC, auto-scroll + speed, OCR, top-of-thread detection | `app.vocr.core`, engine parts of `app.vocr.capture` (CaptureService, LongShot, ScrollService, QueueService) | inherited from the frozen 109 line via sphere-proto-1; Sphere may move it forward, ScrollRSI never sees these changes |
| Sphere shell: overlay sphere, gestures, tool splash, after-capture popup, docking, drag, themes | `app.vocr.sphere` (SphereService, Theme, Splash, Popup, SphereView) | new in this branch |
| Text layer: direct on-screen text (opt-in) else OCR | `ScrollService.screenText` + `core/DirectText` (merge with proven-overlap), OCR in `core/Ocr` | inherited; direct text stays off by default behind the disclosure |
| Hand-off + folder: Image/Text, Open/Share, structured folder, recent destinations | `sphere/CaptureStore`, `sphere/Popup` | new; recent destinations is a designed-in list, starts empty |
| Account + plans: sign-in, free plan, token meter | `sphere/account` (scaffold only) | no backend chosen yet; sign-in screen is a placeholder until the provider decision (user-owned) |
| Later (not v1): AI/agent layer as a swappable processing step | none in v1 | deliberately absent |

## The shell purge

Sphere's interface is the overlay; there is no home page. Removed from the prototype shell:
MainActivity (bento home), SavedActivity, AboutActivity, ManualActivity, the old SettingsActivity, AddActivity
(queue old recordings - the brief leaves "recording import stays ScrollRSI-exclusive?" open, so it is not wired),
SavedPanel (the prototype's saved-files sphere surface - replaced by the folder + Open/Share popup).

Kept app-side screens (setup lives in Android's own settings; these exist only where Android cannot host them):
- `sphere/SetupActivity` - first-run permission setup (overlay, notifications, accessibility) and account placeholder.
- `sphere/SphereSettingsActivity` - theme choice (Day / Night desk / follow phone), direct-text opt-in, account.
- `DisclosureActivity` - accessibility disclosure + consent (Play requirement), reused as-is.

## Capture flow contracts

- Tap = quick capture (one or a few messages). Bottom bar = auto capture (measured part, Stop ends).
  Long press = tool splash. Drag = move sphere; tools follow elastically. Idle 5 s = dock 40% off the edge.
- After every capture: four minis (Image, Text, Open, Share). Last-used format starts lit. Popup stays while
  touched, vanishes ~3 s after the last touch; the capture stays in the shade as fallback.
- Every capture lands in Sphere's folder (`Download/Sphere/`) with a small header: source app, date and time,
  format, capture type (tap | auto | rescue | rec), and the text source (OCR | direct text) for text captures.
  Naming: `sphere-YYYYMMDD-HHmmss-<type>-<source>.<ext>`.
- Hand-off honesty (user requirement): Sphere transfers the exact captured words. No rewriting, no summaries in v1.
  Where the source app exposes roles (direct text), provenance is preserved; where it cannot be known, we show
  uncertainty rather than inventing who said what.
- Hand-off journey measurement: every capture-to-destination run is logged step by step (local CSV only) so the
  real tap count into named destinations can be measured in testing - the one-or-two-tap promise is proven, not assumed.

## Safety carried from the prototype's Rescue flaws (verified 23 Oct)

- The splash X / leaving the app must cancel a running climb, not just close UI. Any new workflow that starts a
  long capture must offer a visible Stop that actually stops the background run.
- Any climb exit must not silently fall into capturing. A cancelled run saves nothing.
- Climb logs survive the run (they were discarded in the first prototype).
The user reports a successful Rescue test (24 Oct 12:09) and defers the remaining debugging; the cancellation
question stays open until tested, and these rules apply to everything new built here.

## Open decisions (user-owned, from the brief's list + build findings)

- Account backend for day-one sign-in (he offered to help with platform signups; a proposal with cost needs to go back).
- Analytics tool and exactly which usage counts v1 records (success measures list exists; tool undecided).
- Exact Sphere launch date; thank-you form for ScrollRSI buyers; default theme on first launch;
  day-theme finish on device; night-desk motion values; long-press/popup/dock timings to confirm in testing.
- "Drop" mechanics of the hand-off (share sheet vs remembered destinations that land text ready to send) -
  the load-bearing product question; measure the real tap count first, then decide.

## Presentation slice 2 (bar / Speed / REC state)

- **Bottom bar** (`DropBar.kt`): while the sphere is dragged, the charcoal auto-capture bar rises from the bottom
  edge; it lights (hot) while the sphere hovers over it, and dropping the sphere there starts auto capture - a
  measured part of the conversation, ended with Stop on the engine's shot bar (existing flow, unchanged).
- **Speed**: the floating speed bar shown only while Auto-scroll or REC runs (existing engine surface) is restyled
  as the brief's Speed sub-tool: palette-driven (Day: the +240 Auto-scroll hue; Night desk: brushed brass), label
  "Speed". Behaviour (0-10 pace, fold to edge handle, drag) unchanged.
- **REC state**: while REC records, the sphere carries the fixed red dot (`RecDot`), signalled engine -> shell over
  `SphereBridge.recState`; cleared on stop and on engine teardown.

The render harness (`SphereRenderTest`) draws every shell surface above to PNG (build/outputs/sphere-render) in both
themes - Day and Night desk - so design-token changes are inspected as pixels.


