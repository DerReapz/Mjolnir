# Accounts (relay) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the relay know people. It needs accounts with a username and a 6-digit PIN, sign-in that adds a device to an account, spaces and chats keyed by person, a one-time move-over of existing devices, and admin PIN resets.

**Architecture:**
- **Person id = account id.** An `accounts` table is added, and every non-guest device gets `account_id`.
- **The first device keeps its id.** When a legacy device creates an account, the new account takes that device's id. All existing space and chat rows keyed by that id are then already correct.
- **Later devices are merged in.** When another legacy device signs in to an account, its rows are rewritten to the account id under the merge rules.
- **Routes act as the person.** Space, chat and send routes act as `principal.accountId`.
- **Fan-out.** Events and pushes go to every device of each account.
- **Deliveries stay device-to-device.**

**Tech Stack:** Kotlin, Ktor 3.3, SQLite (JDBC). For PIN hashing, BouncyCastle `bcprov-jdk18on` Argon2id: pure JVM, already in the Gradle cache through firebase-admin. Add it as a direct dependency through `gradle/libs.versions.toml`.

**Spec:** `docs/superpowers/specs/2026-11-22-accounts-design.md`. Plan 2 (shared client, PC and phone screens, docs) follows this plan. The branch is not releasable until plan 2 lands: apps built from this plan alone get `sign_in_required` at runtime.

## Global Constraints
- **Username:**
  - 3–24 characters from `a–z 0–9 . _ -`.
  - Stored lower-case and compared case-insensitively.
  - Unique and never changeable.
- **Display name:**
  - 1–40 characters after trimming.
  - Defaults to the username.
  - Unique across the relay under `nameKey()`: lower-case, trimmed, inner whitespace collapsed to one space.
  - May not equal any other account's username, and a new username may not equal any existing display name's key.
  - Invisible characters and bidi controls are refused: U+200B–U+200F, U+202A–U+202E, U+2060–U+2069, U+FEFF.
  - A clash gives 409 `name_taken`.
- **PIN:**
  - Exactly 6 ASCII digits.
  - These are refused with 400 `weak_pin`: all the same digit, and every straight run of 6 consecutive digits ascending or descending (`012345` … `456789`, `987654` … `543210`).
- **PIN hashing:** Argon2id with m=19456 KiB, t=2, p=1, 16-byte random salt and 32-byte output.
  - The input is `HMAC-SHA256(pepper, pin)`.
  - Stored as `argon2id$v=19$m=19456,t=2,p=1$<b64 salt>$<b64 hash>`.
  - A PIN is never logged.
- **Pepper:**
  - `MJOLNIR_PIN_PEPPER` (at least 32 characters) if set.
  - Otherwise 32 random bytes in `<data dir>/pin-pepper`, created on first start with owner-only permissions where the OS allows.
  - Losing the pepper invalidates every PIN. The README says so.
- **Lockout:**
  - Per account, 5 wrong PINs lock sign-in for 15 min.
  - Each further lockout doubles the lock: 30 min, 60 min and so on, capped at 24 h.
  - A correct PIN clears the failure count. The doubling level is cleared after 24 h with no lockout.
  - A wrong PIN gives 401 `wrong_username_or_pin`. An unknown username gives the same error and costs the same time.
  - A locked account gives 429 `locked` with `retryAfterMinutes` (rounded up).
  - There is also a per-IP limit of 30 sign-in or create attempts per 15 min. It answers 429 `rate_limited`.
- **Create account:** uses the existing `RegisterLimit` (5 per hour per IP).
- **Protocol header:** every request from the apps carries `Mjolnir-Protocol: 2`.
  - A request without it gets 426 `update_required`.
  - Exempt from the header: guest-authenticated requests, the guest page and guest join routes, `/health`, and the WebSocket upgrade for guest tokens.
- **Devices with no account:** a non-guest device with no account may only call `POST /v1/accounts`, `POST /v1/accounts/sign-in` and `GET /v1/accounts/me`. Everything else gives 403 `sign_in_required`.
- **Must change PIN:** a device whose account has `must_change_pin` may only call `POST /v1/accounts/me/pin`, `GET /v1/accounts/me` and sign-out. Everything else gives 403 `pin_change_required`.
- **Admin reset:**
  - Only the admin of space S, and only for a member of S who is not the admin themself.
  - Rate-limited to 10 per hour per admin.
  - The reset notice is visible in S only, for 7 days.
- **Wire compatibility:** person ids travel in the existing `Peer.deviceId`-shaped fields of space and chat payloads. Those fields now hold an **account id**, with `Peer.name` the display name and the new `Peer.username` set.
- **Copy:** none in this plan. Error codes above are the contract for plan 2's `Messages`.

## Review Focus
1. **Merging two devices that are both in the same space and chats.**
   - No duplicate member rows.
   - The admin and owner roles are kept.
   - Read positions take the maximum.
   - Messages authored by the merged device show as the person.
   - Tested in T4 `mergeKeepsRolesAndMaxReadAndNoDuplicates`.
2. **A legacy device whose id is already an account id.** This is the first device, which created the account. Signing in again with its own token must not merge it into itself. Tested in T4 `firstDeviceSignInIsNoOp`.
3. **Admin reset race.**
   - A device signed out by a reset can't keep using its token.
   - Its open WebSocket is closed.
   - Tested in T5 `resetSignsOutEverywhereAndClosesSockets`.
4. **Guest room traffic with the new gate.**
   - Guest browser requests (no header, guest token) keep working.
   - A guest token can never call account routes.
   - Tested in T2 `guestTrafficUnaffectedByProtocolGate`.
5. **Lockout timing does not reveal whether a username exists.**
   - An unknown username still runs one Argon2 verify against a dummy hash, and counts toward the per-IP limit.
   - Tested in T2 `unknownUsernameSameErrorAndVerifies`, which spies on the hasher call count.

---

### Task T1: Accounts store, names and PIN hashing

**Files:**
- Create: `server/src/main/kotlin/app/mjolnir/server/Pins.kt`, `server/src/main/kotlin/app/mjolnir/server/Names.kt`
- Modify:
  - `server/.../Store.kt`: the `accounts` table, `devices.account_id`, account functions.
  - `server/.../Main.kt`: load the pepper.
  - `server/.../RelayModule.kt`: `RelayDeps.pins`.
  - `server/build.gradle.kts` and `gradle/libs.versions.toml`: bcprov.
  - `protocol/.../Limits.kt`: name and PIN constants.
- Test: `server/src/test/.../PinsTest.kt`, `NamesTest.kt`, `AccountStoreTest.kt`

**Interfaces — Produces:**
- `class PinHasher(pepper: ByteArray) { fun hash(pin: String): String; fun verify(pin: String, stored: String): Boolean; fun verifyDummy() }`
- `fun loadPepper(env: String?, dataDir: File): ByteArray`
- `object Names`:
  - `fun username(raw: String): String?`: normalised, or null if invalid.
  - `fun displayName(raw: String): String?`: trimmed and collapsed, or null if invalid.
  - `fun key(s: String): String`
- `fun weakPin(pin: String): Boolean`
- `fun validPinFormat(pin: String): Boolean`
- Schema: `accounts(id TEXT PK, username TEXT NOT NULL UNIQUE, display_name TEXT NOT NULL, name_key TEXT NOT NULL UNIQUE, pin_hash TEXT NOT NULL, must_change_pin INTEGER NOT NULL DEFAULT 0, pin_reset_by TEXT NULL, pin_reset_at INTEGER NULL, pin_reset_space TEXT NULL, failed_pins INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0, lock_level INTEGER NOT NULL DEFAULT 0, last_lock_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL)`
  - `devices` gains the column `account_id TEXT NULL` and an index on it.
  - Uniqueness of a display name against usernames is enforced in code, inside one transaction.
- `Store`:
  - `fun nameTaken(key: String, exceptAccount: String?): Boolean`. This checks `name_key` and `username` across every account.
  - `fun createAccount(id: String, username: String, displayName: String, pinHash: String, nowMs: Long): AccountRow?`. Null when the name is taken.
  - `fun account(id: String): AccountRow?`
  - `fun accountByUsername(username: String): AccountRow?`
  - `fun setDisplayName(id: String, name: String): Boolean`
  - `fun setPin(id: String, hash: String, mustChange: Boolean)`
  - `fun recordPinFailure(id: String, nowMs: Long): Long?`. Returns the new `locked_until` when this failure locks the account.
  - `fun clearPinFailures(id: String)`
  - `fun devicesOf(accountId: String): List<DeviceRow>`
  - `fun setDeviceAccount(deviceId: String, accountId: String)`
- `data class AccountRow(id, username, displayName, pinHash, mustChangePin, pinResetBy, pinResetAt, pinResetSpace, failedPins, lockedUntil, lockLevel, lastLockAt, createdAt)`

- [ ] **Step 1: Write the failing tests**
  - `PinsTest`:
    - `hashVerifiesAndRejectsWrong`
    - `hashFormatIsArgon2id` (the prefix `argon2id$v=19$m=19456,t=2,p=1$`)
    - `differentPepperFailsVerify`
    - `weakPinsRefused` (`000000`, `999999`, `123456`, `012345`, `456789`, `987654`, `543210` are weak; `120943` and `112233` are not)
    - `pepperFileCreatedOnceAndReused`
    - `envPepperUnder32CharsRejected`
  - `NamesTest`:
    - `usernameRules` (`Fred` → `fred`; `fr` and `a b` → null; 25 characters → null; `fred.s_1-x` is valid)
    - `displayNameTrimsAndCollapses`
    - `bidiAndInvisibleRefused`
    - `keyIsCaseAndSpaceInsensitive`
  - `AccountStoreTest`:
    - `displayNameMayNotEqualAnotherUsername`
    - `usernameMayNotEqualExistingDisplayName`
    - `ownDisplayNameMayEqualOwnUsername`
    - `lockoutAfterFiveDoublesToCap` (with a fake clock: the 5th failure locks for 15 min, then 30, 60 … capped at 1440)
    - `lockLevelClearsAfter24hQuiet`
    - `upgradeFromPreAccountsDbAddsColumns` (open a database created by the current schema, then reopen it)
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*Pins*' --tests '*Names*' --tests '*AccountStore*' --offline`. Expected: FAIL (doesn't compile). Allow one online resolve for bcprov.
- [ ] **Step 3: Implement.** Use `org.bouncycastle.crypto.generators.Argon2BytesGenerator` with `Argon2Parameters.ARGON2_id`. Verify in constant time with `MessageDigest.isEqual`. `verifyDummy()` hashes against a fixed dummy hash so the timing matches. Generate the pepper with `SecureRandom`.
- [ ] **Step 4: Run** `./gradlew :server:test check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(relay): accounts store, names and PIN hashing`

### Task T2: Account routes, sign-in, the protocol gate

**Files:**
- Create: `server/src/main/kotlin/app/mjolnir/server/AccountRoutes.kt`, `server/src/main/kotlin/app/mjolnir/server/ProtocolGate.kt`
- Modify:
  - `RelayModule.kt`: `DevicePrincipal.accountId`, the plugins, `SignInLimit`.
  - `DeviceRoutes.kt`: `POST /v1/devices` and `PATCH /v1/devices/me` now give 426 `update_required`. The push token stays.
  - `protocol/.../Models.kt`: the request and response types below.
  - `server/src/test/.../TestHarness.kt`: `register()` creates an account with a unique username `u<n>`, PIN `135790` and display name equal to the given name. It sends the header on every call.
  - `relay-client/.../RelayClient.kt`: send the `Mjolnir-Protocol: 2` header on every request. This is the only client change, so that existing relay-client integration tests keep passing. Their fixtures call a new harness `createAccount` instead of `register`.
- Test: `server/src/test/.../AccountRoutesTest.kt`, `ProtocolGateTest.kt`

**Interfaces:**
- **Consumes:** everything T1 produces.
- **Produces (protocol, `Models.kt`):**
  - `const val PROTOCOL_HEADER = "Mjolnir-Protocol"` and `const val PROTOCOL_VERSION = 2`, in `Limits.kt`.
  - `@Serializable data class CreateAccountRequest(val username: String, val displayName: String? = null, val pin: String, val deviceName: String, val kind: DeviceKind)`
  - `@Serializable data class SignInRequest(val username: String, val pin: String, val deviceName: String, val kind: DeviceKind)`
  - `@Serializable data class Account(val id: String, val username: String, val displayName: String, val mustChangePin: Boolean = false, val pinResetBy: String? = null)`. `pinResetBy` holds the admin's display name.
  - `@Serializable data class SessionResponse(val deviceId: String, val token: String, val account: Account)`. For a legacy device the token is the device's existing token, so the server echoes `null` and the client keeps its own: `val token: String?`.
  - `@Serializable data class ChangePinRequest(val currentPin: String? = null, val newPin: String)`. `currentPin` is required unless the account has `mustChangePin`.
  - `@Serializable data class RenameAccountRequest(val displayName: String)`
  - `@Serializable data class MyDevice(val deviceId: String, val name: String, val kind: DeviceKind, val lastSeenEpochMs: Long, val current: Boolean)`
  - `@Serializable data class LockedResponse(val error: String = "locked", val retryAfterMinutes: Int)`
  - `Peer` gains `val username: String? = null` and `val accountId: String? = null`.
- **Routes:**
  - `POST /v1/accounts`: create an account.
    - With no bearer: a new device.
    - With the bearer of an account-less legacy device: that device. The account id becomes that device's id (the T4 move-over).
    - Returns 201 `SessionResponse`.
  - `POST /v1/accounts/sign-in`:
    - With no bearer: a new device under the account.
    - With the bearer of a legacy device: that device joins the account (T4 merge).
    - Returns 200 `SessionResponse` with `account.mustChangePin`.
  - `GET /v1/accounts/me` returns `Account`.
  - `PATCH /v1/accounts/me` takes `RenameAccountRequest` and returns `Account`. 409 `name_taken`.
  - `POST /v1/accounts/me/pin` takes `ChangePinRequest`. 204, or 401 `wrong_username_or_pin` or 400 `weak_pin`. It clears `must_change_pin`, `pin_reset_by` and `pin_reset_at`.
  - `GET /v1/accounts/me/devices` returns `List<MyDevice>`.
  - `DELETE /v1/accounts/me/devices/{id}` signs out one of my devices: it deletes the device row and its token, and closes its sockets. Deliveries still waiting for it are dropped with it, as when a device is reset today. It may be the current one.
- **Principal:** `DevicePrincipal(deviceId, name, kind, accountId: String?, guest: Boolean)`.
- **Gate order:**
  1. Protocol header, unless exempt.
  2. Auth.
  3. `sign_in_required` / `pin_change_required` by route allow-list.

- [ ] **Step 1: Write the failing tests**
  - `AccountRoutesTest`:
    - `createThenSignInOnSecondDevice` (two devices, one account; `GET /me/devices` lists both, `current` on the caller)
    - `nameTakenOnCreateAndRename`
    - `weakPinRefused`
    - `wrongPinGenericError`
    - `unknownUsernameSameErrorAndVerifies` (Review Focus 5)
    - `fiveWrongLocksWithRetryAfter`
    - `correctPinClearsFailures`
    - `perIpLimit` (31st attempt → 429 `rate_limited`)
    - `changePinNeedsCurrent`
    - `signOutOtherDeviceRevokesTokenAndClosesSocket`
    - `legacyRegisterGivesUpdateRequired`
  - `ProtocolGateTest`:
    - `missingHeaderGivesUpdateRequired`
    - `accountlessDeviceGetsSignInRequired` (a legacy device row inserted directly into the store, so routes other than the allow-list give 403)
    - `guestTrafficUnaffectedByProtocolGate` (Review Focus 4: a guest joins and uploads with no header, and a guest token on `/v1/accounts/me` gives 403)
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*AccountRoutes*' --tests '*ProtocolGate*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - Sign-in runs Argon2 off the event loop (`Dispatchers.IO`).
  - The per-IP limit uses Ktor `RateLimit` with key `clientIp(call)`.
  - Lockout state lives in the store (T1).
  - The gate is a route-scoped plugin installed in `relayModule` that reads the principal after authentication.
- [ ] **Step 4: Run** `./gradlew check --offline`. Expected: PASS, including every existing server and relay-client test through the updated harness and fixtures.
- [ ] **Step 5: Commit** `feat(relay): accounts and PIN sign-in`

### Task T3: Spaces, chats and sending act as the person

**Files:**
- Modify:
  - `SpaceRoutes.kt`, `ChatRoutes.kt`, `SendRoutes.kt`, `PairingRoutes.kt`, `FileRoutes.kt` (chat-file access).
  - `SessionHub` use sites (new fan-out helper in `RelayModule.kt`), `Push.kt` (`pingDevice` → `pingAccount`).
  - `Store.kt`: membership functions take person ids; there is a new `peersOf(accountId)`; takeover uses account activity.
- Test: update `SpaceRoutesTest`, `ChatRoutesTest`, `ChatFilesTest`, `SendRoutesTest` and `PairingRoutesTest` to the new identity. New: `PeopleRoutesTest.kt`.

**Interfaces:**
- **Consumes:** `DevicePrincipal.accountId` and `Store.devicesOf` (T1/T2).
- **Produces:**
  - `fun RelayDeps.emitToPeople(personIds: Collection<String>, event: RelayEvent)`
  - `fun RelayDeps.pingPeople(personIds: Collection<String>)`
  - Both look up each account's devices. A guest device id passes through as itself.
- **Every `me` in space, chat and room-owner routes is `principal.accountId`.**
  - Store columns keep their names (`space_members.device_id`, `chat_members.device_id`, `spaces.admin_id`, `chats.owner_id`, `chat_messages.author_id`, `chat_invites.*_id`, `chat_receipt_marks.device_id`), but now hold person ids. Each table gets a code comment saying so.
- **Person `Peer` in `Space.members`, `ChatSync.members` and `ChatMessage.author`:** `Peer(deviceId = accountId, name = displayName, kind = kind of that account's most recently seen device, username = username, accountId = accountId)`.
- **`GET /v1/peers`** (device targets for sending) returns:
  1. My other devices: `space = null`, `accountId = me`.
  2. Each person sharing a space with me × each of their devices: `space = SpaceRef`, `accountId`, `username`, and `name = device name`. A device reached through several spaces is listed once, with the first space by name.
  3. Guest-room devices, as today.
- **Send authorisation.** A send to device D is allowed when one of these holds:
  - D's account is mine;
  - D's account shares a space with mine (the space id is given, as today);
  - D is a guest linked to my room.
  - Otherwise: 403 `not_paired`, the existing code.
- **Pairing:** the `PAIRING` kind is removed (`POST /v1/pairings` → 410 `pairing_removed`). `SPACE_INVITE` codes stay. Their join request shows the person (display name and username).
- **Limits:**
  - `maxMembersPerSpace` counts persons.
  - `maxSpacesPerDevice` is renamed `maxSpacesPerAccount` and counts the account's spaces.
  - The env names are unchanged.
- **Admin takeover:** "silent" means none of the admin account's devices has `last_seen` in the past 30 days, and none of them is connected.

- [ ] **Step 1: Write the failing tests** in `PeopleRoutesTest`:
  - `twoDevicesOneAccountAreOneMember` (both devices see the space; the member list has one entry with `username`)
  - `chatMessagesFromEitherDeviceShowThePerson`
  - `eventsFanOutToAllMyDevices`
  - `readOnOneDeviceIsReadForThePerson` (`read_rev` per person)
  - `peersListsMyDevicesAndPeoplesDevicesOnce`
  - `sendToOwnDeviceAllowedWithoutPairing`
  - `sendToStrangerRefused`
  - `pairingCodeGone`
  - `spaceInviteJoinShowsPerson`
  - `takeoverUsesAccountActivity`
  - `memberLimitCountsPeople`

  Update the existing tests whose expectations named device ids for members. Keep their behaviour.
- [ ] **Step 2: Run** `./gradlew :server:test --offline`. Expected: the new tests FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(relay): spaces and chats belong to people`

### Task T4: Move-over and merge of existing devices

**Files:**
- Modify: `Store.kt` (`adoptLegacyDevice`, `mergeLegacyDevice`, link cleanup, pending notices), `AccountRoutes.kt` (wire them into create and sign-in), `protocol/.../Models.kt` (event)
- Test: `server/src/test/.../MoveOverTest.kt`

**Interfaces:**
- **Consumes:** T2 routes and T3 identity.
- **Produces:**
  - `fun adoptLegacyDevice(deviceId: String, account: AccountRow)`
    - Account id = device id. Nothing is rewritten. The device's `account_id` is set.
  - `fun mergeLegacyDevice(deviceId: String, accountId: String): MergeResult`
    - One transaction. Every person-id column equal to `deviceId` is rewritten to `accountId`, under these rules:
      - **space_members:** keep one row per (space, account), with the earliest `joined_at`.
      - **spaces.admin_id:** rewritten. Admin beats member, so if either was admin the account is admin.
      - **chat_members:** one row per (chat, account). `joined_at` is the minimum. `read_rev`, `seen_rev` and their `_at` columns come from the row with the larger rev.
      - **chats.owner_id:** rewritten.
      - **chat_messages.author_id** and **event_json** person ids: rewritten. For events, parse the JSON, replace the ids, then write it back.
      - **Mentions inside body_json:** rewritten the same way.
      - **chat_invites:** rewritten. Delete self-invites and duplicates; keep the most advanced state.
      - **chat_receipt_marks:** rewritten, with duplicates collapsed to the earliest `at` per (chat, person, kind, rev).
      - **pairings:** owner and claimer rewritten.
      - **rooms.owner_id:** rewritten.
    - Then bump the rev of every touched chat, and emit `SpaceChanged` and `ChatChanged` to the people affected.
  - **Links:**
    - A link between two devices of the same account is deleted.
    - A link between devices of **different** accounts is deleted. Each side gets a stored notice.
    - A link to a guest device is kept.
    - The cleanup runs at adopt and merge time, for the device's links whose other side already has an account. A link whose other side has no account yet stays until that side moves over.
  - **Notice:**
    - `RelayEvent.LinkRemoved(name: String)` (serial name `link_removed`), carrying the other side's person display name, or the device name if that side has no account.
    - Stored in a new `notices(device_id, json, created_at)` table and delivered once on the device's next WebSocket connect, then deleted.
- **Edge:** if the signing-in device's id equals the account id (the first device signing in again), the merge is a no-op.

- [ ] **Step 1: Write the failing tests** in `MoveOverTest`:
  - `adoptKeepsEverything` (a legacy device with a space, a chat, admin and owner roles, and messages creates an account; everything is still there and unchanged)
  - `mergeKeepsRolesAndMaxReadAndNoDuplicates` (Review Focus 1)
  - `mergeRewritesMentionsAndEvents`
  - `firstDeviceSignInIsNoOp` (Review Focus 2)
  - `sameAccountLinkDropped`
  - `crossPersonLinkDroppedWithNoticeBothSides`
  - `guestLinkKept`
  - `noticeDeliveredOnceOnConnect`
  - `mergeIntoFullSpaceDoesNotExceedLimit` (merging never adds a member, it only rewrites; the test checks the count)
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*MoveOver*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(relay): existing devices move onto accounts`

### Task T5: Admin PIN reset, notice and docs

**Files:**
- Modify:
  - `SpaceRoutes.kt`: the reset route; `Space` gains notices.
  - `Store.kt`: `resetPin` and `signOutAccount`.
  - `protocol/.../Models.kt`
  - `README.md`: the relay section; see Step 6.
- Test: `server/src/test/.../PinResetTest.kt`

**Interfaces:**
- **Consumes:** T1–T4.
- **Produces:**
  - `POST /v1/spaces/{id}/members/{personId}/reset-pin` with body `ResetPinRequest(val pin: String)`. It returns 204.
  - **Errors:**
    - 403 `not_admin` for a caller who isn't the admin.
    - 404 `not_found` when the person is not a member.
    - 400 `cannot_reset_self`.
    - 400 `weak_pin`.
    - 429 `rate_limited`, more than 10 per hour per admin.
  - **Effect, all in one transaction:**
    - Set the hash and `must_change_pin=1`.
    - Set `pin_reset_by = admin account id`, `pin_reset_at = now` and `pin_reset_space = S`.
    - Revoke every device token of that account: set `token_hash` to a fresh random unusable value and `signed_out=1` (add the `devices.signed_out` column).
    - Then disconnect their sockets.
    - `POST /v1/accounts/sign-in` with the old (revoked) token re-attaches that same device: extend T2's sign-in so a bearer matching a `signed_out` device (look it up by a separately stored `revoked_token_hash`) clears `signed_out` and issues a new token in `SessionResponse.token`.
  - `Account.pinResetBy`: the admin's current display name while `must_change_pin` is set.
  - `Space` gains `val notices: List<SpaceNotice> = emptyList()`, with `@Serializable data class SpaceNotice(val kind: String = "pin_reset", val adminName: String, val personName: String, val atEpochMs: Long)`.
    - Only the space where the reset happened shows it, for 7 days after `pin_reset_at`.
    - It is included for every member of that space.
    - It survives the person changing their PIN. Store resets in a small `pin_resets(space_id, admin_id, person_id, at)` table, so later resets don't overwrite each other.

- [ ] **Step 1: Write the failing tests** in `PinResetTest`:
  - `onlyAdminOfThatSpaceForItsMembers` (an admin of another space gets 403 or 404)
  - `cannotResetSelf`
  - `resetSignsOutEverywhereAndClosesSockets` (Review Focus 3)
  - `signInAfterResetMustChangePinAndShowsWhoReset`
  - `otherRoutesGivePinChangeRequiredUntilChanged`
  - `changeClearsMustChangeButNoticeStays7Days` (a fake clock at 6 d 23 h shows the notice; at 7 d 1 h it is gone)
  - `noticeOnlyInThatSpace`
  - `resetRateLimitedPerAdmin`
- [ ] **Step 2: Run** `./gradlew :server:test --tests '*PinReset*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./gradlew check --offline`. Expected: PASS.
- [ ] **Step 5: Update the README's relay section.**
  - Accounts use a username and a 6-digit PIN, hashed with Argon2id plus a pepper.
  - `MJOLNIR_PIN_PEPPER`, or the `pin-pepper` file in the data folder, and the warning that losing it invalidates every PIN.
  - The lockout rules.
  - A note that the apps from before accounts must be updated together with this relay.
  - Never mention "Synology"; say "your relay host".
- [ ] **Step 6: Commit** `feat(relay): admin PIN reset`
