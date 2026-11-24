# Open channels and who's online: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Space-wide open channels that everyone in a space is automatically in (with per-device mute), plus live online / last-seen presence for people who share a space.

**Architecture:**
- An open channel is an ordinary chat with `kind = OPEN`, so messages, media, receipts and sync are reused unchanged.
- The relay keeps open-channel membership equal to space membership, inside the same store transactions as space join and leave.
- Presence is computed from the existing `SessionHub` connections and `last_seen`. It is pushed as a new `PresenceChanged` event to devices that share a space, with a grace period before reporting offline.
- Mute is a per-member flag on the relay. It silences the phone doorbell, and `ChatController.newMessages` filters on it, which covers both apps' local notifications.

**Tech Stack:** Kotlin, Ktor 3.3 + SQLite (relay), kotlinx.serialization, Compose Desktop, Jetpack Compose (Android), JUnit/kotlin.test.

**Spec:** `docs/superpowers/specs/2026-11-25-rooms-design.md`, sections 1 and 2 plus "Compatibility and updates". Section 3 (voice) is a later plan.

## Global constraints
- **Wire compatibility:** every new field has a default, and every new enum decodes leniently (an unknown value reads as `PRIVATE`). Old apps must keep working against the new relay, and new apps against an old relay.
- **Error codes** are bare strings in `ErrorResponse(error)`. The new code is `not_for_open_channels` (HTTP 409).
- **Channel names:** the same rule as chat names, `validSpaceName` (1–40 characters, trimmed, no control characters). Shown as `# name`; the `#` is not stored.
- **Default channel name:** `general`.
- **Presence grace:** offline is reported 30 s after the last connection closes (`RelayDeps.presenceGraceMs = 30_000`).
- **Copy** (exact, in the shared `Messages`):
  - `Channels`, `Private chats`, `New channel`, `Channel name`, `Mute`, `Unmute`, `Muted`, `Online`
  - `Everyone in {space} can see this channel.`
  - `Delete #{name}? Everyone in {space} loses its messages.`
  - `Open channels can't be joined or left — everyone in the space is in them.` (for `not_for_open_channels`)
  - Last seen: `Last seen just now` (under 1 minute), `Last seen {n}m ago` (under 60 minutes), `Last seen {n}h ago` (under 24 h), `Last seen yesterday` (under 48 h), otherwise `Last seen {d MMM}` (e.g. `Last seen 12 Nov`).
- **Not in scope:** `@everyone` (excluded by the spec), voice, and any change to private-chat behaviour.
- **Read receipts in open channels:** the "Seen by" detail lists names when 5 or fewer people have seen a message, otherwise `Seen by {n}`.
- **The CI-like check** must pass with `MJOLNIR_BUILD=999`: `MJOLNIR_BUILD=999 ./gradlew check :android:lintDebug :android:assembleDebug --offline`.

## Review focus
1. **A device rejoins a space it left.** It is back in every open channel and sees the full history. Leaving and rejoining must not create duplicate rows or skip channels. (Test in T1.)
2. **The channel's creator leaves the space.** The channel survives. Ownership passes as for private chats (the existing `afterMemberGone`), and the admin can still rename or delete it. (Test in T1.)
3. **Muted, but mentioned.** The doorbell still rings and the notification still shows. Mute silences ordinary messages only. (Tests in T2 and T3.)
4. **Quick reconnect** (a phone changing networks within 30 s). No offline event is sent at all. A second open connection keeps the device online when the first closes. (Test in T2.)
5. **Presence privacy.** A device that is only *paired* (not in a shared space) and a device in a different space never receive `PresenceChanged`. (Test in T2.)

---

### Task 1: Relay data — chat kind, open-channel membership, mute, `general`

**Files:**
- Modify: `protocol/src/main/kotlin/app/mjolnir/protocol/Models.kt` (`ChatSummary` :143; add `ChatKind`, `ChatKindSerializer`, `MuteRequest`)
- Modify: `server/src/main/kotlin/app/mjolnir/server/Store.kt` (`ChatRow` :68, migrations :275–290, `createSpace` :496, `addMember` :535, `onLeftSpace` :1044, `createChat` :619, `toChat`; add `setMuted`, `isMuted`, `openChatsIn`, `ensureDefaultChannels`)
- Test: `server/src/test/kotlin/app/mjolnir/server/ChatStoreTest.kt`, `SpacesStoreTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable(with = ChatKindSerializer::class) enum class ChatKind { PRIVATE, OPEN }` (lenient, like `DeviceKindSerializer`: an unknown value reads as `PRIVATE`)
  - `ChatSummary(..., val kind: ChatKind = ChatKind.PRIVATE, val muted: Boolean = false)`, with the new fields added last
  - `@Serializable data class MuteRequest(val muted: Boolean)`
  - `ChatRow(..., val kind: ChatKind = ChatKind.PRIVATE)`
  - `Store.createChat(spaceId, ownerId, name, nowMs, kind: ChatKind = ChatKind.PRIVATE): ChatRow?`
  - `Store.setMuted(chatId, deviceId, muted: Boolean): Boolean` (false when not a member)
  - `Store.isMuted(chatId, deviceId): Boolean`
  - `Store.openChatsIn(spaceId): List<ChatRow>`
  - `Store.addMember(spaceId, deviceId, nowMs): List<String>`: returns the ids of the open chats the device was added to (empty if it was already a member)
  - `Store.ensureDefaultChannels(nowMs): Int`: creates `general` in each space that has no OPEN chat and has never had one; returns how many it created

Steps:
- [ ] **Step 1: Write the failing store tests**
  - `ChatStoreTest`:
    - `openChatIncludesEverySpaceMember`: a space with admin A plus B and C; `createChat(space, B, "games", now, OPEN)` → `chatMembers` = {A, B, C}, `kind == OPEN`.
    - `joiningASpaceJoinsItsOpenChats`: `addMember(space, D)` returns the open chat ids (including `general`); D `isChatMember` of each; D is not added to a PRIVATE chat.
    - `leavingASpaceLeavesItsOpenChats`: `removeMember(space, C, C, now)` → C is not in the open chats; `LeftSpaceResult.changed` contains them.
    - `rejoiningRestoresOpenChats`: remove C, then `addMember` C again → a member again, exactly once (no duplicate rows), and `syncPage(chat, 0)` still returns the earlier messages.
    - `creatorLeavingKeepsTheChannel`: B creates OPEN `games`, then B leaves the space → the chat still exists and `ownerId` is the longest-standing remaining member.
    - `muteIsPerMember`: `setMuted(chat, B, true)` → `isMuted(chat, B)` is true and `isMuted(chat, C)` is false; `setMuted` for a non-member returns false.
  - `SpacesStoreTest`:
    - `newSpaceHasGeneral`: `createSpace(A, "Home", now)` → exactly one OPEN chat named `general`, owned by A, with A as a member.
    - `ensureDefaultChannelsRunsOnce`: insert a space with no chats through SQL (a pre-update space) → `ensureDefaultChannels` returns 1 and the second call returns 0. Deleting that `general` and calling again returns 0: the space keeps having no default channel.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :server:test --tests '*ChatStoreTest*' --tests '*SpacesStoreTest*' --offline`. Expected: they fail to compile, because `ChatKind`, the `kind` parameter, `setMuted` and the rest don't exist yet.
- [ ] **Step 3: Add the protocol types.**
- [ ] **Step 4: Implement the store changes.**
  - Migrations:
    - `addColumnIfMissing("chats","kind","TEXT NOT NULL DEFAULT 'PRIVATE'")`
    - `addColumnIfMissing("chat_members","muted","INTEGER NOT NULL DEFAULT 0")`
    - `addColumnIfMissing("spaces","default_channel_done","INTEGER NOT NULL DEFAULT 0")`
  - `createSpace` makes `general` and sets `default_channel_done = 1`.
  - `ensureDefaultChannels` only touches spaces with `default_channel_done = 0` and then sets the flag, so a deleted `general` is never recreated.
  - `createChat` with OPEN inserts every space member into `chat_members` with no per-member `MemberJoined` events.
  - `addMember` inserts the device into each open chat with `INSERT OR IGNORE` and records `ChatEvent.MemberJoined` for each new row, all in one `transaction`.
  - `onLeftSpace` needs no change, because it already removes the leaver from every chat they are in.
  - `toChat` reads `kind` with `ChatKind.entries.firstOrNull { it.name == v } ?: PRIVATE`.
  - Check every caller of `addMember`; `resolvePairing` :486 is one. They ignore the new return value until T2.
- [ ] **Step 5: Run the T1 tests, then the whole server suite.** Run `./gradlew :server:test --offline`. Expected: PASS. Existing tests that count chats in a new space may need to allow for `general`. Where a test asserted "no chats", update it to filter `kind == PRIVATE`, and say so in the commit message.
- [ ] **Step 6: Commit** `feat(relay): open channels in the store (kind, membership follows the space, mute, #general)`

---

### Task 2: Relay routes — channel rules, mute, doorbell, presence

**Files:**
- Modify: `server/src/main/kotlin/app/mjolnir/server/ChatRoutes.kt` (new channel route next to create :162, invite :198, approve/deny :216, owner :249, member delete :261, admin join :286, `summary` :89, `fanOut` :115; add the mute route)
- Modify: `server/src/main/kotlin/app/mjolnir/server/PairingRoutes.kt` (accept `WON` :116, `webSocket("/v1/ws")` :167)
- Modify: `server/src/main/kotlin/app/mjolnir/server/SpaceRoutes.kt` (`spaceView` :34)
- Modify: `server/src/main/kotlin/app/mjolnir/server/RelayModule.kt` (`RelayDeps` :71: `presenceGraceMs: Long = 30_000`; call `store.ensureDefaultChannels(clock.nowMs())` once in `relayModule`)
- Create: `server/src/main/kotlin/app/mjolnir/server/Presence.kt`
- Modify: `protocol/.../Models.kt` (`Peer` :27, `RelayEvent`)
- Test: `server/src/test/kotlin/app/mjolnir/server/ChatRoutesTest.kt`, `SpaceRoutesTest.kt`, new `PresenceTest.kt`

**Interfaces:**
- Consumes: everything T1 produces.
- Produces:
  - `Peer(..., val online: Boolean = false, val lastSeenMs: Long? = null)`. Only filled in `Space.members`; other `Peer`s keep the defaults.
  - `@SerialName("presence") RelayEvent.PresenceChanged(val deviceId: String, val online: Boolean, val lastSeenMs: Long? = null)`
  - `POST /v1/spaces/{id}/channels` with body `CreateChatRequest(name)`: 201 with `ChatSummary` (`kind == OPEN`). It is a separate route, not a field on the chat create route: an old relay would ignore an unknown field and create a private chat, but it answers this route with 404, which the client maps to `RELAY_OUTDATED`.
  - `PUT /v1/chats/{id}/mute` with body `MuteRequest`: 200 with `ChatSummary`; 404 for a non-member; the space admin who isn't a member gets 409 `not_member`.
  - In `Presence.kt`:
    - `fun RelayDeps.coMembers(deviceId: String): Set<String>`: devices sharing any space, excluding itself.
    - `fun RelayDeps.onConnected(deviceId)` and `fun RelayDeps.onDisconnected(deviceId)`.

Steps:
- [ ] **Step 1: Write the failing route tests.** Use the `spaceWith(admin, vararg joiners)` helper in `ChatRoutesTest` :19.
  - `createOpenChannelAddsEveryone`: POST `/v1/spaces/{id}/channels` with `{name:"games"}` → 201, `kind == OPEN`; every member's chat list contains it; each member gets `ChatChanged`.
  - `privateChatRoutesRefuseOpenChannels`: on an OPEN chat:
    - invite, owner transfer, leave (self delete), admin remove and admin join each → 409 `not_for_open_channels`;
    - approve/deny on an invite row can't arise, so no test for those.
  - `creatorOrAdminRenamesAndDeletesChannel`: a plain member gets 403 `not_owner` on PATCH; the admin gets 200 on PATCH and 204 on DELETE.
  - `muteRoundTrip`: PUT mute `true` → summary `muted == true`, and a fresh list shows `muted`; another member's summary stays `muted == false`.
  - `mutedMemberGetsNoDoorbellUnlessMentioned`: with a `PushSender` fake as in `PushTest`, B mutes, A posts → B is not pinged, C is. A posts mentioning B → B is pinged.
  - `SpaceRoutesTest.joinerGetsOpenChatEvents`: a device that joins through a space invite receives `ChatChanged` for `general` and sees it in `GET /v1/spaces/{id}/chats`.
  - `PresenceTest`, with `presenceGraceMs = 100`:
    - `connectEmitsOnlineToCoMembersOnly`: A and B share a space, C is only paired with A, D is in another space. B connecting gives A `PresenceChanged(B, true)`; C and D get nothing.
    - `disconnectEmitsOfflineAfterGrace`: B closes its socket → A gets `PresenceChanged(B, false, lastSeenMs = clock now)` after the grace period, not before; check with a 50 ms window.
    - `quickReconnectSendsNoOffline`: B closes and reconnects within the grace period → A gets no offline event.
    - `secondConnectionKeepsOnline`: B has two sockets and closes one → no offline event.
    - `spaceMembersCarryPresence`: `GET /v1/spaces` → B `online == true` while connected; after the offline event, `online == false` and `lastSeenMs != null`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :server:test --tests '*ChatRoutesTest*' --tests '*SpaceRoutesTest*' --tests '*PresenceTest*' --offline`. Expected: FAIL.
- [ ] **Step 3: Implement the chat routes.**
  - A helper `suspend fun ApplicationCall.refuseOpen(chat: ChatRow): Boolean` answers 409 `not_for_open_channels` and returns true for OPEN chats. Call it first in the invite, owner, member delete and admin join routes.
  - The channels route has the same checks as chat create (space member, `validSpaceName`). It calls `store.createChat(..., OPEN)` and emits `ChatChanged` to `audience(chat)`, which for OPEN is every member.
  - `summary` adds `chat.kind` and `store.isMuted(chat.id, me)`.
  - `fanOut` pings `members.filter { it != actor && (it in mentioned || !store.isMuted(chat.id, it)) }`.
- [ ] **Step 4: Implement the space join events.** In the pairing accept `WON` branch for `SPACE_INVITE`, use the open chat ids `resolvePairing` now returns:
  - change `resolvePairing`, or read `store.openChatsIn(spaceId)` after the join;
  - call `emitChanged`-style `ChatChanged` to every member of those chats.
  - Leave and remove already emit through `emitLeftSpace`.
- [ ] **Step 5: Implement presence in `Presence.kt`.**
  - **`onConnected`:** run when `hub.add` makes the device connected (it wasn't before). Cancel any pending offline job for the device and emit `PresenceChanged(id, true)` to `coMembers`.
  - **`onDisconnected`:**
    - Force-write `last_seen`: add `Store.setLastSeen(deviceId, nowMs)`, which is unthrottled.
    - If the device has no open sinks left, launch a job on `asyncScope` that does `delay(presenceGraceMs)`. When it fires, if the device is still not `hub.isConnected`, emit `PresenceChanged(id, false, store.lastSeen(id))` to `coMembers`.
    - Keep pending jobs in a `ConcurrentHashMap<String, Job>` held by a small `PresenceTracker` class on `RelayDeps`, field `presence: PresenceTracker = PresenceTracker()`.
  - Wire both into `/v1/ws` around `hub.add` / `hub.remove`. Guests are excluded, because they share no spaces.
  - `spaceView` maps members to `toPeer().copy(online = hub.isConnected(id), lastSeenMs = store.lastSeen(id).takeIf { it > 0 })`.
- [ ] **Step 6: Add the start-up backfill** `store.ensureDefaultChannels(clock.nowMs())` in `relayModule`, plus a test in `SpaceRoutesTest` that a relay started on a store with a pre-existing space lists `general`.
- [ ] **Step 7: Run all server tests.** Run `./gradlew :server:test --offline`. Expected: PASS.
- [ ] **Step 8: Commit** `feat(relay): open channel routes, mute, and live presence`

---

### Task 3: Shared client — API, controller, rules, copy

**Files:**
- Modify: `relay-client/src/main/kotlin/app/mjolnir/client/RelayApi.kt` (add `createChannel` next to `createChat` :87, and `setChatMuted`)
- Modify: `relay-client/src/main/kotlin/app/mjolnir/client/RelayClient.kt` (chat calls :315–345)
- Modify: `relay-client/src/main/kotlin/app/mjolnir/client/ChatController.kt` (`createChat` :444, `onEvent` :420, `newMessages` emission :335–349)
- Modify: `relay-client/src/main/kotlin/app/mjolnir/client/ChatRules.kt`, `Messages.kt` (`chatError` :194)
- Create: `relay-client/src/main/kotlin/app/mjolnir/client/Presence.kt`
- Modify fixtures: `relay-client/src/testFixtures/.../FakeRelayApi.kt`, `FakeChats.kt`
- Test: `ChatControllerTest`, `RelayClientChatTest`, `MessagesTest`, `ChatRulesTest`, new `PresenceTest` (client)

**Interfaces:**
- Consumes: the T1 and T2 wire types.
- Produces:
  - `RelayApi.createChannel(spaceId: String, name: String): ChatSummary` (`POST /v1/spaces/{id}/channels`)
  - `RelayApi.setChatMuted(chatId: String, muted: Boolean): ChatSummary`
  - `ChatController.createChannel(spaceId: String, name: String): ChatSummary`
  - `ChatController.setMuted(chatId: String, muted: Boolean)`: optimistic, and reverts on failure while rethrowing.
  - `class PresenceTracker`:
    - `val presence: StateFlow<Map<String, Presence>>`
    - `fun seed(spaces: List<Space>)`
    - `fun onEvent(e: RelayEvent)`
    - `data class Presence(val online: Boolean, val lastSeenMs: Long?)`
  - Chat rules for an open channel:
    - `fun canInvite(chat: ChatSummary): Boolean = chat.kind == ChatKind.PRIVATE && chat.member`
    - `fun canLeave(chat: ChatSummary): Boolean = chat.kind == ChatKind.PRIVATE && chat.member`
    - `fun canTransferOwner(chat: ChatSummary, selfId: String?): Boolean = chat.kind == ChatKind.PRIVATE && chat.ownerId == selfId`
    - `fun canManage(chat: ChatSummary, selfId: String?, isSpaceAdmin: Boolean): Boolean` (rename and delete)
  - `data class ChatSections(val channels: List<ChatSummary>, val privateChats: List<ChatSummary>)` and `fun chatSections(chats: List<ChatSummary>): ChatSections`. Channels are sorted by name, case-insensitively. Private chats keep their current order.
  - `Messages`:
    - the constants from Global constraints;
    - `fun lastSeen(nowMs: Long, lastSeenMs: Long?, zone: ZoneId = ZoneId.systemDefault()): String`, which returns `""` for null;
    - `fun channelHint(space: String)`, `fun deleteChannelConfirm(name: String, space: String)`;
    - `fun seenBySummary(names: List<String>): String`: names joined when there are 5 or fewer, otherwise `Seen by {n}`;
    - `chatError` maps `not_for_open_channels` to the copy above.
  - **The apps read presence through `ChatController.presence` only.**
    - The controller owns a `PresenceTracker`, seeded whenever spaces are read: add `fun seedPresence(spaces: List<Space>)`, which the apps call where they already load spaces.
    - The tracker is fed from `onEvent`.

Steps:
- [ ] **Step 1: Write the failing client tests.**
  - `RelayClientChatTest`:
    - `createsAnOpenChannel`: against `clientTest`, `createChannel(space, "games").kind == OPEN`.
    - `createChannelOnAnOldRelayIsRelayOutdated`: against a fake server without the route (a bare 404, the `fakeWsTest` style), `assertKind(RELAY_OUTDATED) { createChannel(...) }`.
    - `mutesAChat`: `setChatMuted(id, true).muted`.
    - `refusesInviteToChannel`: `assertKind(CONFLICT or the existing kind for 409)` with the message `not_for_open_channels`. Check how 409 maps in `errorFor` :206 and assert that kind.
  - `ChatControllerTest`:
    - `mutedChatDoesNotAnnounceNewMessages`: a muted chat with an incoming message → nothing on `newMessages`; with a mention of me → announced.
    - `setMutedIsOptimisticAndReverts`: the fake fails → `muted` reverts and the call throws.
    - `presenceEventsUpdateTheMap`: seed from a `Space` with B online=false, then `onEvent(PresenceChanged(B,true))` → `presence.value[B]!!.online`.
  - `ChatRulesTest`:
    - OPEN: `canInvite`, `canLeave` and `canTransferOwner` are false.
    - `canManage` is true for the creator and the space admin, false for a plain member.
    - `chatSections` splits and sorts.
  - `MessagesTest`, with a fixed `nowMs` and `ZoneOffset.UTC`:
    - `lastSeen`: 30 s → `Last seen just now`; 5 min → `Last seen 5m ago`; 2 h → `Last seen 2h ago`; 30 h → `Last seen yesterday`; 2026-11-12 → `Last seen 12 Nov`.
    - `seenBySummary`: 5 names listed, 6 → `Seen by 6`.
    - `chatError(not_for_open_channels)`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :relay-client:test --offline`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `RelayClient.setChatMuted` → `PUT /v1/chats/{id}/mute`.
  - Update `FakeChats` / `FakeRelayApi` so the fakes mirror the relay:
    - OPEN membership follows the space;
    - the 409 refusals;
    - mute.
  - In `ChatController`'s new-message emission (:335), skip when the chat summary is `muted && !mentionedMe`.
- [ ] **Step 4: Run all relay-client tests, then `check`.** Run `MJOLNIR_BUILD=999 ./gradlew :relay-client:test :server:test --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(client): open channels, mute and presence in the shared client`

---

### Task 4: PC app — channel sections, new channel, mute, presence dots

**Files:**
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/ChatsTab.kt` (`ChatList` :263, `ChatListItem` :290, `ConversationHeader` :484, `NewChatDialog` :831, `ChatsUi`/`ChatsActions` :129/:169)
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/ChatSettingsDialog.kt` (`Main` :114: the private-chat copy at :120; invite and leave entries)
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/ui/SpacesSection.kt` (`SpaceCard` member loop :124)
- Modify: `desktop/src/main/kotlin/app/mjolnir/desktop/SpacesController.kt` / `AppController.kt`: call `chatController.seedPresence(spaces)` wherever spaces are refreshed
- Test: `desktop/src/test/.../ChatsTabTest.kt`, `SpacesSectionTest.kt`

**Interfaces:**
- Consumes: `chatSections`, `canInvite`, `canLeave`, `canTransferOwner`, `canManage`, `ChatController.createChannel` / `setMuted` / `presence`, and the `Messages` copy (all T3).

Steps:
- [ ] **Step 1: Write the failing UI tests**, using the Compose test rule as the existing tests do.
  - `ChatsTabTest`:
    - `listShowsChannelsAbovePrivateChats`: under the space header, the `Channels` label and then `# general` appear before `Private chats`.
    - `mutedChatShowsMutedLabel`: a muted channel row shows `Muted` and a grey unread count.
    - `newChannelCreatesWithNameOnly`: the dialog with the `Channel` choice, after entering `games` → `createChannel(space, "games")` is called.
    - `channelSettingsHideInviteAndLeave`: for an OPEN chat, there is no `Invite` and no `Leave`, the hint `Everyone in Home can see this channel.` shows, and `Mute` is present.
  - `SpacesSectionTest.memberShowsPresence`: an online member shows `Online`; an offline one shows `Last seen 5m ago`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :desktop:test --offline`. Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `ChatList` renders each space group as two subsections from `chatSections`; a subsection with no rows hides its label.
  - Channel rows are titled `# {name}`.
  - The member list in a channel's settings shows the same presence dot and text.
  - `NewChatDialog` gets a two-option segmented control, `Chat` / `Channel`. `Channel` uses the label `Channel name`.
  - The ⋯ menu and the settings dialog show `Mute`/`Unmute` for members. Delete uses `deleteChannelConfirm` for channels.
  - The presence dot is 8 dp: green `#3FB950` for online, grey `#6E7681` otherwise. Text comes from `Messages.ONLINE` / `Messages.lastSeen`.
  - A ticker re-renders the last-seen text each minute: `LaunchedEffect` with `delay(60_000)`.
  - The "Seen by" detail uses `seenBySummary` for channels.
- [ ] **Step 4: Run the desktop tests.** Run `./gradlew :desktop:test --offline`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(desktop): channels, mute and online status`

---

### Task 5: Phone app, docs, full check

**Files:**
- Modify: `android/src/main/kotlin/app/mjolnir/android/ui/ChatViewModel.kt` (`ChatRow`/`ChatGroup` :62/:65, grouping :188)
- Modify: `android/src/main/kotlin/app/mjolnir/android/ui/ChatListScreen.kt` (`ChatListContent` :92, `ChatRowItem` :138, `NewChatDialog` :222)
- Modify: `android/src/main/kotlin/app/mjolnir/android/ui/ChatSettingsScreen.kt` (copy at :84, invite and leave entries)
- Modify: `android/src/main/kotlin/app/mjolnir/android/ui/SpacesScreen.kt` (`SpaceBlock` members :135), `SpacesViewModel.kt` (seed presence)
- Modify: `README.md` (features list), `docs/manual-verification.md`
- Test: `android/src/test/.../ChatViewModelTest.kt`, `SpacesViewModelTest.kt`

**Interfaces:**
- Consumes: the same T3 pieces as T4.
- Produces: `ChatGroup(..., val channels: List<ChatRow>, val privateChats: List<ChatRow>)`, which replaces the single list.

Steps:
- [ ] **Step 1: Write the failing tests.**
  - `ChatViewModelTest`:
    - `groupsSplitChannelsAndPrivate`: the summaries give `channels == [general, games]` and `privateChats == [planning]`.
    - `createChannelCallsController`.
    - `muteToggles`.
  - `SpacesViewModelTest.membersCarryPresence`: after a `PresenceChanged(B,true)`, the UI state shows B online.
- [ ] **Step 2: Run the tests and confirm they fail.** Run `./gradlew :android:testDebugUnitTest --offline`. Expected: FAIL.
- [ ] **Step 3: Implement the same UI rules as T4**, in the phone's existing styles:
  - the `Channels` and `Private chats` section headers;
  - `# name` rows;
  - the new-chat dialog's `Chat` / `Channel` choice;
  - in settings, the mute toggle, plus the open-channel hint, with invite and leave hidden;
  - presence dots and text in the space member list and in a channel's member list.
- [ ] **Step 4: Update the docs.**
  - README features: one line each for open channels, mute and online status.
  - `docs/manual-verification.md`, new section "Channels and online status":
    - a new space has `#general`, and everyone in the space sees it and can post;
    - a member who joins later sees `#general` with its history;
    - Invite and Leave are absent in a channel;
    - muting stops notifications, but an @mention still notifies;
    - a closed app's dot goes grey within about 30 s and shows `Last seen just now`;
    - an old app still shows channels as normal chats.
- [ ] **Step 5: Run the full check.** Run `MJOLNIR_BUILD=999 ./gradlew check :android:lintDebug :android:assembleDebug --offline`. Expected: BUILD SUCCESSFUL.
- [ ] **Step 6: Commit** `feat(android): channels, mute and online status; docs`
