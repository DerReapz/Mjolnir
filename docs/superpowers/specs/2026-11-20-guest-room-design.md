# Mjolnir guest room: design

Status: draft for approval. Date: 2026-11-20. Builds on `2026-11-18-mjolnir-design.md` (rev 4) and `2026-11-20-pc-main-window-design.md` (built first).

## Why
The owner wants to exchange files and folders with someone who does **not** have Mjolnir installed, using only a web browser, as a one-off. No install, no account, nothing remembered afterwards.

## Goals
- From the PC (Guest room tab) or the phone, the owner opens a **guest room** and gets an invite link (and a QR code on the PC).
- The guest opens the link in any modern browser (phone or computer) and is connected straight away — nothing to type.
- For **one hour**, both sides can send files, folders and links to each other, as many times as they like.
- The room ends when the owner taps *End room* or the hour runs out. The link stops working, the guest disappears from the owner's devices, and anything not yet downloaded is deleted from the relay.

## Non-goals
- Guests pairing permanently, or reaching any of the owner's other devices.
- Guest-to-guest exchange (two browsers, no Mjolnir).
- Throwing with the wrist gesture from the guest page.
- Extending a room past one hour (the owner can open a new room).

## Anti-goals
- Anything that lets a guest, or someone who guesses or intercepts a link after the room ends, read or write anything beyond that one room.
- A heavy page: the guest page must load fast on mobile data (target < 100 KB before fonts, no framework, no build step).

## Approaches considered
1. **The guest is a temporary device on the relay (chosen).** A new device kind `GUEST`, created by redeeming the invite link, linked only to the owner device, expiring with the room. The guest page is plain HTML/JS served by the relay and reuses the existing upload (chunked, resumable), send, inbox, download and WebSocket APIs. Most reuse, and the owner's apps treat the guest like any destination.
2. A separate "drop box" API tied to the link, with no device. Easier to isolate, but duplicates upload/resume/expiry, and the apps would need a second way to send.
3. A Kotlin/Wasm or Compose-for-Web guest client sharing app code. Multi-MB page, heavy build — overkill for one page.

## How it works
### Opening a room (owner)
- PC: Guest room tab → *Open a guest room*. Phone: Devices screen → *Guest room*. Only one open room per owner device at a time.
- The relay creates a room: id, owner device id, a **long random invite secret** (≥ 128 bits, URL-safe), `expiresAt = now + 60 min`. The link is `https://{relay}/g/{secret}`. (The mockup's short `K7Q-29P` is only illustrative.)
- The owner sees the link with *Copy link* (and *Share…* on the phone), a QR code on the PC, the countdown, and *End room*.

### Joining (guest)
- `GET /g/{secret}` serves the guest page. The page redeems the secret once for a guest device token (`POST /v1/guest/join`), stored in that browser tab's `sessionStorage` so a reload rejoins.
- **One guest per room**: after the first redeem, the link is bound to that browser. Opening it elsewhere shows `This room is already in use.` Expired or ended rooms show `This room has closed.`
- On join, the owner gets a notification `Guest joined` and the room shows `Guest connected`.

### Exchanging
- The guest appears on the owner's device as a destination `Guest` (`Guest room · {mm:ss} left`) on the PC Send tab and in the phone's `Throw to` selector, until the room ends.
- Guest → owner: *Choose files* / *Choose folder* / drop (folder picking via `webkitdirectory`; drag-and-drop folders where the browser supports it). Uploads use the existing chunked, resumable upload API; a dropped connection resumes when the page is still open. Received items land where the owner's other devices' items land (PC save folder + Received tab; phone Caught/MediaStore) and are marked as from `Guest`.
- Owner → guest: items appear in the guest page's `For you` list. Files download directly (ranged, resumable by the browser). **Folders download as one `.zip` streamed by the relay** (stored, not recompressed). Links show *Open*.
- The guest page shows `You sent` with progress and a delivered tick.

### Ending
- *End room* or expiry: the relay revokes the guest token, removes the link, deletes the guest device, and deletes stored files that were sent **to the guest** and not yet downloaded; files sent **to the owner** follow the normal delivery rules (7 days) so a closed room never loses the owner's incoming files.
- The guest page switches to `This room has closed.`; the owner's apps drop the `Guest` destination.

## Relay security rules (must hold)
- A `GUEST` token can only: upload, send to its room's owner, read its own inbox, download files delivered to it, open the WebSocket, and leave. It cannot create pairing codes, claim codes, list peers beyond the owner, rename, register push tokens, or create rooms.
- Guest tokens are rejected after the room ends (checked on every request and on the WebSocket, which is closed at expiry).
- Room creation is limited per owner device (one open room) and rate-limited; join attempts are rate-limited per IP.
- Per-room storage cap (default 10 GB, configurable `MJOLNIR_GUEST_MAX_MB`) on top of the existing limits.
- Guest page responses carry a strict Content-Security-Policy (same-origin scripts only), `Referrer-Policy: no-referrer` (so the secret doesn't leak via Referer), and `X-Content-Type-Options: nosniff`. The secret is never logged.
- Downloads to the guest are served with `Content-Disposition: attachment` and the safe file-name rules already in use.

## The guest page
- Forge look (mockup `GuestWeb` on the design board): hammer + `MJOLNIR`, time left, `You're connected to {owner device name}.` (the owner's own device name, not a nickname), the drop area with *Choose files* / *Choose folder*, `For you`, `You sent`, closing note.
- Works on current Chrome, Safari (iOS 16+), Firefox, Edge. Mobile-first layout, fluid to desktop widths.
- Plain HTML/CSS/JS shipped inside the relay jar; fonts bundled (OFL) and served by the relay.

## Owner apps
- PC: Guest room tab per the `DesktopGuest` mockup — invite link, *Copy link*, QR code, countdown, `Guest connected`, exchanged list (`From guest` / `You sent` with progress and *Open folder*), drop area to send to the guest, *End room*. Sidebar badge shows the countdown.
- Phone: Devices → *Guest room* screen with the same parts minus the QR code, plus *Share…* (Android share sheet for the link). Sending to the guest is done by picking `Guest` in `Throw to`.

## Constraints
- Reuse existing protocol models where possible; new ones: `DeviceKind.GUEST`, room create/join/end requests and responses, a `room_ended` WebSocket event.
- Older app versions must not break when a guest exists: the relay only lists `GUEST` peers to the room's owner, and only to app versions that understand the kind (send it to owners only; the owner's app is updated in this project).
- Server tests cover every security rule above; client tests cover the room state machine.

## Left to the builder
- Exact endpoints, table layout, and how room expiry is scheduled (existing sweeper vs timer).
- Zip streaming implementation (`java.util.zip.ZipOutputStream` with STORED entries is fine).
- QR code generation on the PC (a small pure-Kotlin encoder or a tiny dependency).

## Decisions settled after the builder review (designer's calls)
- **Real link, extra rules on top.** Redeeming the invite creates the guest device and a normal `links` row to the owner device. Send, peers and inbox stay unchanged, and a guest-kind check adds the restrictions listed under the security rules.
- **The room belongs to the device that opened it.** There are no accounts, so only that device sees the `Guest` destination, gets the guest's items and can end the room. Both the PC and the phone can open rooms.
- **What ends a room:** *End room* or expiry, and nothing else. Unpairing other devices does not end it.
- **Limits.** The per-room cap defaults to the relay's global storage cap and can only be lowered (`MJOLNIR_GUEST_MAX_MB`). The existing per-file and per-send limits apply. Guest-page copy for limits:
  - `Too big. Files can be up to {n} MB.`
  - `Too many files. Send up to 1,000 at a time.`
  - `The relay is full right now. Try again later.`
- **Rejoining.** The guest token is kept in `localStorage`, keyed by room, so the same browser can close and reopen the link. Any other browser sees `This room is already in use.`
- **Downloads.** A folder zip is not resumable; an interrupted download simply starts again. Files keep ranged downloads.
- **What "downloaded" means.** The relay marks an item downloaded once the last byte of the file (or zip) has been served to the guest. Items stay in `For you` with a `Downloaded` tag and can be downloaded again until the room ends. The owner sees `downloaded by guest`. Everything sent to the guest is deleted when the room ends, so no ack is needed.
- **The link** is built by the owner's app from its configured relay address plus the secret the relay returns. No public-URL config is needed.
- **Compatibility.** Guest peers are only ever listed to the room's owner, and only updated apps can open rooms, so older apps never see a `GUEST` kind. Clients should still decode an unknown device kind leniently.
- **Android Guest room screen** (Devices → *Guest room*) is in scope, with its own state machine and tests.
