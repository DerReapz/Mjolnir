# Guest Room Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a Mjolnir device open a one-hour guest room. Someone without Mjolnir opens a link in any browser and exchanges files, folders and links with that device.

**Architecture:**
- **Relay:** gets `rooms` and `DeviceKind.GUEST`. Redeeming the invite secret creates a guest device linked only to the owner device. A route allowlist confines guest tokens. A sweeper ends expired rooms. The relay streams folder zips and marks items downloaded once the last byte has been served.
- **Guest page:** plain HTML/CSS/JS shipped in the relay jar. It reuses the existing upload, send, inbox and WebSocket APIs.
- **Owner apps:** a shared `GuestRoomController` drives the PC Guest room tab and the Android Guest room screen.
- **Runs after:** the main-window and spaces plans.

**Tech Stack:** Ktor 3.3 server + SQLite, vanilla ES2020 JS (no framework, no build step), Compose Desktop, Jetpack Compose, `io.nayuki:qrcodegen:1.8.0` (desktop QR codes, MIT, ~30 KB), Playwright (already installed in the environment) for one scripted end-to-end check.

**Spec:** `docs/superpowers/specs/2026-11-20-guest-room-design.md` (read it with this plan, including its "Decisions settled" section). Mockups: artboards `DesktopGuest`, `GuestWeb`.

## Global Constraints
- Rooms last exactly 60 minutes and can't be extended. Each owner device has one open room at a time. A room ends only on *End room* or expiry.
- The invite secret is ≥ 128 bits, URL-safe base64, generated with `SecureRandom`. The link is built by the owner app as `{its relay URL}/g/{secret}`. The secret is never logged.
- A guest token may only use:
  - `POST /v1/uploads`, `GET /v1/uploads/{id}`, `PUT /v1/uploads/{id}`
  - `GET /v1/files/{id}`, `GET /v1/files/zip`
  - `POST /v1/send` (to the owner only)
  - `GET /v1/inbox`, `DELETE /v1/inbox/{id}`
  - `GET /v1/ws`, `GET /v1/me`, `POST /v1/guest/ticket`

  Everything else returns 403 `guest`. Guest tokens are rejected (401) once the room ends, and the guest's WebSocket is closed at the end.
- Per-room storage cap: `min(MJOLNIR_GUEST_MAX_MB, global cap)`, defaulting to the global cap. The existing per-file and per-send limits apply.
- Responses for the guest page and its assets carry these headers:
  - `Content-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'`
  - `Referrer-Policy: no-referrer`
  - `X-Content-Type-Options: nosniff`
  - `Cache-Control: no-store` on `/g/*`
- `GUEST` peers are listed only to the room owner. Clients decode an unknown `DeviceKind` leniently (as PC).
- Guest-page copy, exact:
  - `You're connected to {owner device name}.`
  - `{mm:ss} left` in the header
  - `Drop files here, or`, `Choose files`, `Choose folder`
  - `For you`, `You sent`, `Download`, `Open`, `Downloaded`
  - `This room is already in use.`
  - `This room has closed.`
  - `Too big. Files can be up to {n} MB.`
  - `Too many files. Send up to 1,000 at a time.`
  - `The relay is full right now. Try again later.`
  - `Keep this page open. If it drops, it picks up where it left off.`
  - `This room closes in {n} minutes. Nothing to install; nothing is kept after it closes.`
- Owner-app copy, exact:
  - Room controls: `Open a guest room`, `Guest room`, `Copy link`, `Share…` (phone), `End room`, `Closes in {mm:ss}`
  - Room status and labels: `Guest connected`, `Guest joined`, `Exchanged`, `FROM GUEST`, `YOU SENT`, `downloaded by guest`, `Open folder`
  - Guest destination: `Guest`, `Guest room · {mm:ss} left`
  - Drop area: `Drop files or folders here to send them to the guest`
  - Side-panel notes: `Scan with a phone camera, or send the link.`, `Works in any browser — nothing to install.`, `Only this PC can exchange with the guest.` (`this phone` on Android), `Unclaimed files are deleted when the room closes.`
- Guest page weight: under 100 KB before fonts (check with `du` on the resource folder minus fonts).

## Review Focus
1. **The guest closes the tab during an upload and reopens the link:** the page rejoins with the stored token and resumes the upload from the relay's offset, provided the same file is chosen again. Copy explains this. Tested in G2 (`e2eRejoinResumes`, Playwright) and G1 (`rejoinWithStoredTokenWorks`).
2. **The room expires mid-transfer:** the guest's upload fails cleanly with `This room has closed.` Already-delivered files to the owner survive. Partial uploads are purged. Tested in G1 (`expiryDuringUploadKeepsOwnerDeliveries`).
3. **Folder names with unsafe characters or very deep paths from the guest:** they are cleaned by the existing safe-path rules on the owner side, and the zip entries to the guest use the same rules (no `../`). Tested in G1 (`zipEntriesUseSafePaths`).
4. **Old browsers without folder picking (`webkitdirectory` missing):** *Choose folder* is hidden, and files still work. Tested in G2 (`folderButtonHiddenWithoutSupport`, a JS unit check run in Playwright).
5. **The owner app restarts while a room is open:** it finds the room again through `GET /v1/rooms/current` and restores the tab or screen with the right countdown. Tested in G3 (`restoresOpenRoomOnStart`).

---

### Task G1: Relay rooms, guest devices and rules

**Files:**
- Create: `server/src/main/kotlin/app/mjolnir/server/RoomRoutes.kt`, `server/src/main/kotlin/app/mjolnir/server/GuestGuard.kt` (route allowlist plugin), `server/src/main/kotlin/app/mjolnir/server/ZipStream.kt`
- Modify:
  - `protocol/.../Models.kt`
  - `Store.kt`: `rooms` table; `files.downloaded_at`
  - `FileRoutes.kt`: marks downloads; zip route
  - `RelayModule.kt`: install the guard and routes; the sweeper ends rooms; the auth `validate` rejects the tokens of ended rooms
  - The `/v1/peers` handler hides `GUEST` peers except from the owner
  - `SpaceRoutes.kt`: refuses guests
- Test: `server/src/test/kotlin/app/mjolnir/server/RoomRoutesTest.kt`, `GuestGuardTest.kt`, `ZipStreamTest.kt`

**Interfaces:**
- Produces (protocol):
  - `DeviceKind.GUEST`
  - `@Serializable data class RoomResponse(val roomId: String, val secret: String, val expiresAtEpochMs: Long)`
  - `@Serializable data class RoomStatus(val roomId: String, val expiresAtEpochMs: Long, val serverNowEpochMs: Long, val guest: Peer?)`
  - `@Serializable data class TicketRequest(val fileId: String? = null, val deliveryId: String? = null, val folder: String? = null)` and `TicketResponse(val ticket: String)`
  - `@Serializable data class GuestJoinRequest(val secret: String)`
  - `@Serializable data class GuestJoinResponse(val deviceId: String, val token: String, val roomId: String, val owner: Peer, val expiresAtEpochMs: Long, val maxFileMb: Int)`
  - `RelayEvent.GuestJoined(val roomId: String, val guest: Peer)`, serial name `guest_joined`
  - `RelayEvent.RoomEnded(val roomId: String)`, serial name `room_ended`
  - `RelayEvent.Downloaded(val deliveryId: String, val fileIds: List<String>)`, serial name `downloaded`
- Produces (endpoints):
  - `POST /v1/rooms` → 201 `RoomResponse` (409 `room_open` if one exists; 403 for guests)
  - `GET /v1/rooms/current` → `RoomStatus` or 204
  - `DELETE /v1/rooms/{id}` → 204 (owner)
  - `POST /v1/guest/join` (unauthenticated, rate-limited 10/min per IP) → 201 `GuestJoinResponse`. Errors: 409 `in_use` once redeemed; 410 `closed` when ended, expired or unknown.
  - `POST /v1/guest/ticket` with `TicketRequest` → `TicketResponse`. The ticket is single-use, valid for 60 s and held hashed in memory. `GET /v1/files/{id}?t={ticket}` and `GET /v1/files/zip?…&t={ticket}` accept a ticket in place of the bearer token, so plain `<a download>` links work in browsers.
  - `GET /v1/files/zip?delivery={id}&folder={top}` → a zip stream (level-0 DEFLATED, so no CRC needs precomputing), `Content-Disposition: attachment; filename="{top}.zip"`. It is available to the delivery's recipient.
- Produces (store):
  - `createRoom(ownerId, secretHash, nowMs, ttlMs): RoomRow`
  - `openRoomOf(ownerId, nowMs): RoomRow?`
  - `roomBySecretHash(hash): RoomRow?`
  - `redeemRoom(roomId, guestId): Boolean` (only when `guest_id IS NULL`)
  - `endRoom(roomId): EndedRoom(ownerId, guestId, fileIdsToDelete)`
  - `expiredRooms(nowMs): List<String>`
  - `markDownloaded(fileId, nowMs)`

  The secret is stored hashed (SHA-256), like tokens. Ending a room:
  - deletes the guest device, its link and its token;
  - deletes files and deliveries addressed to the guest;
  - purges the guest's incomplete uploads;
  - keeps deliveries addressed to the owner.

- [ ] **Step 1: Write the failing tests**
  - `RoomRoutesTest`:
    - `createJoinExchangeEnd`
    - `secondRoomRefused`
    - `joinTwiceIsInUse`
    - `rejoinWithStoredTokenWorks` (the same token still authenticates before expiry)
    - `expiredJoinIsClosed`
    - `tokenRejectedAfterEnd`
    - `wsClosedOnEnd`
    - `expiryDuringUploadKeepsOwnerDeliveries`
    - `sweeperEndsExpiredRoom` (fake clock)
    - `ownerSeesGuestPeerOthersDont`
    - `downloadMarksAndNotifiesOwner` (a full GET emits `Downloaded`; a partial range doesn't)
    - `perRoomCapEnforced`
    - `guestCannotJoinSpaces`
    - `ticketSingleUseAndExpires`
    - `secretNotInLogs` (capture the logger output during join and assert the secret is absent)
  - `GuestGuardTest`: every non-allowlisted route returns 403 `guest`; `sendToNonOwnerIs403`.
  - `ZipStreamTest`:
    - `zipContainsFolderFiles`
    - `zipEntriesUseSafePaths`
    - `zipOnlyForRecipient`
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*RoomRoutes*' --tests '*GuestGuard*' --tests '*ZipStream*'`. Expected: FAIL.
- [ ] **Step 3: Implement** as specified above. `ZipStream` writes a `ZipOutputStream` with `DEFLATED` at level 0 to `respondOutputStream`.
- [ ] **Step 4: Run** `./gradlew :server:test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(relay): guest rooms, guest guard, zip downloads`

### Task G2: Guest web page

**Files:**
- Create: `server/src/main/resources/guest/index.html`, `server/src/main/resources/guest/app.css`, `server/src/main/resources/guest/app.js`, `server/src/main/resources/guest/hammer-white.png` (copy of `desktop/src/main/resources/hammer-white.png`), `server/src/main/resources/guest/fonts/` (the same TTFs and OFL texts as the desktop app), `server/src/test/e2e/guest.e2e.mjs` (Playwright script)
- Modify: `RoomRoutes.kt` or a new `GuestPageRoutes.kt`: `GET /g/{secret}` serves `index.html` for any secret, and `GET /guest/{asset}` serves assets. Both carry the security headers from the Global Constraints.
- Test: `server/src/test/kotlin/app/mjolnir/server/GuestPageTest.kt`, plus the Playwright script

**Interfaces:**
- Consumes: the G1 endpoints and the existing upload, send, inbox and ws APIs.
- Produces: in `app.js`, plain functions `joinRoom()`, `uploadFile(file, relativePath)` (8 MiB chunks, Content-Range, resync on 409 like the Kotlin client), `sendFiles(fileIds)`, `sendLinks(urls)` (the guest can also paste links into the drop area), `listen()` (the WebSocket, with an inbox poll on reconnect), and `download(item)` (fetches a ticket, then clicks an `<a download>` pointing at the ticketed URL).

- [ ] **Step 1: Write the failing tests**
  - `GuestPageTest`:
    - `servesPageForAnySecret`
    - `securityHeadersPresent`
    - `assetsServedWithNosniff`
    - `pageUnder100KbExcludingFonts`
  - `guest.e2e.mjs` (Playwright, against a relay started by the script on a free port, with a fake owner registered over HTTP):
    - `e2eJoinUploadDownload`: the guest uploads a 3 MB file and the owner's inbox has it; the owner sends a file and a folder; the guest downloads both and the zip opens
    - `e2eRejoinResumes`
    - `e2eSecondBrowserInUse`
    - `e2eClosedAfterEnd`
    - `folderButtonHiddenWithoutSupport` (delete `HTMLInputElement.prototype.webkitdirectory` before load)
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*GuestPage*'`, then `./gradlew :server:installDist && node server/src/test/e2e/guest.e2e.mjs`. Expected: FAIL.
- [ ] **Step 3: Implement** the page to the `GuestWeb` mockup, mobile-first and fluid. The token is kept in `localStorage` under `mjolnir.guest.{roomId}`. Error states use the exact copy. Uploads resume after a reload when the same file (same name, size and last-modified) is chosen again, keyed in `localStorage`.
- [ ] **Step 4: Run** both commands. Expected: PASS. Take a Playwright screenshot at 390×844 into the scratchpad and compare it by eye with `GuestWeb`.
- [ ] **Step 5: Commit** `feat(relay): guest web page`

### Task G3: Shared room controller

**Files:**
- Create: `relay-client/src/main/kotlin/app/mjolnir/client/GuestRoom.kt`
- Modify:
  - `RelayApi.kt`, `RelayClient.kt`, `FakeRelayApi.kt`: `openRoom(): RoomResponse`, `currentRoom(): RoomStatus?`, `endRoom(id)`.
  - `Destinations.kt`: a `GUEST` peer forms its own group, key `"guest"`, title `"Guest room"`, shown first while the room is open.
- Test: `relay-client/src/test/kotlin/app/mjolnir/client/GuestRoomTest.kt`

**Interfaces:**
- Produces:
  - `class GuestRoomController(api: () -> RelayApi?, relayUrl: () -> String, scope: CoroutineScope, clock: () -> Long)` with:
    - `val state: StateFlow<GuestRoomUi>`
    - `fun open()`
    - `fun end()`
    - `fun restore()` (calls `currentRoom`)
    - `fun onEvent(e: RelayEvent)`
    - `val exchanged: StateFlow<List<Exchanged>>`
  - `sealed interface GuestRoomUi`:
    - `Closed`
    - `Opening`
    - `Open(roomId, link, expiresAtEpochMs, guest: Peer?)`
    - `Failed(message)`
  - `data class Exchanged(val id: String, val fromGuest: Boolean, val title: String, val detail: String, val progress: Float?, val downloadedByGuest: Boolean, val localPath: String?)`
  - The countdown is local, so a wrong device clock doesn't matter. On open, it ends at `clock() + Limits.GUEST_ROOM_MS` (`3_600_000`, added to `Limits`). On restore, it ends at `clock() + (status.expiresAtEpochMs - status.serverNowEpochMs)`.

- [ ] **Step 1: Write the failing tests** in `GuestRoomTest`:
  - `openBuildsLinkFromRelayUrl` (`https://relay.example.com/g/{secret}`)
  - `guestJoinedSetsGuest`
  - `roomEndedReturnsClosed`
  - `restoresOpenRoomOnStart`
  - `countdownIgnoresClockSkew`
  - `downloadedEventMarksExchanged`
  - `secondOpenWhileOpenIsNoop`
- [ ] **Step 2: Run** `./gradlew :relay-client:test --tests '*GuestRoom*'`. Expected: FAIL.
- [ ] **Step 3: Implement** `GuestRoomController`, the API methods and the destination group above.
- [ ] **Step 4: Run** `./gradlew :relay-client:test`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(client): guest room controller`

### Task G4: Desktop Guest room tab

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/GuestTab.kt`, `desktop/src/main/kotlin/app/mjolnir/desktop/ui/QrCode.kt` (renders `io.nayuki.qrcodegen.QrCode` to an `ImageBitmap`, dark on `#F5F2EC`)
- Modify: `desktop/build.gradle.kts` (adds `io.nayuki:qrcodegen:1.8.0`), `AppController.kt` (owns `GuestRoomController` and routes events; guest deliveries go through `DesktopCatcher` with `fromName = "Guest"`), `MainWindow.kt` (shows the Guest room nav item with its countdown badge), `SendTab.kt` (the guest group card)
- Test: `desktop/src/test/kotlin/app/mjolnir/desktop/ui/GuestTabTest.kt`, `desktop/src/test/kotlin/app/mjolnir/desktop/QrCodeTest.kt`

**Interfaces:**
- Consumes: the G3 controller and `Exchanged`.
- Produces: `@Composable fun GuestTabContent(ui: GuestRoomUi, exchanged: List<Exchanged>, onOpen: () -> Unit, onEnd: () -> Unit, onCopy: () -> Unit, onAddFiles: () -> Unit, onAddFolder: () -> Unit, onDrop: (List<File>) -> Unit)`

- [ ] **Step 1: Write the failing tests**
  - `GuestTabTest`:
    - `closedShowsOpenButton`
    - `openShowsLinkCopyQrCountdown`
    - `exchangedRowsLabelled` (`FROM GUEST` / `YOU SENT` / `downloaded by guest`)
    - `endAsksNothingAndCloses`
  - `QrCodeTest`: `encodesLink` (decode the bitmap's module grid back with the qrcodegen API, or assert the module count for the given version)
- [ ] **Step 2: Run** `./gradlew :desktop:test --tests '*GuestTab*' --tests '*QrCode*'`. Expected: FAIL.
- [ ] **Step 3: Implement** to the `DesktopGuest` mockup. *Copy link* uses the AWT clipboard. Dropped files and folders are sent to the guest peer through the existing send path.
- [ ] **Step 4: Run** `./gradlew :desktop:test :desktop:compileKotlin`. Expected: PASS. Render headless and compare by eye with the mockup.
- [ ] **Step 5: Commit** `feat(desktop): guest room tab`

### Task G5: Android Guest room screen

**Files:**
- Create: `android/src/main/kotlin/app/mjolnir/android/ui/GuestRoomScreen.kt`, `GuestRoomViewModel.kt` (wraps `GuestRoomController`)
- Modify: `DevicesScreen.kt` (adds a *Guest room* entry), `MainActivity.kt` (wiring and restore on start), `ThrowViewModel.kt` (the guest group appears in `Throw to`), `PhoneSession.kt` (forwards room events)
- Test: `android/src/test/kotlin/app/mjolnir/android/ui/GuestRoomViewModelTest.kt`

**Interfaces:**
- Consumes: the G3 controller.
- Produces: the `GuestRoomViewModel.state` and `exchanged` flows.

- [ ] **Step 1: Write the failing tests** in `GuestRoomViewModelTest`:
  - `openShowsLinkAndShare`
  - `restoreOnStart`
  - `endClears`
  - `guestTargetAppearsInThrowTo` (via `ThrowViewModel`)
- [ ] **Step 2: Run** `./gradlew :android:testDebugUnitTest --tests '*GuestRoom*' --tests '*ThrowViewModel*'`. Expected: FAIL.
- [ ] **Step 3: Implement** in the Forge style:
  - The link card with *Copy link* and *Share…* (`Intent.ACTION_SEND` with the link text).
  - The countdown, `Guest connected` and the exchanged list.
  - *End room*.
  - No QR code on the phone.
- [ ] **Step 4: Run** `./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(android): guest room`

### Task G6: Docs

- [ ] **Step 1: Update** the README with a "Guest room" section: what it is, the one-hour limit, that the relay must be reachable over HTTPS from the guest's network, and `MJOLNIR_GUEST_MAX_MB`. Add a "Guest room" section to `docs/manual-verification.md`:
  - Open a room on the PC and scan the QR code with a phone that has no Mjolnir.
  - Send both ways, including a folder; the zip opens.
  - Close and reopen the guest tab; it resumes.
  - A second browser gets "already in use".
  - *End room* makes the guest page show "closed".
  - Repeat from the phone using *Share…*.
- [ ] **Step 2: Run** `./gradlew check`. Expected: PASS.
- [ ] **Step 3: Commit** `docs: guest room`
