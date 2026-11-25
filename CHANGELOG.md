# Changelog

All notable changes to CarStalker are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses semantic versioning.

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
