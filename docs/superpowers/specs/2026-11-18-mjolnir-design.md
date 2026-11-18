# Mjolnir — Design Spec

**Date:** 2026-11-18 · **Status:** Draft for review (rev 2: adds files)

## 1. What it is

Mjolnir moves browser tabs and files between an Android phone and a Windows PC.

- **Phone → PC ("the throw"):** the user selects tabs and files in the phone app, holds an on-screen hammer button, and flicks their wrist. On the PC they selected, tabs open in the default browser and files are saved to `Downloads\Mjolnir`.
- **PC → phone:** the user pastes or drags links, and drops or picks files, in the PC tray app, then sends them to a chosen phone. The phone shows a notification. Links open on tap, and files are saved to `Download/Mjolnir`.

It has three parts: an Android app (APK), a Windows tray app, and a small self-hosted **relay server** so the two work on any network.

## 2. Decisions taken with the user

| Question | Decision |
|---|---|
| Where phone tabs come from | **Android share sheet.** In Chrome's tab switcher: *Select tabs → Share → Mjolnir*. A single link shared from any app also works. Mjolnir is not a browser. |
| What can be sent | **Tabs (links) and files, in both directions.** |
| PC operating system | **Windows 10/11 (x64) only.** |
| Directions | **Both.** Phone→PC by the throw gesture; PC→phone from the tray app. |
| Network | **Anywhere**, through a relay server. |

## 3. Design decisions (reviewable)

- **One language, one build.** Kotlin everywhere in one Gradle build with five modules: `protocol` (wire types and rules), `relay-client` (shared client library), `server`, `desktop` and `android`.
- **Relay:** a Ktor server with a SQLite database and a directory for file blobs, shipped as a Docker image. TLS is terminated by the host (Fly.io or any HTTPS reverse proxy).
- **No accounts.** Each device registers once and gets a bearer token. Devices are linked by **pairing**: the PC shows a 6-character code, the phone enters it, and the PC user clicks *Accept*.
- **Delivery model:** the relay stores each send (its tabs and file references) in the recipient's **inbox** until the recipient acks it, or for up to 7 days. The PC holds a WebSocket and gets new deliveries instantly. The phone is woken by a **Firebase Cloud Messaging (FCM)** data message used only as a doorbell; the contents are always fetched from the inbox. If Firebase is not configured, the phone checks its inbox when the app opens and every 15 minutes.
- **Files go through the relay.** Each file is uploaded first as a standalone blob and then attached to a send. The relay keeps a blob only until the recipient acks the delivery (or it expires). Blobs uploaded but never sent are deleted after 1 hour. The relay has a total storage cap.
- **All-or-nothing receiving.** Every file in a delivery is downloaded and SHA-256-checked into a staging folder before any file is published or any tab opened. An interrupted transfer leaves nothing behind and is retried from the start.
- **Files are saved, never opened automatically.** On the PC, saved files get Windows' "downloaded from the internet" mark (Mark of the Web), so SmartScreen and Office warn just as they would for a browser download.
- **Phone minimum is Android 10 (API 29),** so files can be saved to Downloads through MediaStore without any storage permission.
- **Privacy:** the relay can read the links and files it carries (there is no end-to-end encryption). This is acceptable because the user hosts the relay. E2E encryption is a non-goal for v1.

## 4. User flows

1. **Collect:** the user shares tabs or files (from Chrome, the gallery, a file manager or any app) to Mjolnir, or taps *Add files* in the app. Shared files are copied into the app straight away, because the sharing app's permission is temporary. Everything lands in the phone's **tray**, a persistent list, and newly shared items are auto-selected. Sharing again adds to the tray.
2. **Throw:** the user picks a target PC (the last choice is remembered), holds the hammer button and flicks. The phone vibrates and the hammer flies off the top of the screen. Selected files upload first (the button shows progress), then everything is sent as one delivery. On success the items leave the tray. On failure they stay in the tray, still selected, with an error.
3. **Catch on PC:** the tray app saves the files into `Downloads\Mjolnir` (changeable in Settings), then opens each tab in the default browser, and shows a tray notification: *"Caught 2 tabs and 1 file from Pixel 8"*. The tray menu item *Open received files* opens the folder.
4. **Send to phone:** in the tray app, the user picks *Send to phone…*. They can paste or drop text, and drop files or click *Add files…*. They choose which links and files to send and to which phone. The phone downloads in the background, showing a progress notification while files transfer, then notifies *"Caught 1 file from DESKTOP-4F2K"*. Tapping it opens the **Caught** screen, where *Open all links* opens the links and tapping a file opens it with an installed app.
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

The base path is `/v1`, the body format is JSON (except file bodies), and authentication is `Authorization: Bearer <token>` on every route except `/health` and `POST /v1/devices`. Errors return `{"error": "..."}`.

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
| `POST /v1/files?name={name}` (body = raw bytes; `Content-Type` and `Content-Length` required) | device | `201 {id, name, sizeBytes, mimeType, sha256}`; `411` no length; `413` too big; `507` relay storage full |
| `GET /v1/files/{id}` | uploader, or recipient of the delivery holding it | the file bytes; `404` for anyone else |
| `POST /v1/send` `{to, tabs:[{url, title?}], fileIds:[…]}` | device (60/min) | `202 {deliveryId}`; `400` if empty, invalid, or a file is not yours or already sent; `403` if not paired with `to` |
| `GET /v1/inbox` | device | deliveries addressed to me (`{id, from, tabs, sentAtEpochMs, files}`), oldest first |
| `DELETE /v1/inbox/{id}` | recipient | `204` (idempotent ack); deletes the delivery's file blobs |
| `GET /v1/ws` (WebSocket) | device | server pushes events: `delivery`, `pair_requested`, `paired`, `unpaired` |

## 7. Rules and limits

- **Only `http` and `https` URLs** are ever sent, stored or opened. Senders filter them, the relay rejects anything else, and receivers filter again before opening.
- Max **100 tabs per send**. A throw of more tabs is split into batches of 100.
- Max URL length **8192**. Titles are truncated to **300** characters. Device names are **1–64** characters.
- **Files:** at most **20 per send** and **100 MB each** (the relay can lower this with `MJOLNIR_MAX_FILE_MB`). The relay's total storage cap is `MJOLNIR_MAX_STORAGE_MB`, default **2048 MB**. Larger selections are split into batches of 20 files.
- **File names** are cleaned on the relay and again on receive:
  - Any path part is dropped.
  - Windows-illegal characters (`<>:"/\|?*` and control characters) become `_`.
  - Reserved names (`CON`, `PRN`, `AUX`, `NUL`, `COM1`–`COM9`, `LPT1`–`LPT9`, with or without an extension) get a `_` prefix.
  - Trailing dots and spaces are removed.
  - The name is cut to 200 characters, keeping the extension; an empty name becomes `file`.

  An existing file is never overwritten: the new one is saved as `name (1).ext`, then `name (2).ext`, and so on.
- Received files are **never opened or executed automatically**. Each file's size and SHA-256 are checked after download.
- **Pairing code:** 6 characters from `0123456789ABCDEFGHJKMNPQRSTVWXYZ`, shown as `XXX-XXX`. Input is forgiving (case, spaces, dashes, `O`→`0`, `I`/`L`→`1`). It is valid for 5 minutes, single use, and needs the owner's acceptance.
- **Deliveries** are kept at most **7 days**. A delivery must never be caught twice on a receiver.
- **Relay URLs** must be `https://`. Plain `http://` is allowed only for `localhost`, `127.0.0.1` and `10.0.2.2` (the emulator host).
- If the relay answers `401`, the device's registration was lost: the client clears its credentials, registers again, and tells the user to re-pair.

## 8. Copy (exact strings)

`{items}` reads `1 tab`, `3 tabs`, `1 file`, `2 files`, or `2 tabs and 1 file`.

| Where | Text |
|---|---|
| Hammer button, idle | `Hold & flick to throw` |
| Hammer button, held | `Flick!` |
| Hammer button, no PC paired | `Pair a PC first` (disabled) |
| While sending | `Throwing…`, or `Throwing… 45%` while files upload |
| Throw success | `Thrown {items} to {pc}` |
| Throw failure | `Not thrown: {reason}. Your tabs are still here.` |
| Share with no links or files | `No links found in what you shared` |
| File too big / unreadable | `{name} is too big (max 100 MB)` · `Couldn't read {name}` |
| Catch notification (PC and phone) | `Caught {items} from {device}` |
| Phone receiving files | `Receiving {name}… 45%` |
| Phone, no app for a file | `No app can open {name}` |
| PC pairing prompt | `Pair with {name}?` with buttons `Accept` / `Reject` |
| Phone pairing, waiting | `Waiting for {pc} to accept…` |
| Phone pairing errors | `That code should be 6 letters and numbers` · `Code not found or expired. Get a new code on the PC.` · `Too many tries. Wait a minute.` · `{pc} said no.` · `The code expired. Get a new code on the PC.` |
| PC pairing window | `Expires in m:ss` · `Get a new code` · `Paired with {name}` |
| PC tray menu | `Send to phone…` · `Send clipboard to {phone}` · `Open received files` · `Pair a phone…` · `Paired devices…` · `Settings…` · `Quit Mjolnir` |
| PC send result | `Sent {items} to {phone}` · `Not sent: {reason}.` |
| Failure reasons | `no connection` · `{peer} is no longer paired` · `too many throws, wait a minute` · `this device was reset on the relay` · `a file is too big for the relay` · `the relay is out of space` · `the relay had a problem` |
| PC second launch | `Mjolnir is already running. Look for the hammer in the system tray.` |
| Settings (both) | `Use an https:// address` · `Changing the relay unpairs all devices.` · PC: `Start Mjolnir when I sign in to Windows`, `Save received files to` · phone sensitivity: `Low` / `Medium` / `High` |
| Re-registration notice | `This device was reset on the relay. Pair again.` |

The throw-failure text keeps "Your tabs are still here." even when files are involved; it refers to everything left in the tray.

## 9. Non-goals (v1)

The following are out of scope for v1:
- End-to-end encryption.
- macOS, Linux and iOS; a browser extension; reading the PC browser's open tabs.
- Phone↔phone and PC↔PC sending.
- A built-in phone browser.
- User accounts, and a Play Store listing (the APK is sideloaded).
- Resumable (partial) uploads and downloads.
- Sending whole folders.
- Opening received files automatically.
- Wi-Fi-only downloads.
- Explorer *Send to* integration.
