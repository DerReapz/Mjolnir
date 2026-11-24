# Rooms: open channels, who's online, voice rooms: design

## Context
Space chats (private, invite-only, with approval and Join) are built and merged. The owner wants Discord-style group rooms, and has confirmed these choices:

- **Three features:** open channels, who's online, and voice rooms.
- **Order:** these come before accounts and are built on today's device identities. Accounts later moves all of this from devices to people, the same way it moves chats.
- **Voice:** small groups of up to about 8 people, audio only, with push-to-talk or open mic, on the phone and the PC.

There are two build plans, each shipped as its own update:
- **Plan A:** open channels plus who's online.
- **Plan B:** voice rooms.

People in Mjolnir are still devices, shown by nickname.

## 1. Open channels

### Rules
- **A chat has a kind, `PRIVATE` or `OPEN`.** Every existing chat becomes `PRIVATE` and behaves exactly as it does today.
- **Everyone in the space is in every open channel.** Nobody needs an invite, approval or Join.
  - When a channel is created, every current space member becomes a member.
  - When someone joins the space, they join every open channel in it.
  - When someone leaves the space or is removed from it, they leave its open channels.
  - Someone who joins later sees the full history, the same as private chats.
- **Who can do what:**
  - Any space member can create an open channel.
  - Any member can post, edit and delete their own messages.
  - The space admin can rename a channel, delete any message in it, and delete the channel.
  - The channel's creator can also rename or delete it.
  - Nobody can invite to, remove from, or leave an open channel, because membership follows the space.
- **Mute instead of leave.** A person can mute a channel. A muted channel sends no notifications or phone "doorbell", and its unread count shows grey instead of highlighted. Mute is per device and stored on the relay, so the phone's background notifications respect it too. Private chats can be muted the same way.
- **`#general`:**
  - Every new space gets a `general` channel automatically, created by the admin.
  - Spaces that already exist get one when the relay first starts on the new version.
  - `#general` can be renamed. If it is deleted, the space has no default channel. That is allowed.
- **Names:** 1–40 characters, the same as chat names. Channels show with a `#` in front, which is not part of the name.
- **Messages, media, video, mentions, receipts, edit and delete** all work exactly as in private chats.
  - `@everyone` is not added (my call: avoid pinging the whole space).
  - Read receipts ("Seen by") in a channel show only a count once more than 5 people have seen a message, so the list doesn't flood (my call).

### Relay
- `chats.kind TEXT NOT NULL DEFAULT 'PRIVATE'`.
- `chat_members.muted INTEGER NOT NULL DEFAULT 0`.
- Membership sync runs in the same transaction as the space join, leave or remove.
- Errors:
  - the invite, approve, remove-member, make-owner and leave routes refuse open channels with `not_for_open_channels`;
  - mute uses `PUT /v1/chats/{id}/mute {muted}`.
- The chat summary sent to clients gains `kind` and `muted`.

### Apps
- In each space's chat list, a **Channels** section (`# general`, `# games`, …) sits above **Private chats**.
- **New channel** sits next to **New chat**. It asks only for a name; there are no invites.
- In a channel's header, the ⋯ menu offers Mute/Unmute, plus Rename and Delete for the admin or creator. Invite, Members-manage and Leave are hidden.
- **Copy:**
  - `Channels`, `Private chats`, `New channel`, `Channel name`, `Mute`, `Unmute`, `Muted`;
  - `Everyone in {space} can see this channel.` (the empty channel's hint);
  - `Delete #{name}? Everyone in {space} loses its messages.`

## 2. Who's online

### Rules
- **Online** means the device has a live connection to the relay right now. A phone with the app closed is offline, even though it can still get notifications.
- **Who sees it:** only people who share at least one space with that device. Presence goes no further.
- **Where it shows:**
  - In the space's member list and the channel's member list: a green dot plus **Online**, or a grey dot plus **Last seen 5m ago** / **2h ago** / **yesterday** / **12 Nov**.
  - Next to a name in the chat list's direct context, a small green dot.
  - Once voice exists, also **In voice: Lounge**.
- **Flapping:** going offline is reported only after 30 seconds without a connection, so a phone switching networks doesn't flash offline.

### Relay
- It uses the existing `SessionHub` connection tracking and `last_seen`.
- **Live updates:** a new `RelayEvent.PresenceChanged(deviceId, online, lastSeen, voiceRoomId?)` is sent to connected devices that share a space with the device that changed.
- **Snapshot:** the space member list response gains `online` and `lastSeen` per member.
- An older app ignores the unknown event, because the event decoder already skips unknown types.

## 3. Voice rooms (plan B)

### Rules
- **Rooms:**
  - A voice room belongs to a space. Every space gets one `Lounge` room automatically, and existing spaces get one on first start.
  - Any space member can create more rooms. The space admin or the room's creator can rename or delete them.
- **Who can join:** any member of the space, with at most 8 people in a room (`MJOLNIR_MAX_VOICE`, default 8, max 16).
  - A full room shows `Lounge is full (8/8)`.
  - A device is in at most one room at a time; joining another leaves the first.
- **In the room:**
  - Everyone hears everyone.
  - Each person shows with a speaking ring that lights up while they talk, plus a muted icon when they are muted.
  - Buttons: **Mute**, **Leave**, and on the PC a **Push to talk** toggle.
  - People outside the room see who is in it under the room's name in the list (`🔊 Lounge — Sam, Pixel 8`).
- **Talking modes:**
  - **Phone:** open mic by default; the mute button toggles.
  - **PC:** push-to-talk by default (hold `` ` `` or the on-screen button); it can switch to open mic.
  - The PC has no echo cancellation, so open mic on the PC suggests headphones (`Use headphones to avoid echo`).
- **Phone in the background:**
  - Voice keeps running with the screen off or while another app is open, as a foreground service.
  - The notification reads `Mjolnir voice — Lounge`, with **Mute** and **Leave** buttons.
  - The first join asks for microphone permission. If it is refused, the app shows `Mjolnir needs the microphone for voice rooms.` with a button to open Settings.
- **Disconnects:**
  - If the connection drops, the app shows `Reconnecting…` and rejoins automatically for up to 30 seconds. After that it shows `You left Lounge (connection lost)`.
  - The relay removes a device from a room 10 seconds after its connection drops.
- **Not recorded.** The relay forwards audio and never stores it.

### Transport (my call)
Audio is forwarded by the relay over the existing secure WebSocket, not sent over WebRTC.
- **Why:** WebRTC needs open UDP ports or a TURN server. The relay host exposes only HTTPS through its reverse proxy, so WebRTC would need new network setup. The WebSocket already works everywhere the apps work.
- **Load:**
  - Opus at about 24–32 kbit/s per speaker, in 20 ms frames.
  - With 8 people, each device receives at most 7 streams, about 0.25 Mbit/s.
  - The relay forwards about 2 Mbit/s in total for a full room, which a home connection handles.
- **Framing:** binary WebSocket frames (`[room id][speaker id][sequence][Opus bytes]`).
  - The relay checks that the sender is in the room, rewrites the speaker id from the connection (so nobody can impersonate anyone), and forwards to the room's other members.
  - It never queues audio for a slow receiver. If a receiver's send buffer is full, that frame is dropped for that receiver only.
- **Events:** `VoiceRoomChanged(roomId, participants)` goes to the space's members whenever someone joins or leaves. Speaking state is worked out on each client from incoming audio.

### Audio engine (shared, in relay-client)
- **Opus:** encoded and decoded with **Concentus**, a pure-Java Opus, so neither app needs native libraries. 48 kHz mono, 20 ms frames, with in-band forward error correction on.
- **Per-speaker jitter buffer:** about 60 ms target, up to 200 ms. Missing frames are filled with Opus packet-loss concealment.
- **Mixer:** sums the speakers with clipping protection.
- **Speaking indicator:** a speaker's level stays above a threshold for at least 100 ms. When the mic is open, silent frames aren't sent, which also saves bandwidth.
- **Phone:** `AudioRecord` with the `VOICE_COMMUNICATION` source, Android's echo canceller and noise suppressor, `AudioTrack` playback, and a foreground service (`FOREGROUND_SERVICE_MICROPHONE`).
- **PC:** `javax.sound.sampled` capture and playback on the default devices. A Settings choice of microphone and speaker comes later, if wanted.

### Copy
- `Voice`, `Join voice`, `Leave`, `Mute`, `Unmute`, `Push to talk`, `Open mic`, `Hold \` to talk`;
- `In voice: {room}`, `{room} is full ({n}/{max})`, `Reconnecting…`, `You left {room} (connection lost)`;
- `Use headphones to avoid echo`, `Mjolnir needs the microphone for voice rooms.`, `New voice room`.

## Compatibility and updates
- Both plans need the relay and the apps updated together. The update system handles that:
  - the relay auto-updates;
  - the relay pushes `UpdateAvailable` to older apps.
- An old app against a new relay sees open channels as ordinary chats it is a member of, and ignores presence and voice events.
- A new app against an old relay hides Channels, presence and Voice, and shows `The relay needs updating` if someone tries to create a channel.

## Testing
- **Relay:**
  - open-channel membership follows the space (create, join, leave, remove);
  - private-chat routes refuse open channels;
  - mute suppresses the doorbell;
  - `#general` and `Lounge` are added to existing spaces exactly once;
  - presence reaches only people who share a space, with the 30-second offline delay;
  - voice frames go only to the room's other members, with the speaker id rewritten;
  - the room limit;
  - a slow receiver doesn't delay the others.
- **Client:**
  - the ChatController exposes kind and mute;
  - presence state;
  - Opus encode/decode round trip;
  - the jitter buffer (reordering, loss, late frames) and the mixer with synthetic frames.
- **Manual on devices** (two PCs and a phone in one space):
  - everyone sees and posts in `#general`;
  - muting stops notifications;
  - dots go grey about 30 seconds after an app closes;
  - in Lounge, everyone hears everyone and the rings light up;
  - push-to-talk works on the PC;
  - the phone keeps talking with the screen off;
  - a full room refuses the 9th person.
