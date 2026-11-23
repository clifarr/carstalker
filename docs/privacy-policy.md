# CarStalker Privacy Policy

**Effective date:** 23 November 2026
**App:** CarStalker (`com.carstalker.app`)
**Status:** Beta

CarStalker is an OBD-II diagnostic app for Android. This policy explains what the app does with your data.

> **Hosting note:** publish this file at a public URL (for example GitHub Pages or your own site) and use that URL in Google Play Console. The same text is bundled in the app under **Settings → About → Privacy policy**.

## Summary

- CarStalker does not collect, transmit, sell or share your personal data.
- There is no account, no analytics, no advertising and no cloud service.
- Settings, vehicle identity, recordings and reports stay on your device until you choose to share them.

## Data stored on your device

CarStalker stores the following in its private app storage:

- app settings such as units, theme, polling interval and adapter defaults;
- the last vehicle identity read from the car, including the VIN;
- drive recordings (CSV) and diagnostic reports (PDF) you create;
- custom PID definitions;
- guided diagnostic results and saved baselines.

This data is not uploaded anywhere by CarStalker. Android's app sandbox protects it, and CarStalker disables cloud backup and device-to-device transfer for its data.

## Permissions and why they are needed

| Permission | Why it is needed |
| --- | --- |
| Bluetooth | To find and connect to a Bluetooth OBD-II adapter. |
| Location (Android 11 and lower only) | Android requires location permission for Bluetooth device discovery. CarStalker uses it only to find your adapter. It does not read, use or store your location. |
| USB | To connect to a USB OBD-II adapter. |
| Internet | To connect to a Wi-Fi OBD-II adapter on your local network. CarStalker does not contact a server. |

## Sharing

Recordings and reports are shared only when you choose **Share** or **Print**. The app you share to is selected by you. CarStalker does not share data on its own.

## Retention and deletion

Data stays on your device until you delete it. You can delete recordings in the app or clear the app's storage in Android settings. Because CarStalker does not collect data on a server, there is no server-side data to access, correct or delete.

## Security

Data is stored in app-private storage protected by Android's app sandbox. Recordings and reports leave the app only when you choose to share or print them.

## Children

CarStalker is not directed at children.

## Changes

If this policy changes, the effective date above will be updated and the new version will be published at this URL.

## Contact

For privacy questions, use the developer email shown on the CarStalker Google Play listing or open an issue at [github.com/clifarr/carstalker/issues](https://github.com/clifarr/carstalker/issues).
