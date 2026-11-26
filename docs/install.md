# Install CarStalker from an APK

CarStalker is distributed as a signed APK from GitHub Releases. It is not on Google Play yet, so Android will ask you to allow installs from your browser or file manager.

## Requirements

- Android 8.0 (API 26) or newer.
- About 15 MB of free space. The APK is 1.9 MB; Android's code optimization adds a few megabytes, and recordings use about 1 MB per hour.
- An OBD-II adapter for live data: Bluetooth Classic, Bluetooth LE, USB (FTDI, CH340, CP210x, PL2303, CDC-ACM) or a Wi-Fi ELM327. **Demo mode works without hardware.**

## Download

1. Open the releases page: <https://github.com/clifarr/carstalker/releases> and pick the newest beta.
2. Download `carstalker-vX.Y.Z.apk`.
3. Download `SHA256SUMS` and verify the APK (recommended):
   - macOS/Linux: `shasum -a 256 carstalker-vX.Y.Z.apk`
   - Windows: `certutil -hashfile carstalker-vX.Y.Z.apk SHA256`
4. Only install APKs from the official release page.

## Install

1. Tap the APK in your browser or file manager.
2. Android asks to allow installs from that app. Tap **Settings** → **Allow from this source** → **Back** → **Install**.
3. If Play Protect shows **Unsafe app blocked** or **App scan recommended**:
   - Tap **More details** → **Install anyway**.
   - This is expected for a sideloaded app that is not distributed through Google Play. The APK is signed with the project's release key, and the SHA-256 checksum is published on the release page.
4. Open CarStalker.

## First run

- Tap **Demo** → **Start demo** to explore without a car.
- For a real adapter:
  - **Bluetooth:** pair the dongle in Android Settings → Bluetooth, then choose Bluetooth in the app.
  - **USB:** connect the cable through OTG and allow the USB permission prompt.
  - **Wi-Fi:** join the adapter's Wi-Fi network and set the host and port in the app (default `192.168.0.10:35000`).
- On Android 11 and lower, Android requires location permission for Bluetooth scanning. CarStalker uses it only to find the adapter; it does not read or store your location.

## Updates

- Download the new APK from the latest release and install it over the old one. The signing key is the same, so settings and recordings are kept.
- **Automatic updates:** add <https://github.com/clifarr/carstalker> to [Obtainium](https://github.com/ImranR98/Obtainium). Obtainium checks GitHub Releases and offers updates.
- **F-Droid:** an IzzyOnDroid listing may follow. F-Droid itself requires reproducible builds and a review.

## Troubleshooting

- **"App not installed"** — uninstall the Play Store or debug build first; the signatures differ. Check free space.
- **"Blocked by Play Protect"** — tap **More details** → **Install anyway**. Only do this for the official release APK.
- **"No Bluetooth devices"** — pair the adapter in Android Settings first, grant the Nearby devices/Bluetooth permission, and check whether the dongle is Classic or LE. The two modes are separate.
- **"Unknown sources"** — allow the browser or file manager, not CarStalker itself.
- **"No adapter found"** — try Demo mode first, confirm the adapter is ELM327-compatible, and turn the ignition on.

### Connection troubleshooting

- **Bluetooth Classic** — pair the adapter in Android Settings first (PIN is usually `1234` or
  `0000`), then choose **Bluetooth Classic** in the app. If the adapter appears in the scan but
  connects and immediately drops, unpair it from the phone, power-cycle the dongle and pair again.
  Only one app can hold the Classic connection at a time.
- **Bluetooth LE** — choose **Bluetooth LE**, keep the adapter awake and close any other OBD app.
  A Classic-only clone does not advertise over LE; if it never appears, switch to Classic.
- **Wi-Fi** — join the adapter's Wi-Fi network in Android Settings, then enter the address shown in
  its manual (commonly `192.168.0.10:35000`). Mobile data can take over the connection on some
  phones; disable it while connected if reads time out. Android may warn that the network has no
  internet — keep the connection.
- **USB** — connect through an OTG adapter, allow the USB permission prompt, and choose
  **USB serial**. Supported chipsets are FTDI, CH340, CP210x, PL2303 and CDC-ACM. A charge-only
  cable or a hub without power will not work.
- **Clone adapters** — cheap "ELM327 v1.5" clones often drop characters, ignore commands or
  misreport the protocol. If reads fail intermittently, lower the poll rate in Settings, keep the
  engine running (a weak battery causes resets) and try a reputable adapter. The Connect screen
  shows a clone warning and a round-trip benchmark.
- **Recording stops or will not start** — a recording write failure is reported on the dashboard and
  in a notification; the partial file is kept in Recordings. If Android stops the background
  service, keep the app open or allow notifications so the recording status stays visible.

### What the labels mean

- **Live** — a fresh sample arrived within the last 3 seconds. **Delayed** — the last sample is
  older than 3 seconds. **Vehicle offline** — the adapter answers, but the ECU has stopped
  responding; old engine values are not shown as current.
- **Valid** — the stage reached its target duration with enough samples. **Partial** — the capture
  ended early or was shorter than the target. **Not exercised** — the required stimulus did not
  happen. **Insufficient quality** — too few samples or too short a capture. **Skipped** /
  **Interrupted** — the stage was skipped or lost its conditions.
- **Inconclusive** — the data does not distinguish the candidate causes. **Unable to run** — a
  required signal or prerequisite is missing. **Safety stopped** — a safety limit was exceeded or a
  stop condition was present. **Unsupported** — the vehicle does not report a required signal.
  **Stale** — the value is too old to use as evidence.

## Safe operation

- Do not interact with the phone while driving. Set up the adapter and start a recording before
  moving, or use a passenger to operate the app.
- Run load, rev and throttle tests only while parked in a safe location with the handbrake on.
- Do not run guided tests with a flashing MIL, a known active misfire, an overheating engine or a
  visibly damaged engine bay. The app blocks some of these, but it cannot prove the car is safe.
- Generic OBD-II exposes only emissions-related data. A clean result is not a mechanical
  inspection, and a single capture cannot prove a part is healthy or failed.

## Uninstall

Long-press the app icon → **App info** → **Uninstall**. Settings and recordings are removed with the app, so export any recordings you want to keep first.

## Security and privacy

- No accounts, ads, analytics or telemetry.
- Settings, vehicle identity, recordings and reports stay on the device until you share them.
- The app requests Bluetooth, USB, Internet and (Android 11 and lower) location for adapter discovery.
- The source is MIT-licensed: <https://github.com/clifarr/carstalker>.
- Privacy policy: <https://clifarr.github.io/carstalker/privacy-policy.html>.
