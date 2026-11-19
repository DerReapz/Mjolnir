# Mjolnir — Design Spec

**Date:** 2026-11-18 · **Status:** Draft for review (rev 4: adds any-device linking — PC↔PC, phone↔phone, PC↔phone)

## 1. What it is

Mjolnir moves browser tabs and files between an Android phone and a Windows PC.

- **Phone → PC ("the throw"):** the user selects tabs and files in the phone app, holds an on-screen hammer button, and flicks their wrist. On the PC they selected, tabs open in the default browser and files are saved to `Downloads\Mjolnir`.
- **PC → phone:** the user pastes or drags links, and drops or picks files and folders, in the PC tray app, or right-clicks files or folders in Explorer and picks *Send to → Mjolnir*. They send them to a chosen phone. The phone shows a notification. Links open on tap, and files are saved to `Download/Mjolnir`.

It has three parts: an Android app (APK), a Windows tray app, and a small self-hosted **relay server** so the two work on any network.

## 2. Decisions taken with the user

| Question | Decision |
|---|---|
| Where phone tabs come from | **Android share sheet.** In Chrome's tab switcher: *Select tabs → Share → Mjolnir*. A single link shared from any app also works. Mjolnir is not a browser. |
| What can be sent | **Tabs (links), files and whole folders, in both directions.** |
| Interrupted transfers | **Resume** where they stopped, in both directions. |
| Large files on the phone | The user chooses **Wi-Fi only or mobile data**, with a one-off override. |
| PC shell integration | **Explorer right-click *Send to → Mjolnir*.** |
| PC operating system | **Windows 10/11 (x64) only.** |
| Directions | **Both.** Phone→PC by the throw gesture; PC→phone from the tray app. |
| Network | **Anywhere**, through a relay server. |

## 3. Design decisions (reviewable)

- **One language, one build.** Kotlin everywhere in one Gradle build with five modules: `protocol` (wire types and rules), `relay-client` (shared client library), `server`, `desktop` and `android`.
- **Relay:** a Ktor server with a SQLite database and a directory for file blobs, shipped as a Docker image. TLS is terminated by the host (Fly.io or any HTTPS reverse proxy).
- **No accounts.** Each device registers once and gets a bearer token. Devices are linked by **pairing**: the PC shows a 6-character code, the phone enters it, and the PC user clicks *Accept*.
- **Delivery model:** the relay stores each send (its tabs and file references) in the recipient's **inbox** until the recipient acks it, or for up to 7 days. The PC holds a WebSocket and gets new deliveries instantly. The phone is woken by a **Firebase Cloud Messaging (FCM)** data message used only as a doorbell; the contents are always fetched from the inbox. If Firebase is not configured, the phone checks its inbox when the app opens and every 15 minutes.
- **Files go through the relay.** Each file is uploaded first as a standalone blob, in **8 MB chunks** to a resumable upload session, and then attached to a send. The relay keeps a blob only until the recipient acks the delivery (or it expires). Blobs uploaded but never sent are deleted after 1 hour. The relay has a total storage cap.
- **Resumable transfers.**
  - Uploads: the relay records each session's received offset, and the client asks for it and continues from there.
  - Downloads: they use HTTP `Range` requests and continue from the partial file kept in a hidden staging folder.
  - Incomplete uploads are dropped after 24 hours.
- **All-or-nothing receiving.** Every file in a delivery is downloaded and SHA-256-checked in the staging folder before any of it is published. The user never sees a half-written file. A hash mismatch discards that file's partial data, and the next attempt starts it over.
- **Links and files travel separately.** Links are sent at once. Files go through a persistent **outbox** that uploads in the background, survives app restarts and retries when the connection returns. Links and files never share a delivery, so links are never held up by a large file.
- **No duplicate sends.** The relay accepts each uploaded file in only one send. If an outbox resends after a crash, the relay answers `409 already sent`, and the outbox treats that as success.
- **Folders** keep their structure. Each file carries a sanitized `relativePath` such as `Photos/2024/a.jpg`. The receiver chooses one non-clashing top-folder name per delivery (`Photos`, else `Photos (1)`, …). Empty folders are not transferred, and symbolic links are not followed.
- **Large files on the phone:** a delivery or outgoing send whose files total more than the threshold (default **25 MB**) follows the setting *Wait for Wi-Fi* (default) or *Use mobile data*. A waiting transfer shows a notification with a one-off *Download now* / *Send now* that uses mobile data for that transfer only. The PC always transfers immediately.
- **Explorer *Send to*:** the app keeps a `Mjolnir` shortcut in the user's *Send to* folder (on by default, switchable in Settings). Explorer starts `Mjolnir.exe` with the selected paths. If Mjolnir is already running, the new process hands the paths to the running one over a loopback socket protected by a random token, then exits.
- **Files are saved, never opened automatically.** On the PC, saved files get Windows' "downloaded from the internet" mark (Mark of the Web), so SmartScreen and Office warn just as they would for a browser download.
- **Phone minimum is Android 10 (API 29),** so files can be saved to Downloads through MediaStore without any storage permission.
- **Privacy:** the relay can read the links and files it carries (there is no end-to-end encryption). This is acceptable because the user hosts the relay. E2E encryption is a non-goal for v1.

## 4. User flows

1. **Collect:** the user shares tabs or files (from Chrome, the gallery, a file manager or any app) to Mjolnir, or taps *Add files* or *Add folder* in the app. Shared files are copied into the app straight away, because the sharing app's permission is temporary. A folder added in-app is read in place, through a persistent permission. Everything lands in the phone's **tray**, a persistent list, and newly shared items are auto-selected. Sharing again adds to the tray.
2. **Throw:** the user picks a target PC (the last choice is remembered), holds the hammer button and flicks. The phone vibrates and the hammer flies off the top of the screen. Links are sent at once: on success they leave the tray; on failure they stay in the tray, still selected, with an error. Selected files and folders move to the **On the way** list and upload in the background with a progress notification. They wait for a connection, and for Wi-Fi if they are large and the setting says so. When they arrive the phone notifies *"Thrown 2 files to DESKTOP-4F2K"*.
3. **Catch on PC:** the tray app saves the files and folders into `Downloads\Mjolnir` (changeable in Settings), then opens each tab in the default browser, and shows a tray notification: *"Caught 2 tabs and 1 file from Pixel 8"*. The tray menu item *Open received files* opens the folder.
4. **Send to a device:** in the tray app, the user picks *Send to device…* (the target can be a phone or another PC). They can paste or drop text, and drop files or folders or click *Add files…* / *Add folder…*. In Explorer, *Send to → Mjolnir* opens this window with the selection already in it. They choose which links and files to send and to which phone. Links go at once, and files go through the PC's outbox. The phone downloads in the background (or waits for Wi-Fi), showing a progress notification while files transfer, then notifies *"Caught 1 file from DESKTOP-4F2K"*. Tapping it opens the **Caught** screen, where *Open all links* opens the links and tapping a file opens it with an installed app.
5. **Pair (any two devices):** any device can link to any other — PC↔phone, PC↔PC, phone↔phone, and a device can have many partners. One device shows a code like `K7M-2QX` for 5 minutes (PC: *Pair a device…*; phone: *Devices → Pair a device → Show a code*); the other enters it (PC: *Pair a device… → Enter a code instead*; phone: *Devices → Pair a device → Enter a code*). The code-showing device asks *"Pair with Pixel 8?"*: Accept or Reject; the entering device shows the result. Both apps list every paired device as a target, labelled *PC* or *Phone*; the phone's throw and the PC's send work to either kind.

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
| `POST /v1/uploads` `{relativePath, mimeType, sizeBytes}` | device | `201 {uploadId, offset: 0}`; `413` too big; `507` relay storage full (it counts space reserved by unfinished uploads) |
| `GET /v1/uploads/{id}` | uploader | `{uploadId, offset, file?}` |
| `PUT /v1/uploads/{id}` (body = chunk ≤ 8 MB; `Content-Range: bytes start-end/total`) | uploader | `{uploadId, offset, file?}`; `file` (`{id, name, relativePath, sizeBytes, mimeType, sha256}`) is present once complete; `409` with the current status if `start` ≠ the stored offset |
| `GET /v1/files/{id}` (optional `Range: bytes=N-`) | uploader, or recipient of the delivery holding it | the file bytes, or `206` with the tail; `404` for anyone else |
| `POST /v1/send` `{to, tabs:[{url, title?}], fileIds:[…]}` | device (60/min) | `202 {deliveryId}`; `400` if empty, invalid, mixing tabs and files, or a file is not yours; `409` if these files were already sent to `to`; `403` if not paired with `to` |
| `GET /v1/inbox` | device | deliveries addressed to me (`{id, from, tabs, sentAtEpochMs, files}`), oldest first |
| `DELETE /v1/inbox/{id}` | recipient | `204` (idempotent ack); deletes the delivery's file blobs |
| `GET /v1/ws` (WebSocket) | device | server pushes events: `delivery`, `pair_requested`, `paired`, `unpaired` |

## 7. Rules and limits

- **Only `http` and `https` URLs** are ever sent, stored or opened. Senders filter them, the relay rejects anything else, and receivers filter again before opening.
- Max **100 tabs per send**. A throw of more tabs is split into batches of 100.
- Max URL length **8192**. Titles are truncated to **300** characters. Device names are **1–64** characters.
- **Files:** at most **1000 per send**, so a folder of up to 1000 files always arrives as one delivery, and **100 MB each** (the relay can lower this with `MJOLNIR_MAX_FILE_MB`). The relay's total storage cap is `MJOLNIR_MAX_STORAGE_MB`, default **2048 MB**. Upload chunks are 8 MB. Unfinished uploads are deleted after 24 hours.
- **Relative paths** are cleaned segment by segment. `.`, `..` and empty segments are dropped, each remaining segment goes through the file-name rules below, and at most 16 levels and 1024 characters are kept.
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
| Hammer button, nothing paired | `Pair a device first` (disabled) |
| While sending links | `Throwing…` |
| Files queued | `Throwing {items} to {pc} in the background` |
| Phone *On the way* states / notifications | `Throwing {name}… 45%` · `Waiting for a connection` · `Waiting for Wi-Fi to throw {items} ({size}) to {pc}` with action `Send now` |
| Files thrown | `Thrown {items} to {pc}` (notification) |
| Files given up | `Not thrown: {reason}. Your files are back in the tray.` |
| Throw success | `Thrown {items} to {pc}` |
| Throw failure | `Not thrown: {reason}. Your tabs are still here.` |
| Share with no links or files | `No links found in what you shared` |
| File too big / unreadable | `{name} is too big (max 100 MB)` · `Couldn't read {name}` |
| Catch notification (PC and phone) | `Caught {items} from {device}` |
| Phone receiving files | `Receiving {name}… 45%` · `Waiting for Wi-Fi: {items} ({size}) from {device}` with action `Download now` |
| Folder limits | `{folder} has too many files (max 1000)` · `{n} file(s) over 100 MB were left out` |
| Phone large-file setting | `Large files (over {N} MB)`: `Wait for Wi-Fi` / `Use mobile data`; threshold `10` / `25` / `50` / `100` MB |
| Phone, no app for a file | `No app can open {name}` |
| Pairing prompt (code-showing device, PC or phone) | `Pair with {name}?` with buttons `Accept` / `Reject` |
| Pairing, waiting (code-entering device) | `Waiting for {name} to accept…` |
| Pairing errors (code-entering device) | `That code should be 6 letters and numbers` · `Code not found or expired. Get a new code on the other device.` · `Too many tries. Wait a minute.` · `{name} said no.` · `The code expired. Get a new code on the other device.` |
| Pairing choices | PC pair window: `Enter a code instead` / `Show a code instead`; phone: `Pair a device` → `Enter a code` / `Show a code`; target labels `PC` / `Phone` |
| PC pairing window | `Expires in m:ss` · `Get a new code` · `Paired with {name}` |
| PC Send window buttons | `Add files…` · `Add folder…` · `Send` |
| PC tray menu | `Send to device…` · `Send clipboard to {name}` · `Open received files` · `Pair a device…` · `Paired devices…` · `Settings…` · `Quit Mjolnir` |
| PC send result | `Sent {items} to {phone}` · `Not sent: {reason}.` |
| Failure reasons | `no connection` · `{peer} is no longer paired` · `too many throws, wait a minute` · `this device was reset on the relay` · `a file is too big for the relay` · `the relay is out of space` · `the relay had a problem` |
| PC second launch | `Mjolnir is already running. Look for the hammer in the system tray.` |
| Settings (both) | `Use an https:// address` · `Changing the relay unpairs all devices.` · PC: `Start Mjolnir when I sign in to Windows`, `Save received files to`, `Show Mjolnir in Explorer's Send to menu` · phone sensitivity: `Low` / `Medium` / `High` |
| Re-registration notice | `This device was reset on the relay. Pair again.` |

The throw-failure text keeps "Your tabs are still here." even when files are involved; it refers to everything left in the tray.

## 9. Non-goals (v1)

The following are out of scope for v1:
- End-to-end encryption.
- macOS, Linux and iOS; a browser extension; reading the PC browser's open tabs.
- A built-in phone browser.
- User accounts, and a Play Store listing (the APK is sideloaded).
- Opening received files automatically.
- Empty folders and symbolic links.
- Windows metered-connection handling (the PC always transfers immediately).
- A classic right-click verb outside *Send to*.
- Per-file sizes above 100 MB by default (the relay setting can raise it).
