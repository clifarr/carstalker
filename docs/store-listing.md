# CarStalker Play Store listing

Copy-paste material for the Google Play Console. The app is currently a **beta**; start on a testing track before production.

## App identity

| Field | Value |
| --- | --- |
| App name | CarStalker |
| Package name | `com.carstalker.app` |
| Category | Auto & Vehicles |
| Tags | OBD-II, car diagnostics, ELM327, engine data, trouble codes, drive logging |
| Pricing | Free |
| Ads | No |
| In-app purchases | No |
| Accounts | No |

## Short description

> Live OBD-II data and assisted diagnostics. No ads, accounts or subscriptions.

## Full description

> CarStalker is an OBD-II app for Android that goes beyond a live dashboard. It shows live engine data, reads and clears trouble codes, records your drives — and adds assisted diagnostics that rank likely causes and show the evidence. No account, no ads, no subscription and no cloud. A 1.9 MB download.
>
> CarStalker is currently in beta. The core flows are usable, but guided tests and thresholds are still being validated on real cars. Feedback is welcome.
>
> NOT JUST ANOTHER OBD-II DASHBOARD
> Plenty of apps show RPM and clear codes. CarStalker adds step-by-step guided tests that use live data and the ECU's own self-tests to answer the question that matters: which cause is most plausible, what else could explain it, and which check would distinguish them? Every result lists the observations, evidence for and against each cause, competing explanations, a confidence category and one next check — and states what it cannot prove.
>
> SMALL BY DESIGN
> The download is 1.9 MB. A fresh install takes about 5 MB, and Android's background code optimization can grow the installed app to roughly 12 MB. Drive recordings add about 1 MB per hour and are kept until you delete them. Comparable OBD apps routinely ship at tens or hundreds of megabytes because of ads, analytics, crash SDKs and bundled media. CarStalker has none of those, so it installs fast and runs on an old phone left in the glovebox.
>
> BUILT ON PUBLIC WORK
> CarStalker does not invent a new diagnostic science. It compiles publicly available, freely licensed information — OBD-II standards, the CC0 OBDex trouble-code catalogue and public technician explanations — into one offline tool. Sources and licences are in the repository.
>
> WHAT IT DOES
> • Guided diagnostics: step-by-step tests that use live data and the ECU's own self-tests to rank plausible causes, show competing explanations and suggest one next check. Every result states what it cannot prove.
> • Live telemetry: RPM, speed, load, coolant, intake temperature, MAF, throttle, fuel trims, fuel level and more. The default dashboard picks six of the most useful parameters your car reports, and up to ten can be shown.
> • Multiple dashboards: create a blank dashboard with only the values you need and hide the gauges. Only the values on the active dashboard are polled, so a single value such as battery voltage updates as fast as the adapter allows.
> • Diagnostics: read stored, pending and permanent trouble codes with human-readable descriptions; clear codes behind a confirmation; see MIL status and readiness monitors.
> • Freeze frames: see the sensor values recorded when a code was stored.
> • Mode 06 self-tests: read on-board monitor results with the ECU's own limits and pass/fail state.
> • DTC knowledge: a bundled CC0 generic database of 9,500+ SAE J2012 codes, with clean-room likely causes and next checks for common codes.
> • PDF reports: preview a diagnostic report, then share or print it.
> • Drive recordings: every sample is written to wide-format CSV, then shared or analysed anywhere. Recordings play back offline with a scrub timeline and live chart.
> • Custom PIDs: define manufacturer-specific parameters with your own formula.
> • Adapter support: Bluetooth Classic, Bluetooth LE, USB (FTDI, CH340, CP210x, PL2303, CDC-ACM) and Wi-Fi ELM327 adapters.
> • Demo mode: explore the whole app with a simulated car, no hardware needed.
>
> WHAT IT IS NOT
> CarStalker is a diagnostic aid, not a replacement for service information or a professional inspection. It speaks generic OBD-II (SAE J1979). It does not read manufacturer-specific modules or perform service functions. Clearing codes resets readiness monitors — don't do it right before an emissions test, and don't use it to hide a fault you haven't fixed.
>
> PRIVACY
> No account. No ads. No analytics. No telemetry. No cloud. Settings, vehicle identity, recordings and reports stay on your device until you choose to share them. See the privacy policy for details.
>
> OPEN SOURCE
> CarStalker is open source under the MIT license. The source, the diagnostic rules and the evidence are in the GitHub repository, so they can be inspected and improved.
>
> BETA
> This is a beta release. Features, thresholds and wording may change. Guided diagnostics are deliberately conservative and need real-car validation. If something looks wrong, contact the developer using the email on this listing.

## Beta release notes

> 0.5.0: drive recordings keep running in the background with a foreground service, a wake lock and a stop action; the pedal channel finder finds the channel that follows your pedal and interpolates the released and fully pressed readings to 0–100 %; recording playback gets a min/max scale, tap-to-read and long-press multi-value comparison, with long gaps shown honestly; offline navigation no longer enables Dashboard, Diagnose or Codes from Settings or Recordings; and the small-phone/landscape sweep adds a navigation rail when the phone is held sideways, gauge readouts that scale with the dial, and a first-run pass that keeps the record button visible. 0.4.0: multiple dashboards with blank dashboards, optional gauges and dashboard-scoped polling; a clearer result hierarchy with pattern match, data quality and one next safe check; browseable diagnostic catalogue with search, categories and code matches; custom PIDs in the dashboard picker; per-vehicle engine profiles; transport-specific connection errors; Mode 06 mapping/unit validation; stricter safety and stimulus checks; and PDF/JSON provenance with VIN masking. No account or hardware is needed to explore the demo. Expect changes while the guided tests are validated on more vehicles.

## Contact and policy

| Field | Value |
| --- | --- |
| Developer email | TODO: add the support email before publishing |
| Website | TODO (optional) |
| Privacy policy URL | TODO: host [privacy-policy.md](privacy-policy.md) at a public URL and paste it here |

## App access (reviewer instructions)

> No account or login is required. On the Connect screen, choose **Demo**, then **Start demo**. The demo car exposes live data, a stored and a pending trouble code, readiness monitors, recordings, PDF reports and the guided diagnostic flows without an OBD-II adapter.

## Data safety answers

- Does your app collect or share any of the required user data types? **No.**
- Is all user data encrypted in transit? **Not applicable** — the app does not send user data to a server.
- Do you provide a way to request data deletion? **Not applicable** — there are no accounts and no server-side data.
- Data types collected: **none**.
- Data types shared: **none**.
- Data handling notes: settings, VIN, recordings, reports, custom PIDs and guided results are stored only in the app's private storage. Recordings and reports leave the app only when the user chooses Share or Print. Location permission is requested only on Android 11 and lower because Android requires it for Bluetooth scanning; the app does not use it to determine location. The app contains no analytics, ads or crash-reporting SDKs.

## Content rating answers

- Violence, sexual content, profanity, drugs, alcohol/tobacco, gambling, horror: **No.**
- User-generated content or social features: **No.**
- Shares location: **No.**
- Digital purchases: **No.**
- Unrestricted web access: **No.**
- Expected rating: **Everyone.**

## Target audience and declarations

| Declaration | Answer |
| --- | --- |
| Target audience | 18+ (car owners); not designed for children |
| Designed for families | No |
| Ads | No |
| News app | No |
| Government app | No |
| Financial features | No |
| Health app | No |
| Data deletion | Not applicable (no accounts) |
| Advertising ID | No AD_ID permission and no advertising SDK |
| Photo/video permissions | No |
| SMS/call log permissions | No |
| All files access | No |
| Package visibility | No |
| VPN | No |
| Accessibility API | No |
| Foreground service | No |
| Exact alarm | No |
| Health Connect | No |

## Permissions note for review

- `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` — find and talk to a Bluetooth OBD-II adapter. `neverForLocation` is set on Android 12+.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — `maxSdkVersion=30`, required by Android for Bluetooth discovery on Android 11 and lower. Not used to determine location.
- `INTERNET` — connect to a Wi-Fi OBD-II adapter on the local network. No server is contacted.
- USB host — connect to a USB OBD-II adapter. Declared `required=false`.

## Graphics checklist

| Asset | Status |
| --- | --- |
| App icon 512×512, 32-bit PNG with alpha, ≤1 MB | ✅ [icon.png](icon.png) |
| Feature graphic 1024×500, JPEG or 24-bit PNG without alpha | ✅ [feature-graphic.png](feature-graphic.png) |
| Phone screenshots, 2–8, 9:16 portrait | ✅ Lead with diagnostics: [16-diagnose.png](screenshots/16-diagnose.png), [17-guided-capture.png](screenshots/17-guided-capture.png), [18-guided-result.png](screenshots/18-guided-result.png), then [03-dashboard.png](screenshots/03-dashboard.png), [05-codes.png](screenshots/05-codes.png), [09-playback.png](screenshots/09-playback.png). Extras: [01-connect.png](screenshots/01-connect.png), [08-logs.png](screenshots/08-logs.png), [11-custom-pid.png](screenshots/11-custom-pid.png), [19-dashboards.png](screenshots/19-dashboards.png), [20-dashboard-voltage.png](screenshots/20-dashboard-voltage.png). |
| Tablet screenshots | Optional; add 4+ if you want to promote tablet support |
| Promo video | Optional |

## Play Console release checklist

1. Create the app in Play Console with package name `com.carstalker.app`.
2. Enroll in Play App Signing and keep the existing upload key and its password safe.
3. Upload `app/build/outputs/bundle/release/app-release.aab` to **Internal testing**.
4. Complete **App content**: privacy policy, app access, ads, content rating, target audience, data safety, news/government/financial/health declarations.
5. Complete the **Main store listing** with the copy and graphics above.
6. Run the **pre-launch report** and fix any crashes, ANRs or accessibility warnings.
7. Move to **Closed testing**. If this is a new personal developer account created after 13 November 2023, keep at least 12 testers opted in for 14 continuous days before applying for production access.
8. Apply for production, then use a staged rollout: 5% → 20% → 50% → 100%.
9. Monitor Android vitals and reviews after launch.

## Open source status

The source code is public under the MIT license at [github.com/clifarr/carstalker](https://github.com/clifarr/carstalker). The Play listing says this explicitly so the claim is accurate.
