# Voice rooms: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task by task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Small voice rooms inside spaces: up to 8 people, audio only, push-to-talk or open mic, on the PC and the phone.

**Architecture:**
- The relay keeps voice rooms in SQLite and who is in each room in memory, in a `VoiceHub`.
- Audio travels over a dedicated WebSocket per active room, `/v1/voice/{roomId}`. Connecting joins the room and closing leaves it.
- The relay forwards Opus frames to the room's other participants and stores nothing. It drops frames for a slow receiver instead of queueing them.
- A shared engine in relay-client does the Opus encoding (Concentus, pure Java), the jitter buffering, mixing and speaking detection.
- The PC and the phone plug in their own microphone and speaker, and their own UI.

**Tech Stack:**
- Kotlin, Ktor 3.3 (server and client WebSockets), SQLite
- `io.github.jaredmdobson:concentus:1.0.2`
- `javax.sound.sampled` (PC); Android `AudioRecord` / `AudioTrack` with a foreground service (phone)
- Compose Desktop / Jetpack Compose

**Spec:** `docs/superpowers/specs/2026-11-25-rooms-design.md`, section 3 plus "Compatibility and updates".

## Global constraints
- **Ruling V1:** audio uses its own WebSocket per active room (`/v1/voice/{roomId}`), not the event socket. It is the same host and reverse proxy, so the relay host needs no changes.
- **Limits:**
  - `MJOLNIR_MAX_VOICE`: default 8, max 16 people per room.
  - A device is in at most one room. Joining another room leaves the first.
  - The relay removes a device 10 s after its voice socket drops, unless it reconnects (`RelayDeps.voiceGraceMs = 10_000`).
  - The app retries for 30 s before giving up.
- **Audio:**
  - Opus, 48 kHz mono, 20 ms frames (960 samples), about 24–32 kbit/s, in-band FEC on.
  - Jitter buffer: 60 ms target, 200 ms max.
  - Speaking: the level stays above the threshold for at least 100 ms. On open mic, silent frames are not sent.
- **Defaults:** every new space gets a voice room named `Lounge`. Existing spaces get one exactly once, behind a flag like `default_channel_done`.
- **Who manages rooms:**
  - Any space member creates a room.
  - The room's creator or the space admin renames or deletes it.
  - Names use `validSpaceName`.
- **Privacy:**
  - Only space members can join.
  - The relay sets the speaker from the connection, so a client can never claim to be someone else.
  - Room participant lists go to space members only.
  - Audio is never stored or logged.
- **Wire compatibility:**
  - New fields have defaults, and old apps ignore the new events.
  - A new app on an old relay hides Voice: the room list call answers 404 → `RELAY_OUTDATED`, and the app shows nothing for voice.
- **Talking modes:**
  - Phone: open mic by default.
  - PC: push-to-talk by default (hold `` ` `` while the window has focus, or hold the on-screen button), switchable to open mic.
- **Copy** (exact, in the shared `Messages`):
  - `Voice`, `Join voice`, `Leave`, `Mute`, `Unmute`, `Push to talk`, `Open mic`, ``Hold ` to talk``, `New voice room`, `Voice room name`
  - `In voice: {room}`, `{room} is full ({n}/{max})`, `Reconnecting…`, `You left {room} (connection lost)`
  - `Use headphones to avoid echo`, `Mjolnir needs the microphone for voice rooms.`, `Open settings`
  - `Mjolnir voice — {room}` (the phone notification title)
  - `Delete {room}? Anyone in it is disconnected.`
- **The CI-like check** must pass: `MJOLNIR_BUILD=999 ./gradlew check :android:lintDebug :android:assembleDebug --offline`.
  - The first build after adding Concentus must run once without `--offline`, so Gradle can fetch it.

## Review focus
1. **A participant's network drops and comes back within 10 s.** They are still listed in the room and nobody sees them leave. After 10 s they are removed and everyone's list updates. (T2)
2. **A device is removed from the space while it is talking.** Its voice socket is closed at once and it disappears from the room. (T2)
3. **One receiver on a bad connection.** The other participants' audio keeps flowing on time; the relay never buffers without limit. (T2)
4. **Frames arrive late, out of order or not at all.** Playback stays continuous, using concealment, and never piles up delay beyond 200 ms. (T3)
5. **The phone's screen turns off, or the user switches app, mid-call.** Voice continues through the foreground service, and Leave in the notification ends it cleanly. (T5)

---

### Task 1: Relay voice rooms — data, routes, events

**Files:**
- Modify: `protocol/src/main/kotlin/app/mjolnir/protocol/Models.kt`
- Modify: `server/src/main/kotlin/app/mjolnir/server/Store.kt` (schema, migrations, `createSpace`, delete-space cleanup)
- Create: `server/src/main/kotlin/app/mjolnir/server/VoiceHub.kt` (in-memory participants), `server/src/main/kotlin/app/mjolnir/server/VoiceRoutes.kt`
- Modify: `server/src/main/kotlin/app/mjolnir/server/RelayModule.kt` (`RelayLimits`, `RelayDeps`, routing, backfill), `Main.kt` (`MJOLNIR_MAX_VOICE`)
- Modify: `server/src/main/kotlin/app/mjolnir/server/SpaceRoutes.kt` (leave/remove kicks the device from that space's rooms)
- Test: new `server/src/test/kotlin/app/mjolnir/server/VoiceRoomsTest.kt`

**Interfaces (produces):**
- Wire types:
  - `@Serializable data class VoiceRoom(val id: String, val spaceId: String, val name: String, val creatorId: String, val participants: List<Peer> = emptyList())`
  - `@Serializable data class CreateVoiceRoomRequest(val name: String)`
  - `RelayEvent.VoiceRoomChanged(val room: VoiceRoom)` with `@SerialName("voice_room")`
  - `RelayEvent.VoiceRoomDeleted(val spaceId: String, val roomId: String)` with `@SerialName("voice_room_deleted")`
  - `PresenceChanged` gains `val voiceRoomId: String? = null`, added last
- Store:
  - `data class VoiceRoomRow(id, spaceId, name, creatorId, createdAtMs)`
  - `createVoiceRoom(spaceId, creatorId, name, nowMs): VoiceRoomRow?`
  - `voiceRoom(id)`, `voiceRoomsIn(spaceId)`, `renameVoiceRoom(id, name): Boolean`, `deleteVoiceRoom(id): Boolean`
  - `ensureDefaultVoiceRooms(nowMs): Int`
  - `createSpace` adds `Lounge`, owned by the admin
- `class VoiceHub`:
  - `fun participants(roomId): List<String>`
  - `fun roomOf(deviceId): String?`
  - `fun join(roomId, deviceId, max: Int): JoinResult` (`JOINED`, `FULL`, or `MOVED(previousRoomId)`)
  - `fun leave(roomId, deviceId): Boolean`
  - All of these are thread-safe. Task 2 adds the socket bookkeeping.
- Routes:
  - `GET /v1/spaces/{id}/voice-rooms` → `List<VoiceRoom>` (space members only, otherwise 404)
  - `POST /v1/spaces/{id}/voice-rooms` → 201 `VoiceRoom`
  - `PATCH /v1/voice-rooms/{id}` → `VoiceRoom` (creator or admin, otherwise 403 `not_owner`)
  - `DELETE /v1/voice-rooms/{id}` → 204. Delete also closes the participants' voice sockets through a `VoiceHub` hook that Task 2 fills.
  - Every change emits `VoiceRoomChanged` or `VoiceRoomDeleted` to all space members.
- `RelayLimits.maxVoicePerRoom: Int = 8`, from `MJOLNIR_MAX_VOICE` and clamped to 1..16.

Steps:
- [ ] **Step 1: Write the failing tests** in `VoiceRoomsTest`:
  - `newSpaceHasLounge`: the room list after creating a space is `[Lounge]`.
  - `backfillAddsLoungeOnce`: `ensureDefaultVoiceRooms` returns 1 for a pre-update space and 0 the second time. Deleting `Lounge` and calling again also returns 0.
  - `createRenameDeleteRules`: a member creates a room and every member gets `VoiceRoomChanged`. A plain member's PATCH on someone else's room → 403. The admin's DELETE → 204, and members get `VoiceRoomDeleted`.
  - `nonMemberCannotSeeRooms`: GET from a device outside the space → 404.
  - `deletingASpaceDeletesItsRooms`.
  - `VoiceHub` unit tests:
    - joins beyond `max` → `FULL`;
    - joining a second room → `MOVED(first)`, and the device is no longer in the first;
    - `leave` is idempotent.
- [ ] **Step 2:** Run `./gradlew :server:test --tests '*VoiceRooms*' --offline` and confirm the tests fail.
- [ ] **Step 3: Implement it.**
  - Migrations: create the `voice_rooms` table and add `spaces.default_voice_done`.
  - Call the backfill in `relayModule` next to `ensureDefaultChannels`.
  - On space leave or remove, the routes call a `VoiceHub` removal for that space's rooms and emit `VoiceRoomChanged`.
- [ ] **Step 4:** Run `MJOLNIR_BUILD=999 ./gradlew :server:test --offline` and confirm it passes. Older tests that count rooms or events in a new space may need to account for `Lounge`: they never listen for voice events, so check first.
- [ ] **Step 5:** Commit `feat(relay): voice rooms (Lounge, routes, events)`.

---

### Task 2: Relay voice socket — join, forward, grace, kick

**Files:**
- Modify: `server/src/main/kotlin/app/mjolnir/server/VoiceRoutes.kt`, `VoiceHub.kt`, `Presence.kt` (`voiceRoomId` in presence events and snapshots), `RelayModule.kt` (`voiceGraceMs`)
- Modify: `protocol/.../Models.kt`: `@Serializable data class VoiceRoster(val type: String = "roster", val participants: List<String>)`. These are device ids, and the index in the list is the speaker index.
- Test: new `server/src/test/kotlin/app/mjolnir/server/VoiceSocketTest.kt`

**Interfaces (produces):**
- `webSocket("/v1/voice/{roomId}")` on the `device` auth. Guests are refused.
- **Close reasons** (close code 4000 + text): `not_found` (no such room, or not a space member), `room_full`, `replaced` (the same device joined from another socket, or moved rooms), `room_deleted`, `removed` (left or was removed from the space).
- **On open:**
  - `VoiceHub.join`. On `MOVED`, close the previous socket with `replaced`.
  - Send a text frame `VoiceRoster` to everyone in the room, and on every later change.
  - Emit `VoiceRoomChanged` to space members and a `PresenceChanged(..., voiceRoomId)` to co-members.
- **Binary frames:**
  - Client → relay: `[seq: u16 BE][opus payload]`, with a payload of 1..1275 bytes. Anything else is dropped.
  - Relay → each other participant: `[speakerIndex: u8][seq: u16 BE][opus payload]`, where `speakerIndex` is the sender's index in the current roster.
- **Per receiver:** a `Channel<ByteArray>(capacity = 16)` with `trySend`. A full channel drops the frame for that receiver only. One sender coroutine per receiver writes to the socket.
- **On close:**
  - The participant stays listed for `voiceGraceMs`.
  - A reconnect by the same device to the same room within the grace takes over its slot silently: no leave or join events, and a roster resent only if it changed.
  - After the grace, the device is removed, with roster, event and presence updates.
  - Run the grace timer on `deps.asyncScope`, locked per device the same way as `PresenceWatch`.
- **Kicks:** space leave/remove and room delete close the sockets immediately (`removed` / `room_deleted`) and remove the device without a grace.

Steps:
- [ ] **Step 1: Write the failing tests** in `VoiceSocketTest`. Use a short grace, about 500 ms, with negative windows well inside it.
  - `framesReachOnlyTheOtherParticipants`: A, B and C are in Lounge and D is in the space but not the room. A sends `[0,1,0xAA]`. B and C receive `[idxA,0,1,0xAA]`; A and D receive nothing.
  - `speakerIsSetByTheRelay`: whatever A sends, receivers see A's roster index.
  - `ninthJoinIsRefused`: with `maxVoicePerRoom = 2`, the third join closes with `room_full`.
  - `joiningAnotherRoomReplacesTheFirst`.
  - `slowReceiverDoesNotDelayOthers`: B never reads. A sends 200 frames, C receives all 200 in order, and the relay's memory stays bounded (B's channel is capped).
  - `reconnectWithinGraceIsSilent` and `droppedParticipantLeavesAfterGrace`.
  - `spaceRemovalKicksImmediately`: the admin removes B from the space and B's socket closes with `removed`.
  - `nonMemberIsRefused` and `guestIsRefused`.
  - `presenceCarriesVoiceRoom`.
- [ ] **Step 2:** Run the tests and confirm they fail.
- [ ] **Step 3:** Implement the socket and the hub bookkeeping.
- [ ] **Step 4:** Run `MJOLNIR_BUILD=999 ./gradlew :server:test --offline` and confirm it passes. Then run `VoiceSocketTest` three times with `--rerun-tasks` and confirm it is stable.
- [ ] **Step 5:** Commit `feat(relay): voice socket with forwarding, grace and kicks`.

---

### Task 3: Shared voice engine and controller (relay-client)

**Files:**
- Modify: `relay-client/build.gradle.kts`: add `implementation("io.github.jaredmdobson:concentus:1.0.2")`. Run Gradle online once.
- Create in `relay-client/src/main/kotlin/app/mjolnir/client/voice/`:
  - `OpusCodec.kt`, `JitterBuffer.kt`, `Mixer.kt`, `SpeakingDetector.kt`
  - `AudioIo.kt`: the `AudioInput` and `AudioOutput` interfaces
  - `VoiceSession.kt`, `VoiceController.kt`
- Modify: `RelayApi.kt` / `RelayClient.kt` (the room routes, plus `voiceSocket(roomId)`), `Messages.kt` (copy), fixtures `FakeRelayApi.kt`
- Test: in `relay-client/src/test/kotlin/app/mjolnir/client/voice/`: `OpusCodecTest`, `JitterBufferTest`, `MixerTest`, `SpeakingDetectorTest`, `VoiceSessionTest` (against the real relay through `clientTest`), `VoiceControllerTest`

**Interfaces (produces):**
- `class OpusCodec`:
  - `fun encode(pcm: ShortArray): ByteArray`, taking 960 samples
  - `fun decode(packet: ByteArray?): ShortArray`, where null means a lost frame: concealment, or FEC when the next packet is known
- `class JitterBuffer(targetMs = 60, maxMs = 200)`:
  - `fun push(seq: Int, packet: ByteArray)`
  - `fun pop(): ByteArray?`, called every 20 ms; it returns null for a gap
  - It handles u16 wraparound, drops packets older than the play point, and skips ahead when over `maxMs`.
- `object Mixer { fun mix(frames: List<ShortArray>): ShortArray }`: sums with a soft clip.
- `class SpeakingDetector(thresholdDb = -45.0, holdMs = 100) { fun update(pcm: ShortArray): Boolean }`
- `interface AudioInput { fun start(); fun read(into: ShortArray): Int; fun stop() }`, which blocks per 20 ms frame
- `interface AudioOutput { fun start(); fun write(pcm: ShortArray); fun stop() }`
- `RelayApi`:
  - `voiceRooms(spaceId): List<VoiceRoom>`
  - `createVoiceRoom(spaceId, name): VoiceRoom`
  - `renameVoiceRoom(id, name): VoiceRoom`
  - `deleteVoiceRoom(id)`
- `RelayClient.openVoice(roomId, onRoster, onFrame): VoiceLink`, where `VoiceLink` has `send(seq, opus)`, `close()` and `val closeReason: Deferred<String?>`. It reuses the same HTTP client, auth header and app-version header as `events()`.
- `class VoiceSession(link factory, input: AudioInput, output: AudioOutput, scope)`:
  - `val state: StateFlow<VoiceState>`, where `VoiceState` is `Connecting | Live | Reconnecting | Ended(reason: String?)`
  - `val speaking: StateFlow<Set<String>>` of device ids
  - `fun setMuted(Boolean)`, `fun setTalking(Boolean)` (the push-to-talk gate), `var openMic: Boolean`
  - `fun leave()`
  - **Loops:**
    - capture: read, detect, gate, encode, send;
    - receive: push into the speaker's jitter buffer;
    - playout every 20 ms: pop each speaker, decode, mix, write.
  - **Reconnect:** on an unexpected close, reconnect with backoff for up to 30 s, then `Ended("connection_lost")`. Close reasons `room_full`, `removed`, `room_deleted`, `replaced` and `not_found` end at once with that reason.
- `class VoiceController(api, scope, audioFactory)`:
  - `val rooms: StateFlow<Map<String, List<VoiceRoom>>>` keyed by space
  - `val active: StateFlow<ActiveVoice?>`, where `ActiveVoice(roomId, roomName, state, speaking, muted, talking, openMic)`
  - `fun onEvent(e: RelayEvent)` for `VoiceRoomChanged`, `VoiceRoomDeleted` and `Connected` (re-fetch)
  - `suspend fun refresh(spaces: List<Space>)`
  - `fun join(room)`, `fun leave()`, `setMuted`, `setTalking`, `setOpenMic`
  - `createRoom`, `renameRoom`, `deleteRoom`
  - On an old relay, `RELAY_OUTDATED` from `voiceRooms` makes `rooms` empty and sets `val supported: StateFlow<Boolean>` to false.
- `Messages`:
  - every copy string in Global constraints;
  - `fun voiceEnded(reason: String?, room: String): String?`, which maps `connection_lost` → `You left {room} (connection lost)`, `room_full` → `{room} is full ({n}/{max})` (with n and max passed in), and the kick reasons → a short sentence;
  - `fun inVoice(room)`.

Steps:
- [ ] **Step 1: Write the failing tests.**
  - `OpusCodecTest`: a 1 kHz sine round trip keeps its energy within ±3 dB and its packets are under 200 bytes; `decode(null)` returns 960 samples.
  - `JitterBufferTest`:
    - in order;
    - reordering within the target;
    - a gap gives one null;
    - a late packet is dropped;
    - an overflow beyond 200 ms skips ahead;
    - u16 wraparound (65535 → 0).
  - `MixerTest`: two loud signals don't wrap around; silence plus a signal gives the signal.
  - `SpeakingDetectorTest`: silence → false; a tone longer than 100 ms → true; a 40 ms blip → false.
  - `VoiceSessionTest`, with fake I/O over the real relay:
    - A's tone reaches B's output;
    - mute stops it;
    - push-to-talk off stops it;
    - B's `speaking` contains A while A talks;
    - the relay closing with `room_full` ends with that reason;
    - a dropped link reconnects within 30 s (inject a short limit for the test).
  - `VoiceControllerTest`:
    - rooms load per space;
    - events update the lists;
    - join/leave changes `active`;
    - an old relay gives `supported == false`.
- [ ] **Step 2:** Run the tests and confirm they fail.
- [ ] **Step 3:** Implement. Inject the 20 ms timing through a clock or ticker interface so the tests don't depend on real time where possible.
- [ ] **Step 4:** Run `MJOLNIR_BUILD=999 ./gradlew :relay-client:test :server:test --offline` and confirm it passes. Run `VoiceSessionTest` three times and confirm it is stable.
- [ ] **Step 5:** Commit `feat(client): voice engine (Opus, jitter, mixing) and voice controller`.

---

### Task 4: PC app — audio, voice list, voice panel, push-to-talk

**Files:**
- Create: `desktop/src/main/kotlin/app/mjolnir/desktop/voice/DesktopAudio.kt` (a `javax.sound.sampled` `TargetDataLine` / `SourceDataLine` at 48 kHz, 16-bit, mono, on the default devices)
- Modify: `desktop/.../AppController.kt` (create the `VoiceController`, route events, refresh on spaces load and reconnect), `CatchService.kt` (route voice events)
- Modify: `desktop/.../ui/ChatsTab.kt` (a Voice subsection per space and a voice panel), plus the tray menu
- Test: `desktop/src/test/.../VoiceUiTest.kt`

**Interfaces:**
- Consumes `VoiceController`, `ActiveVoice` and the `Messages` voice copy from Task 3.
- Produces nothing for later tasks.

Steps:
- [ ] **Step 1: Write the failing UI tests** with a fake `VoiceController` state.
  - Each space shows `Voice` with `🔊 Lounge`, followed by the participant names (`Lounge — Sam, Pixel 8`).
  - Clicking a room calls `join`.
  - The panel shows the participants, with a ring when speaking and a muted icon. Mute and Leave call the controller.
  - The `Push to talk` / `Open mic` toggle calls `setOpenMic`; on open mic, `Use headphones to avoid echo` shows.
  - Holding the on-screen button calls `setTalking(true)`, and releasing calls `setTalking(false)`.
  - The `` ` `` key down/up does the same while the window has focus.
  - `Reconnecting…` shows for that state, and the ended message shows on end.
  - `New voice room` creates one.
  - The Voice section is hidden when `supported == false`.
- [ ] **Step 2:** Run the tests and confirm they fail.
- [ ] **Step 3: Implement it.**
  - Push-to-talk is the PC default.
  - The tray tooltip or menu shows `In voice: {room}` with a Leave item while in voice.
  - The PC is in at most one room; joining another room asks the controller, which leaves the first.
  - If the microphone can't be opened, end the session with an inline error.
- [ ] **Step 4:** Run `MJOLNIR_BUILD=999 ./gradlew :desktop:test --offline` and confirm it passes.
- [ ] **Step 5:** Commit `feat(desktop): voice rooms`.

---

### Task 5: Phone app — audio, foreground service, permission, UI

**Files:**
- Create in `android/src/main/kotlin/app/mjolnir/android/voice/`:
  - `AndroidAudio.kt`: `AudioRecord` with `MediaRecorder.AudioSource.VOICE_COMMUNICATION` at 48 kHz mono 16-bit, attaching `AcousticEchoCanceler`/`NoiseSuppressor` when `isAvailable()`. `AudioTrack` with `USAGE_VOICE_COMMUNICATION`. `AudioManager.mode = MODE_IN_COMMUNICATION` while live, restored after.
  - `VoiceService.kt`: a foreground service of type `microphone`. The notification is `Mjolnir voice — {room}` with Mute/Unmute and Leave actions. It starts on join and stops when the session ends.
- Modify:
  - `AndroidManifest.xml`: `RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`, `MODIFY_AUDIO_SETTINGS`, and the service with `android:foregroundServiceType="microphone"`
  - `MjolnirApp.kt` / `AppContainer`: create the `VoiceController`, route events from the watcher, refresh on spaces load and reconnect
  - the UI: the chat list (Voice section per space), a voice panel screen or sheet, and the permission flow
  - `Notifications.kt`: a voice channel, low importance and silent
- Test: `android/src/test/.../VoiceViewModelTest.kt`, plus logic for the notification actions where it can be tested without a device.

Steps:
- [ ] **Step 1: Write the failing tests.**
  - The ViewModel maps controller state to the UI: rooms per space, the active panel, and open mic as the default on the phone.
  - Join with no permission emits a request-permission effect; when refused, `Mjolnir needs the microphone for voice rooms.` shows with `Open settings`.
  - The notification-action intents map to `setMuted` and `leave`.
- [ ] **Step 2:** Run the tests and confirm they fail.
- [ ] **Step 3: Implement it.**
  - The voice session runs in the `VoiceService`'s lifetime, so it keeps going with the screen off.
  - The event socket's foreground rule is unchanged. Voice has its own socket, so it keeps working while the app is backgrounded.
  - Leaving or ending stops the service and restores the audio mode.
- [ ] **Step 4:** Run `MJOLNIR_BUILD=999 ./gradlew :android:testDebugUnitTest :android:lintDebug :android:assembleDebug --offline` and confirm it passes, lint included.
- [ ] **Step 5:** Commit `feat(android): voice rooms with a foreground service`.

---

### Task 6: Docs and full check

**Files:**
- `README.md`: a voice rooms section covering what it is, limits, push-to-talk, the phone notification, headphones on the PC, and `MJOLNIR_MAX_VOICE`.
- `docs/manual-verification.md`: a "Voice rooms" section listing:
  - everyone in Lounge hears everyone;
  - rings light up;
  - push-to-talk on the PC, holding `` ` `` or the button;
  - phone voice continues with the screen off;
  - notification Mute and Leave work;
  - a 9th person sees `Lounge is full (8/8)`;
  - turning phone Wi-Fi off and on shows `Reconnecting…` and rejoins;
  - removing someone from the space drops them from voice;
  - an old app ignores voice.

Steps:
- [ ] **Step 1:** Write the docs.
- [ ] **Step 2:** Run `MJOLNIR_BUILD=999 ./gradlew check :android:lintDebug :android:assembleDebug --offline` and confirm BUILD SUCCESSFUL.
- [ ] **Step 3:** Commit `docs: voice rooms`.
