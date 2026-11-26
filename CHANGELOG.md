# Changelog

All notable changes to CarStalker are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

## [0.5.1] - 2026-11-26

Version code 108. A combined release, since 0.5.1 was never published: recording integrity
and gap detection, battery guidance for frozen background apps, the other-supported-PIDs
picker, fresh safety data, honest stage outcomes, foreground-service handling, instrumented
CI and a published privacy policy.

### Added

- **Recording integrity states.** A recording now reports `Recording`, `Completed` or `Failed`
  instead of a single boolean. A failed write, flush, close or metadata update is shown on the
  dashboard and in a notification, and says that the partial file was kept. The index entry is
  written atomically, so a killed process cannot leave a half-written session that hides a
  recoverable CSV.
- **Sampling-gap reporting.** A recording now says when it was not sampling instead of claiming a
  clean run. The dashboard names the longest pause, the ongoing notification shows the sample
  count and the clock time of the newest sample (both stop advancing while the phone has frozen
  the app), and the CSV metadata stores the stall count and longest gap. The Recordings list and a
  recording's detail header show the pause next to the duration.
- **Battery settings guidance.** When a recording starts while Android may freeze the app, the
  dashboard offers a direct link to the app's battery screen, plus the Xiaomi/HyperOS Autostart
  screen. **Settings → Recording in the background** shows whether the app is exempt and repeats
  the links, including the "Battery saver → No restrictions" step MIUI needs.
- **Other supported PIDs.** The parameter picker now lists the standard Mode 01 codes the car
  advertises but CarStalker has no built-in definition for, instead of only counting them. Tapping
  a code opens the custom-PID form with that code fixed, so a parameter the app does not define
  can still reach a dashboard once you give it a name and formula. The vehicle card reports how
  many of the advertised PIDs have no definition, which explains why that count is higher than the
  number of selectable parameters. Readiness-monitor and freeze-frame PIDs are excluded: the app
  reads those itself. Demo mode now advertises two standard codes the catalogue lacks (0x1C OBD
  standard, 0x51 fuel type), so the list can be seen without a car.
- **Instrumented tests in CI.** GitHub Actions now runs the Android UI smoke tests on an API 35
  emulator, alongside the existing unit tests, lint and unsigned AAB build.
- **Published privacy policy.** The policy is hosted at
  <https://clifarr.github.io/carstalker/privacy-policy.html>, linked from Settings and the docs.

### Changed

- **Per-car settings are separated from global settings.** Vehicle name, fuel type, aspiration
  and pedal calibration now live on **Diagnose → Vehicle profile**, not in global Settings. The
  last car can be renamed directly from the Connect screen. Switching cars no longer inherits the
  previous car's name, fuel or aspiration; the simulated demo car's profile is repaired once so a
  real car's name or diesel setting cannot leak into the demo.
- **Guided catalogue consistency.** Search and filters now sit above the guide list, and
  “Suggested from this scan”, every category and “Planned workflows” use the same collapsible
  header. Suggested guides are no longer a flat, differently styled block at the top.
- **Pedal interpolation is per dashboard.** The old global switch is gone. A dashboard shows a
  Pedal interpolation toggle that is disabled until the pedal finder has stored a calibration for
  the current car; running the finder enables it on the active dashboard. Existing installs keep
  the old choice by migration, and an open recording keeps the interpolation mode it started with.

### Fixed

- **Recording no longer silently loses samples when Android freezes the app.** MIUI/HyperOS freeze
  apps that are not exempt from battery saving even with a foreground service and a partial wake
  lock, and the ongoing notification stays visible because the system draws it, not the frozen
  process. CarStalker now detects the gap as soon as the process resumes, explains it on the
  dashboard, writes it into the recording metadata and points at the setting that prevents it.
- **Stopping a recording no longer shows a stale "interrupted, partial file" entry.** The
  recordings list refreshed as soon as the recording state left `Recording`, which happened before
  `endedAt` was persisted, so a cleanly saved file looked incomplete until the screen was reopened.
- **System bars follow the app theme.** The Android night mode is now aligned with the app's
  theme setting, so the 3-button navigation bar and bottom-sheet dialog themes switch with the
  app instead of the system theme. The Edit, Replace, dashboard switcher and parameter detail
  sheets also fill the navigation-bar area with the sheet surface colour, so a forced dark theme
  no longer leaves a light grey strip or dark-on-dark navigation buttons.
- **Dashboard parameter picker.** It now uses the same bottom-sheet inset handling as the Replace
  list, so the last parameter stays above the navigation bar in portrait and landscape without a
  fixed empty gap.
- **Parameter detail sheet.** The “Remove from dashboard” / “Pin to dashboard” button is gone;
  dashboard membership is managed from the parameter picker.
- **Switch contrast.** Off switches now use a lighter thumb, a distinct track and a visible
  border. Material's default unchecked thumb and track were the same shade in this theme, so an
  off switch looked like an empty pill.
- **Last vehicle card.** It no longer shows a previous car's VIN when the current car does not
  report one. It shows the car's nickname (or VIN-derived name) and hides when the car is
  unidentified; naming a car in Settings → Your car makes it appear.
- **Diagnose page spacing.** The vehicle-profile labels and their chips now have proper
  breathing room, and guide cards space the status pills away from the “Detected in the … scan”
  line and the required-signals text.
- **The pedal finder keeps the throttle recommendation.** When both the pedal and the
  throttle plate follow the pedal, the result now offers **Use both**, **Use pedal only** and
  **Use throttle only** instead of dropping the throttle channel. A static throttle plate on the
  dashboard is still replaced rather than duplicated.
- **Recording channels no longer follow the dashboard.** Starting a recording snapshots its
  columns, units, labels and PID list. Switching dashboards keeps sampling and writing every
  recorded channel with blank cells for unavailable samples instead of silently dropping them.
- **Safety checks require fresh data.** Guided tests now sample RPM, coolant and voltage
  independently of the stage's analytics channels. A missing or stale safety signal pauses the
  stage with an explicit reason instead of passing the gate; an out-of-range value still stops the
  test. Safety signals the vehicle does not report are listed as unverified rather than assumed
  safe.
- **Incomplete stages are no longer valid evidence.** A stage is `Valid` only when it reached its
  target duration with an adequate sample count. Early finishes are `Partial` or `Insufficient
  quality`, with a note explaining what is missing.
- **Background recording service.** The foreground service now always uses the
  `connectedDevice` type, which matches Bluetooth Classic, BLE, Wi-Fi and USB adapters and avoids
  the Android 15 `dataSync` time limit. If the service cannot start or Android stops it, the app
  shows the failure in the dashboard and in a notification instead of implying background capture
  is still protected.

## [0.5.0] - 2026-11-25

Version code 107. A combined release: background drive recording, pedal interpolation and a
playback chart built around selecting values, plus a small-phone/landscape layout sweep, a pedal
channel finder, vehicle names and a first-run clarity pass.

### Added

- **Background recording.** Starting a recording also starts a foreground service with a partial
  wake lock and, for network adapters, a Wi-Fi lock. Android no longer freezes the poll loop when
  the app is backgrounded, so a drive recording has no long empty stretch in the middle. The
  notification offers a Stop action and returns to the app when tapped. On Android 13+ the app
  asks for notification permission the first time a recording starts, so the ongoing-recording
  notification is visible and can be used to stop the recording.
- **Throttle & pedal channel finder.** The parameter picker samples the throttle-plate and
  accelerator-pedal channels for ten seconds while the pedal is pressed, then recommends the
  channel that actually moved. It measures the connected car instead of relying on a per-model PID
  database, which does not exist in a reliable free form, and it works on cars that do not report a
  VIN. Applying a recommendation replaces a static throttle-plate card on the dashboard instead of
  growing the list past its ten-value limit.
- **Pedal interpolation.** The channel finder records the released and fully pressed readings and
  uses them to rescale the chosen pedal or throttle channel to 0–100 %. Cars that report only part
  of the range (for example 12 % released and 88 % pressed) read naturally on the dashboard.
  Interpolation is on by default and can be turned off in Settings → Your car; the calibration is
  stored against the car it was measured on.
- **Vehicle name.** Settings → Your car stores an optional name against the car's ECU fingerprint
  (VIN, calibration ID or protocol fingerprint). It is used on the dashboard and in the vehicle card
  when the car reports no VIN, is migrated from a name entered before the first connection, and is
  never reused for a different car.

### Changed

- **Recording playback.** The redundant timeline slider is gone; tap the chart to place the
  playhead. A single series now shows a left-hand minimum/maximum scale. Tap a value to isolate it
  or press and hold to compare several values; the shared scale is hidden while several are
  selected. A long gap between samples is drawn as a gap instead of a misleading straight line.
- Throttle and pedal parameters now say what they measure: `Throttle plate position` (`0x11`),
  `Throttle plate position (relative)` (`0x45`), `Throttle plate position B` (`0x47`),
  `Accelerator pedal D (main)` (`0x49`, short name `Pedal`) and `Accelerator pedal E (secondary)`
  (`0x4A`, short name `Pedal 2`).
- The throttle detail note points at the pedal channels and the channel finder.

### Fixed

- **Small-phone and landscape layout.** Swept every screen on a 320dp-wide phone, a 360dp phone, a
  411dp phone and both orientations. Fixes:
  - Navigation labels no longer wrap onto a second line at 320–360dp; the bar drops its letter
    tracking and steps the type down instead, which also stops the bar from growing taller than the
    screen it sits on.
  - Held sideways, the five destinations move from the bottom bar to a rail on the leading edge.
    The bottom bar plus the system bars previously left roughly 150dp of content height, so tab
    screens opened mid-card with their own navigation cut in half.
  - The dashboard gauges are capped to a share of the viewport height, so landscape shows the whole
    dial instead of the top two thirds of an oversized one.
  - Gauge readouts scale with the dial. The digits were a fixed 34sp, so on a small dial the value,
    unit and label crowded the arc.
  - Rows of actions wrap instead of squeezing their labels: "Try demo" no longer breaks into a
    two-line button, and "Clear codes" stays on one line.
  - The fuel and aspiration chips wrap onto a second line. "Turbo / supercharged" was being crushed
    into a 3.5dp-wide column that rendered one character per line.
  - The connect screen no longer applies the navigation-bar inset twice, which had pushed
    "Allow Bluetooth" off-screen on a 640dp-tall phone.
  - Module and code-group chips separate the count from the name (`Engine · 2`), so an unnamed
    module no longer reads `ECU 0x7E9 0`.

- **First-run and next-step clarity.**
  - The dashboard no longer scrolls itself down to the gauges a second after opening. The jump hid
    the vehicle name and the record button without the user asking, so a first-time user never saw
    the control that the recordings screen tells them to press. Scrolling is now entirely theirs;
    the gauges still snap into place when they scroll near them.
  - A stored code now offers `Run the guided test for P0420` from its expanded guidance, which
    opens the matching test on the Diagnose tab. The app's premise is finding the cause, not just
    reading the code, and this was the missing link between the two screens.
  - The codes summary names the group the codes are actually in (`1 pending code`) instead of
    calling every fault "stored", which contradicted the `No stored codes` empty state below it.
  - An empty code group now points at the group that does hold codes
    (`Nothing in this group, but this module reports pending 1`) instead of implying the module is
    clean.
  - `Include full VIN in reports` moved from About to Reports, where the rest of the report
    settings live.
  - The codes screen dropped its unlabelled refresh icon; `Read codes` in the status card is the
    labelled action two lines below it.
  - The manual poll-rate slider no longer reaches 0, which is the automatic rate. Dragging it to 0
    used to leave the switch reading "Automatic polling: off" while the app polled automatically.
  - The Diagnose intro is one line instead of three; the marketing copy was pushing the catalogue
    below the fold on small screens.

- **Offline navigation.** Dashboard, Diagnose and Codes stay disabled while no adapter is
  connected, even after opening Recordings or Settings from the startup screen. Pressing back
  from those offline-safe tabs returns to the startup screen instead of opening the Dashboard.
- **Adapter voltage label.** The adapter's `ATRV` measurement is a built-in value, not a custom
  PID, so its detail sheet no longer says "Custom PID — manage it in Settings".

## [0.4.0] - 2026-11-25

Version code 105. The changes below come from the independent 2026-11-24 UI and functionality
review and field testing on a UK 9th-gen Honda Civic; the per-issue record is kept in the internal
review notes, outside the published repository.

### Added

- **Multiple dashboards.** Each dashboard has its own parameter list and gauge visibility. Create a
  blank dashboard, duplicate, rename or delete one, and switch from the Dash header or Settings.
- **Dashboard-scoped polling.** The adapter is asked only for the values the active dashboard
  shows, plus visible gauge/mini-stat PIDs and `ATRV`. A single-value dashboard therefore updates
  as fast as the adapter allows, with no extra parameters slowing it down. A voltage-only dashboard
  was verified against the new demo scenario: min 12.40 V, max 15.90 V.
- **Blank dashboard flow.** A blank dashboard starts with no values and no gauges; adding one value
  produces a large focus card with min/average/max and a sparkline.
- **Dashboard card editing.** Press and hold a card for one second to arm it. While dragging, the
  card follows the finger, the cards around the drop slot reflow to open it, and a vertical
  insertion line shows where it will land. Drop it on another card to move it before that card,
  or release it in place to open a **Replace by** list. The list excludes values already on the
  dashboard and keeps unsupported values visible but disabled, marked **N/A**.
- **Browseable diagnostic catalogue.** The catalogue is available without an adapter, grouped by
  problem category instead of implementation phase, with search, All / Ready to run / Limited /
  Unavailable filters and collapsible sections.
- **Code-matched suggestions.** Guides that match a scanned code are shown first with
  `Matches P0420` and the scan time, and unavailable guides open an explanation instead of doing
  nothing.
- **Custom PIDs in the dashboard picker.** Enabled custom PIDs appear in a `CUSTOM` section with
  validation and source badges.
- **Per-vehicle engine profiles.** Fuel and aspiration are stored per VIN (or calibration ID) and
  are never silently reused for another car.
- **New demo scenarios:** steady 2,000 rpm, throttle blip, rich idle (−18 % trims) and voltage
  spikes.
- **Result provenance.** PDF and JSON exports include adapter, protocol, scan time, Mode 06 state,
  demo flag and guided rule version. PDFs mask the VIN by default; full VIN is opt-in.
- **PDF page navigation.** Sticky page count, Previous/Next and a Fit (zoom reset) action.
- **Local connection diagnostics export** for beta bug reports, with no VIN or location.
- **Settings links** to the source code, issue tracker and releases.
- **Readiness/scan freshness:** the Codes screen warns when a scan is over 10 minutes old; Dash
  shows `Updated <0.1s ago` / `Delayed` and no longer shows green `Live` when stale.

### Changed

- **Six-value default dashboard.** The automatic selection now shows six values instead of ten,
  with adapter voltage as the sixth priority so it is always included. A dashboard can still grow
  to ten values.
- **Dashboard limit enforced.** The parameter picker promised a limit but allowed more. It now
  stops at ten, disables further additions at the cap and says why.
- **Offline startup navigation.** With no adapter connected, the startup screen disables the
  Dash, Diagnose and Codes tabs in the bottom bar; Recordings and Settings stay available. The
  duplicate Recordings and Settings buttons above the adapter picker are removed.
- **Result screen hierarchy.** Status, pattern match and data quality lead; a plain-English
  summary and **NEXT SAFE CHECK** follow; observations, traces, stage quality, other causes and
  limitations are behind expandable sections. Result actions are stacked so labels cannot wrap
  into vertical words.
- **Capture screen** leads with the instruction, state, target and gauge; the live trace and
  simulation controls are collapsed.
- **Guide intro** keeps safety visible, collapses technical limits and shows preparation notes for
  cold-soak or passenger-assisted tests.
- **Selection controls** now use selectable semantics and a visible check mark, not colour alone.
- **Small text** is larger (`bodySmall` 13 sp, `labelSmall` 11 sp) and critical actions are full
  width.
- **Polling** defaults to Automatic; the millisecond slider is behind an explicit advanced choice.
- **Playback** starts with one series and offers an explicit `Compare all` mode with a shared-scale
  warning. The play button is gone: pinch the chart to zoom, drag to pan and double-tap to reset,
  with the vertical scale following the visible window. The slider still scrubs the playhead.
- **Single-value dashboards** no longer repeat the focus card as a small tile below it; the large
  card is the only chart for that value.
- **Detail views** request their parameter on every poll cycle, and dim the gauge with a warning
  when the adapter disconnects, the vehicle stops answering or samples go stale.
- **Dashboard entry** scrolls the first data block (gauges or the focus card) to the top after a
  second and cancels if the driver already scrolled. That edge is a magnetic scroll point: the
  scroll resists leaving it and settles back onto it, so it is easy to stop with the gauges on top.
- **Recordings** show `Drive` or `Guided · <guide>` and `Demo` badges.
- **Connection errors** are transport-specific with a technical detail, Retry and Change adapter.
- **PDF reports** mark demo data on every page and explain when Mode 06 was not checked.
- **Copy** uses “emissions inspection”, qualifies converter temperature readings and says that
  emissions-defeat devices may violate local law.
- **Guided tests pause** when the app leaves the foreground or the Diagnose tab, and a mid-test
  adapter loss is reported instead of silently continuing.

### Fixed

- **Brand mark.** The launcher icon and in-app logo now share a centred magnifier with a smaller
  lens and a longer, thicker handle that joins cleanly under the ring.
- **Catalyst wizard safety gate.** A flashing MIL, active misfire or missing prerequisite now
  blocks **Next: capture** and the capture itself, even when a positive checkbox was ticked.
- **Rich-mixture polarity.** Positive fuel trims can no longer produce a negative-trim finding;
  they produce an Inconclusive result that points to the lean-mixture guide.
- **Unexercised response tests.** Throttle/pedal, MAP throttle-blip and oxygen response stages now
  require an observed stimulus and are labelled `Not exercised` instead of `No anomaly detected`.
- **Mode 06 mapping.** Monitor/test identity and unit family are validated; mismatches are marked
  `UNVERIFIED`, shown as raw data and excluded from pass/fail counts.
- **“Not checked” is not zero.** Mode 06 has an explicit state (`Not checked`, `Not supported`,
  `Could not read`, `Outdated`, `Read`), and absent results no longer appear as zero failures in
  Codes, used-car/readiness results, PDF or JSON.
- **Catalyst confidence.** A high-confidence converter verdict requires adequate O2 sampling
  (sample count, switches, effective Hz and duration) and trusted Mode 06 data.
- **Symptom markers survive interruption.** A marker recorded before a pause or disconnect is kept
  as invalidated evidence and is never reported as “no marker entered”.
- **Readiness consistency.** The demo now reports Misfire, Fuel system, Components and Catalyst as
  supported and complete with P0420; freeze-frame MAP is plausible at 1,726 rpm.
- **Mode 06 multi-block parsing.** Demo and parser tests now use the repeated MID/TID/UAS layout
  expected by SAE J1979.
- **Custom PID validation.** PID range, collisions with standard PIDs, formula syntax, min/max,
  decimals and unit length are validated before saving.
- **Vertical button wrapping** on result, capture, catalyst, dashboard and settings action groups.
- **Stale values** are no longer presented as live; PID detail shows sample count and last update.
- **Vehicle offline detection.** With the ignition off, the adapter stays powered and keeps answering
  `ATRV`, which previously refreshed the dashboard timestamp and froze the last engine readings as
  if they were live. Adapter voltage no longer counts as vehicle liveness: after 2.5 s without an
  ECU answer the dashboard shows `Vehicle offline`, dims the gauges and hides the last values until
  the ECU responds again. Polling continues in the background, so starting the engine restores live
  data without reconnecting the adapter. A voltage-only dashboard is unaffected.
- **Unknown vehicle identity** no longer shows “My garage”. The dashboard shows **Vehicle** (or
  **Unidentified vehicle** when nothing is known), states that the VIN was not reported, and the
  vehicle card explains that some UK/European models need manufacturer-specific diagnostics.
- **Adapter-only dashboards recover.** A dashboard that polls only `ATRV` used the last ECU
  timestamp from an earlier dashboard, so it stayed on `Delayed` even while voltage kept
  streaming. Freshness now follows what the active dashboard actually polls, and the status
  returns to `Live` as soon as adapter samples resume.
- **Throttle sampling and diagnosis.** Throttle position is now polled every cycle and relative
  throttle, accelerator pedal D and accelerator pedal E every other cycle instead of every fourth,
  so short pedal movements on a slow Bluetooth Classic adapter are no longer missed. Decoding was
  verified against SAE J1979. Field data from a 9th-gen Civic showed `0x11` held at ~16 % and
  `0x45` at ~93 % while the pedal moved, changing only during idle-stop: those PIDs report the
  throttle body/valve on that ECU, not pedal travel. The detail view now says so and points at
  `0x49`/`0x4A` for the pedal.
- **Per-parameter freshness.** Each tile tracks when that parameter last produced a sample. A
  tile that has not updated for 10 s is labelled `stale` and dimmed, and the detail view shows
  that parameter's own sample age. This separates a value the ECU keeps reporting at one position
  from a poll that stopped working — the two look identical on the dashboard otherwise.

### Security

- PDF VIN masking is the default.
- Diagnostics export contains no VIN, location or account data and is shared only on request.
- No new permissions, telemetry, accounts or network services were added.

### Verification

- 163 core unit tests and 47 app unit tests pass.
- Android lint: 0 errors.
- Release APK: 1.9 MB; installed and exercised on an API 36 emulator and the field-test phone.

### Known limitations

- Field validation now includes a UK 9th-gen Honda Civic over a Bluetooth Classic ELM327. USB,
  Wi-Fi, BLE and other vehicles still need hardware validation, as do transport recovery and the
  diagnostic thresholds.
- Some Honda ECUs report throttle-body/valve position on PIDs `0x11` and `0x45`; those values do
  not track the pedal. Use `0x49`/`0x4A` for pedal travel.
- Mode 06 decoding validates the unit family for known monitor/test pairs but does not decode
  manufacturer-specific identifiers.
- `docs/screenshots/15-real-vehicle*.png` and `14-dashboard-tcp.png` are historical hardware
  captures and were not regenerated.

## [0.3.1] - 2026-11-23

### Changed

- More reliable ELM327 adapter reads.
- New settings screen for units, theme, polling, dashboard parameters, custom PIDs, engine profile,
  report branding and privacy information.
- Diagnostics-first README and store copy.
