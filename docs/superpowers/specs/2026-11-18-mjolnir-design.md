# Mjolnir — Design Spec

**Date:** 2026-11-18 · **Status:** Draft for review

## 1. What it is

Mjolnir moves browser tabs between an Android phone and a Windows PC.

- **Phone → PC ("the throw"):** the user selects tabs in the phone app, holds an on-screen hammer button, and flicks their wrist. The tabs open in the default browser of the PC they selected.
- **PC → phone:** the user pastes or drags links into the PC tray app and sends them to a chosen phone. The phone shows a notification, and tapping it opens the links.

It has three parts: an Android app (APK), a Windows tray app, and a small self-hosted **relay server** so the two work on any network.

## 2. Decisions taken with the user

| Question | Decision |
|---|---|
| Where phone tabs come from | **Android share sheet.** In Chrome's tab switcher: *Select tabs → Share → Mjolnir*. A single link shared from any app also works. Mjolnir is not a browser. |
| PC operating system | **Windows 10/11 (x64) only.** |
| Directions | **Both.** Phone→PC by the throw gesture; PC→phone from the tray app (paste/drag links). |
| Network | **Anywhere**, through a relay server. |

## 3. Design decisions (reviewable)

- **One language, one build.** Kotlin everywhere in one Gradle build with five modules: `protocol` (wire types and rules), `relay-client` (shared client library), `server`, `desktop` and `android`.
- **Relay:** a Ktor server with a SQLite database, shipped as a Docker image. TLS is terminated by the host (Fly.io or any HTTPS reverse proxy).
- **No accounts.** Each device registers once and gets a bearer token. Devices are linked by **pairing**: the PC shows a 6-character code, the phone enters it, and the PC user clicks *Accept*.
- **Delivery model:** the relay stores each send in the recipient's **inbox** until the recipient acks it, or for up to 7 days. The PC holds a WebSocket and gets new deliveries instantly. The phone is woken by a **Firebase Cloud Messaging (FCM)** data message used only as a doorbell; the tabs themselves are always fetched from the inbox. If Firebase is not configured, the phone checks its inbox when the app opens and every 15 minutes.
- **Privacy:** the relay can read the URLs it carries (there is no end-to-end encryption). This is acceptable because the user hosts the relay. E2E encryption is a non-goal for v1.

## 4. User flows

1. **Collect:** the user shares tabs to Mjolnir. They land in the phone's **tray**, a persistent list, and newly shared tabs are auto-selected. Sharing again adds to the tray.
2. **Throw:** the user picks a target PC (the last choice is remembered), holds the hammer button and flicks. The phone vibrates, the hammer flies off the top of the screen, and the selected tabs are sent. On success they leave the tray. On failure they stay in the tray, still selected, with an error.
3. **Catch on PC:** the tray app opens each tab in the default browser and shows a tray notification: *"Caught 3 tabs from Pixel 8"*.
4. **Send to phone:** in the tray app, the user picks *Send tabs to phone…*, pastes or drops text, and chooses which extracted links to send and to which phone. The phone notification reads *"Caught 2 tabs from DESKTOP-4F2K"*. Tapping it opens the **Caught** screen, which has *Open all* and per-link open.
5. **Pair:** on the PC, the user picks *Pair a phone…*, which shows a code like `K7M-2QX` for 5 minutes. On the phone, *Devices → Pair a PC*, then type the code. The PC asks *"Pair with Pixel 8?"*: Accept or Reject. The phone shows the result.

## 5. The throw gesture

- The detector runs **only while the hammer button is held**. Pressing the button arms it, and releasing it without a flick cancels.
- An **arm delay of 150 ms** after the press ignores the jolt of the thumb press.
- A flick is detected when the **linear-acceleration magnitude ≥ threshold on 2 consecutive samples** (sampling at `SENSOR_DELAY_GAME`).
- Thresholds by sensitivity setting: **LOW 22 m/s², MEDIUM 16 m/s² (default), HIGH 11 m/s²**.
- **One throw per hold.**
- The sensor is `TYPE_LINEAR_ACCELERATION`. If the phone lacks it (no gyroscope), the app uses `TYPE_ACCELEROMETER` with a low-pass gravity estimate subtracted (alpha 0.8).
- Feedback: a haptic tick on arm, a strong haptic on throw, and the hammer flying off the top of the screen.

## 6. Relay API

The base path is `/v1`, the body format is JSON, and authentication is `Authorization: Bearer <token>` on every route except `/health` and `POST /v1/devices`. Errors return `{"error": "..."}`.

| Route | Who | Result |
|---|---|---|
| `GET /health` | anyone | `200 ok` |
| `POST /v1/devices` `{name, kind}` | anyone (5/hour per IP) | `201 {deviceId, token}` |
| `GET /v1/devices/me` | device | `{deviceId, name, kind}`; used to check that a token is still valid |
| `PATCH /v1/devices/me` `{name}` | device | the updated `{deviceId, name, kind}` (rename) |
| `PUT /v1/devices/me/push-token` `{token}` | device | `204` |
| `POST /v1/pairings` | device (code owner) | `201 {code, expiresAtEpochMs}`; replaces the owner's previous code |
| `POST /v1/pairings/claim` `{code}` | device (10/min) | `202 {pairingId, owner}`, where `owner` is the PC's `{deviceId, name, kind}`; `404` if the code is unknown, used or expired; `400` if it is your own code |
| `GET /v1/pairings/{id}` | owner or claimer | `{state, peer?}`, where `state` is one of `PENDING`, `CLAIMED`, `ACCEPTED`, `REJECTED` or `EXPIRED` |
| `POST /v1/pairings/{id}/accept` / `reject` | owner | `204`; accepting links the two devices |
| `GET /v1/peers` | device | `[{deviceId, name, kind}]` |
| `DELETE /v1/peers/{deviceId}` | device | `204`; unlinks both sides |
| `POST /v1/tabs` `{to, tabs:[{url, title?}]}` | device (60/min) | `202 {deliveryId}`; `400` invalid; `403` not paired with `to` |
| `GET /v1/inbox` | device | deliveries addressed to me, oldest first |
| `DELETE /v1/inbox/{id}` | recipient | `204` (idempotent ack) |
| `GET /v1/ws` (WebSocket) | device | server pushes events: `delivery`, `pair_requested`, `paired`, `unpaired` |

## 7. Rules and limits

- **Only `http` and `https` URLs** are ever sent, stored or opened. Senders filter them, the relay rejects anything else, and receivers filter again before opening.
- Max **100 tabs per send**. A throw of more tabs is split into batches of 100.
- Max URL length **8192**. Titles are truncated to **300** characters. Device names are **1–64** characters.
- **Pairing code:** 6 characters from `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, shown as `XXX-XXX`. Input is forgiving (case, spaces, dashes, `O`→`0`, `I`/`L`→`1`). It is valid for 5 minutes, single use, and needs the owner's acceptance.
- **Deliveries** are kept at most **7 days**. A delivery must never open twice on a receiver.
- **Relay URLs** must be `https://`. Plain `http://` is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2` (the emulator host).
- If the relay answers `401`, the device's registration was lost: the client clears its credentials, registers again, and tells the user to re-pair.

## 8. Copy (exact strings)

| Where | Text |
|---|---|
| Hammer button, idle | `Hold & flick to throw` |
| Hammer button, held | `Flick!` |
| Hammer button, no PC paired | `Pair a PC first` (disabled) |
| While sending | `Throwing…` |
| Throw success | `Thrown {n} tab(s) to {pc}` (`1 tab` / `3 tabs`) |
| Throw failure | `Not thrown: {reason}. Your tabs are still here.` |
| Share with no links | `No links found in what you shared` |
| Catch notification (PC and phone) | `Caught {n} tab(s) from {device}` |
| PC pairing prompt | `Pair with {name}?` with buttons `Accept` / `Reject` |
| Phone pairing, waiting | `Waiting for {pc} to accept…` |
| Phone pairing errors | `That code should be 6 letters and numbers` · `Code not found or expired. Get a new code on the PC.` · `Too many tries. Wait a minute.` · `{pc} said no.` · `The code expired. Get a new code on the PC.` |
| PC pairing window | `Expires in m:ss` · `Get a new code` · `Paired with {name}` |
| PC send result | `Sent {n} tab(s) to {phone}` · `Not sent: {reason}.` |
| Failure reasons | `no connection` · `{peer} is no longer paired` · `too many throws, wait a minute` · `this device was reset on the relay` · `the relay had a problem` |
| PC second launch | `Mjolnir is already running. Look for the hammer in the system tray.` |
| Settings (both) | `Use an https:// address` · `Changing the relay unpairs all devices.` · PC: `Start Mjolnir when I sign in to Windows` · phone sensitivity: `Low` / `Medium` / `High` |
| Re-registration notice | `This device was reset on the relay. Pair again.` |

## 9. Non-goals (v1)

The following are out of scope for v1: end-to-end encryption, macOS, Linux and iOS, a browser extension, reading the PC browser's open tabs, phone↔phone and PC↔PC sending, a built-in phone browser, user accounts, and a Play Store listing (the APK is sideloaded).
