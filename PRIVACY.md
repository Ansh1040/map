# Drive Alert — Privacy Policy

_Last updated: 21 October 2026_

Drive Alert is a free, open-source Android app ([source code](https://github.com/Ansh1040/map)) that warns drivers before traffic-enforcement cameras so they can keep to the speed limit. This policy explains what the app does with your data. It applies to the app as published from this repository.

## Summary

- Your **location is processed on your phone**: while you use the app (map, nearby cameras, GPS check, writing a report) and during a drive **you** start. It is never sold, shared with advertisers or used for ads, and trips are never uploaded.
- Your position itself leaves your phone **only** in a camera report you explicitly choose to publish as a public OpenStreetMap note. Apart from that, the map requests map images for the area around you from OpenFreeMap (see Network services).
- There is **no account, no analytics, no advertising SDK and no trip history upload**.
- The app talks to three free public services over HTTPS (described below).

## Location

**While the app is open**, Drive Alert reads your current location on the phone to show where you are on the map, list nearby cameras with their distance and direction from you (Home and camera details), check GPS accuracy (calibration screen) and attach a position to a camera report you are writing. The map opens around your position, so map images for that area are requested from OpenFreeMap (see below).

**When you press Start Drive**, Drive Alert uses precise location until you stop alerts, the drive ends, or 30 minutes pass without movement. This includes times when other apps such as Google Maps are on screen and when the phone is locked; a notification with a **Stop** button is shown the whole time.

When the app is closed and alerts are off, Drive Alert does not use your location. The app never requests "background location" permission and never starts by itself (not after a reboot, not after the system stops it).

Camera matching is done on the phone against the downloaded camera database.

## Data stored on your phone

- **Alert history**: which camera was announced and when — **without your coordinates**. Deleted automatically after 30 days; you can delete it any time (Settings → Delete local history, or History → Clear).
- **Camera reports you create**, and their status.
- **Settings**.

This data is excluded from Android cloud backup and device-to-device transfer. Uninstalling the app deletes it.

## Network services

All connections use HTTPS.

| Service | When | What is sent |
|---|---|---|
| **GitHub** (raw.githubusercontent.com) | About once a day (Wi-Fi by default), and when you tap *Update now*. The file itself is refreshed weekly. | A request for one public file: the OpenStreetMap camera snapshot. No location, no identifiers. GitHub sees your IP address, like any website. |
| **OpenFreeMap** (tiles.openfreemap.org) | Only while the map is on screen | Requests for map images of the area shown, which is initially the area around you. OpenFreeMap sees your IP address and that area. Alerts do not need the map. |
| **OpenStreetMap API** (api.openstreetmap.org) | Only if you submit a report **and** tick "Publish publicly on OpenStreetMap" | The report text and one position. New, temporary and wrong-position reports use your phone's current position; other reports use the mapped camera's position. It becomes a **public OpenStreetMap note without an account name** that anyone can read. OpenStreetMap also receives your IP address and stores it with the note, visible to its administrators. Published notes cannot be deleted from the app. |

Each service has its own privacy policy: [GitHub](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement), [OpenFreeMap](https://openfreemap.org/), [OpenStreetMap Foundation](https://osmfoundation.org/wiki/Privacy_Policy).

## Logging

Release builds do not log precise coordinates. Crash and debug logs stay on the device. Test (debug) builds send published reports to the OpenStreetMap test server, never to the real map, and never publish reports about synthetic test cameras or simulated positions.

## Your choices and rights

You can stop alerts at any time, revoke location or notification permission in Android settings, delete local history, or uninstall the app. Because the app keeps your data only on your device, there is no server-side copy to access, correct or delete — except reports you chose to publish as OpenStreetMap notes, which are public and governed by OpenStreetMap.

## Children

Drive Alert is a driving aid and is not directed at children.

## Contact and grievances

Drive Alert is an open-source project. For privacy questions, data requests or grievances (including under India's Digital Personal Data Protection Act, 2023), open an issue at <https://github.com/Ansh1040/map/issues>. Do not post personal data in a public issue; ask for a private contact channel instead.

## Changes

Changes to this policy are made in this file; its history is public in the repository.
